(ns graphden.packages.core.refinements-test
  "Direct unit tests for the runtime `:ensure-*` narrowers shipped by
   `core.refinements/impls.clj`. Each impl validates a single
   constraint at execute time and either returns its input (narrowed
   to the refined type from the type system's view) or throws
   `:refinement/violated` with the constraint that failed.

   `impls.clj` lives under `resources/packages/...` and is loaded by
   the package loader's `load-module-impls` (slurp + eval, not a
   conventional require). The fixture below invokes the loader once
   so the symbols are reachable for the rest of the suite, then each
   test resolves the impl by name from the returned map.

   The defbase macro generates a fn of shape `[__args ctx]` that
   forces delays on access — tests mirror that wire format, wrapping
   values in `(delay ...)` and passing nil for ctx."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]))


(def ^:dynamic *impls* nil)


(defn- load-refinement-impls-fixture
  "Slurp + eval the package's impls.clj (mirrors the runtime loader's
   load-module-impls — which is private, hence requiring-resolve) so
   the defbase'd symbols become reachable and bind their impls map to
   `*impls*` for the suite. Doing it via the loader instead of a
   top-level `require` keeps the test honest about how impls.clj is
   actually consumed in production."
  [f]
  (binding [*impls* ((requiring-resolve 'graphden.packages.loader/load-module-impls)
                     "core" "refinements")]
    (f)))


(use-fixtures :once load-refinement-impls-fixture)


(defn- impl-of
  [kw]
  (let [entry (or (get *impls* kw)
                  (throw (ex-info (str "No impl for " kw) {:available (keys *impls*)})))]
    ;; impls.clj values are either a bare impl-fn or a
    ;; `{:impl … :return-type-rule …}` map (every fn that participates
    ;; in `:secret`-taint propagation moved to the map form in T3).
    (if (map? entry) (:impl entry) entry)))


(defn- call
  "Invoke a defbase impl with a single :value binding."
  [impl v]
  (impl {:value (delay v)} nil))


(defn- ex-of
  "Return the ex-data :type tag thrown by calling `impl` with `v`, or
   :no-throw if the impl returned cleanly."
  [impl v]
  (try (call impl v) :no-throw
       (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))


;; -----------------------------------------------------------------------------
;; :ensure-url

(deftest ensure-url-accepts-http-and-https
  (let [impl (impl-of :ensure-url)]
    (testing "http:// and https:// pass through unchanged"
      (is (= "http://example.com"  (call impl "http://example.com")))
      (is (= "https://example.com" (call impl "https://example.com")))
      (is (= "https://unpkg.com/cytoscape@3.30.4/dist/cytoscape.min.js"
             (call impl "https://unpkg.com/cytoscape@3.30.4/dist/cytoscape.min.js"))))))


(deftest ensure-url-rejects-non-http-schemes
  (let [impl (impl-of :ensure-url)]
    (testing "ftp / file / mailto / bare hostname → :refinement/violated"
      (is (= :refinement/violated (ex-of impl "ftp://example.com")))
      (is (= :refinement/violated (ex-of impl "file:///tmp/x")))
      (is (= :refinement/violated (ex-of impl "mailto:a@b.c")))
      (is (= :refinement/violated (ex-of impl "example.com")))
      (is (= :refinement/violated (ex-of impl ""))))))


(deftest ensure-url-rejects-non-strings
  (let [impl (impl-of :ensure-url)]
    (testing "non-string inputs throw"
      (is (= :refinement/violated (ex-of impl 42)))
      (is (= :refinement/violated (ex-of impl nil)))
      (is (= :refinement/violated (ex-of impl :keyword))))))


(deftest ensure-url-ex-data-shape
  (let [impl (impl-of :ensure-url)]
    (testing "violation carries the constraint that was checked"
      (let [ex (try (call impl "nope")
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (= :refinement/violated  (:type (ex-data ex))))
        (is (= :url                  (:refine-name (ex-data ex))))
        (is (= [:matches "^https?://"] (:constraint (ex-data ex))))
        (is (= "nope"                (:value (ex-data ex))))))))


;; -----------------------------------------------------------------------------
;; :ensure-non-blank-text

(deftest ensure-non-blank-text-accepts-strings-with-non-ws
  (let [impl (impl-of :ensure-non-blank-text)]
    (testing "any non-whitespace character anywhere in the string passes"
      (is (= "hello"       (call impl "hello")))
      (is (= "  hello  "   (call impl "  hello  ")))
      (is (= "x"           (call impl "x")))
      (is (= "\t a \n"     (call impl "\t a \n"))))))


(deftest ensure-non-blank-text-rejects-blank-and-empty
  (let [impl (impl-of :ensure-non-blank-text)]
    (testing "empty string and whitespace-only strings throw"
      (is (= :refinement/violated (ex-of impl "")))
      (is (= :refinement/violated (ex-of impl " ")))
      (is (= :refinement/violated (ex-of impl "\t\n  "))))))


(deftest ensure-non-blank-text-rejects-non-strings
  (let [impl (impl-of :ensure-non-blank-text)]
    (testing "non-string inputs throw"
      (is (= :refinement/violated (ex-of impl 0)))
      (is (= :refinement/violated (ex-of impl nil)))
      (is (= :refinement/violated (ex-of impl [\a \b]))))))


(deftest ensure-non-blank-text-ex-data-shape
  (let [impl (impl-of :ensure-non-blank-text)]
    (testing "violation names :non-blank-text and the :matches constraint"
      (let [ex (try (call impl "   ")
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (= :refinement/violated   (:type (ex-data ex))))
        (is (= :non-blank-text        (:refine-name (ex-data ex))))
        (is (= [:matches "\\S"]       (:constraint (ex-data ex))))
        (is (= "   "                  (:value (ex-data ex))))))))


(deftest impls-map-exposes-new-narrowers
  (testing ":ensure-url and :ensure-non-blank-text are in the impls map"
    (is (contains? *impls* :ensure-url))
    (is (contains? *impls* :ensure-non-blank-text))
    (is (fn? (impl-of :ensure-url)))
    (is (fn? (impl-of :ensure-non-blank-text)))))
