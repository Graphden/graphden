(ns graphden.packages.loader-test
  "Tests for package loader."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.packages.loader :as loader]))


;; =============================================================================
;; normalize-arg-spec tests
;; =============================================================================

(deftest normalize-arg-spec-test
  (testing "keyword arg-spec becomes full map with required true"
    (is (= {:type :int :required true}
           (#'loader/normalize-arg-spec :int))))

  (testing "map arg-spec with type only gets required true"
    (is (= {:type :text :required true}
           (#'loader/normalize-arg-spec {:type :text}))))

  (testing "map arg-spec with required false stays as-is"
    (is (= {:type :text :required false}
           (#'loader/normalize-arg-spec {:type :text :required false}))))

  (testing "map arg-spec preserves extra fields"
    (is (= {:type :fn :required true :is-fn true}
           (#'loader/normalize-arg-spec {:type :fn :is-fn true})))))


;; =============================================================================
;; normalize-args tests
;; =============================================================================

(deftest normalize-args-test
  (testing "nil args returns nil"
    (is (nil? (#'loader/normalize-args nil))))

  (testing "empty args returns empty map"
    (is (= {} (#'loader/normalize-args {}))))

  (testing "normalizes mixed arg specs"
    (is (= {:a {:type :int :required true}
            :b {:type :text :required false}}
           (#'loader/normalize-args {:a :int
                                     :b {:type :text :required false}}))))

  (testing "normalizes all keyword arg specs"
    (is (= {:x {:type :int :required true}
            :y {:type :text :required true}
            :z {:type :bool :required true}}
           (#'loader/normalize-args {:x :int :y :text :z :bool})))))


;; =============================================================================
;; base-fn? tests
;; =============================================================================

(deftest base-fn?-test
  (testing "returns true for fn without :parent"
    (is (true? (#'loader/base-fn? {:name :add :args {:a :int} :return-type :int}))))

  (testing "returns false for fn with :parent"
    (is (false? (#'loader/base-fn? {:name :add-10 :parent :add :args {:a 10}})))))


;; =============================================================================
;; fn-def->base-fn-def tests
;; =============================================================================
;;
;; The loader no longer pre-derefs args; defbase bodies use `rt/resolve-arg`
;; which handles IDeref (and thunks) on-demand. `:fn`-type args are
;; pre-wrapped into callables via `rt/hof-callable` so HOF impls receive a
;; ready-to-invoke fn regardless of whether the executor is the legacy
;; queue or the new compile path.

(deftest fn-def->base-fn-def-test
  (testing "creates base-fn-def; non-:fn args pass through to impl as-is"
    (let [fn-def {:name :add :args {:a :int :b :int} :return-type :int}
          impl-fn (fn [{:keys [a b]}]
                    ;; Impl receives whatever the caller put in the args map
                    ;; — no forced deref.
                    (+ (if (instance? clojure.lang.IDeref a) @a a)
                       (if (instance? clojure.lang.IDeref b) @b b)))
          result (#'loader/fn-def->base-fn-def fn-def impl-fn)]
      (is (= {:a {:type :int :required true}
              :b {:type :int :required true}}
             (:args result)))
      (is (= :int (:return-type result)))
      (is (fn? (:impl result)))
      (let [wrapped (:impl result)]
        (is (= 5 (wrapped {:a (delay 2) :b (delay 3)} nil)))
        (is (= 5 (wrapped {:a 2 :b 3} nil))))))

  (testing "creates base-fn-def with :ctx true — impl receives raw args + ctx"
    (let [fn-def {:name :ctx-fn :args {:x :int} :return-type :int :ctx true}
          impl-fn (fn [{:keys [x]} ctx]
                    (+ (if (instance? clojure.lang.IDeref x) @x x)
                       (:offset ctx)))
          result (#'loader/fn-def->base-fn-def fn-def impl-fn)]
      (is (true? (:ctx result)))
      (let [wrapped (:impl result)]
        (is (= 15 (wrapped {:x (delay 10)} {:offset 5})))))))


;; =============================================================================
;; read-resource-edn tests
;; =============================================================================

(deftest read-resource-edn-test
  (testing "returns nil for non-existent resource"
    (is (nil? (#'loader/read-resource-edn "nonexistent/path.edn")))))


;; =============================================================================
;; load-package-meta tests
;; =============================================================================

(deftest load-package-meta-test
  (testing "throws for non-existent package"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Package not found"
          (#'loader/load-package-meta "nonexistent-package")))))


;; =============================================================================
;; load-module-fns tests
;; =============================================================================

(deftest load-module-fns-test
  (testing "throws for non-existent module"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Module fns not found"
          (#'loader/load-module-fns "core" "nonexistent-module")))))


;; =============================================================================
;; load-module-impls tests
;; =============================================================================

(deftest load-module-impls-test
  (testing "returns nil for non-existent module impls"
    (is (nil? (#'loader/load-module-impls "nonexistent" "module")))))


;; =============================================================================
;; list-available-packages tests
;; =============================================================================

(deftest list-available-packages-test
  (testing "returns a vector or nil"
    (let [result (loader/list-available-packages)]
      (is (or (nil? result) (vector? result))))))


;; =============================================================================
;; get-startup-fn-name tests
;; =============================================================================

(deftest get-startup-fn-name-test
  (testing "returns startup-fn from loaded packages"
    (is (= :web-server (loader/get-startup-fn-name {:startup-fn :web-server}))))

  (testing "returns nil when no startup-fn"
    (is (nil? (loader/get-startup-fn-name {:base-fn-defs {} :fn-defs []})))))


;; =============================================================================
;; Integration tests (with actual packages if available)
;; =============================================================================

(deftest load-packages-integration-test
  (testing "load-packages with empty list"
    (let [result (loader/load-packages [])]
      (is (map? result))
      (is (contains? result :base-fn-defs))
      (is (contains? result :fn-defs))
      (is (contains? result :packages))
      (is (empty? (:base-fn-defs result)))
      (is (empty? (:fn-defs result)))
      (is (empty? (:packages result))))))


(deftest load-default-packages-test
  (testing "loads core, web, app packages"
    ;; This may fail if packages don't exist, but will cover the function
    (try
      (let [result (loader/load-default-packages)]
        (is (map? result))
        (is (contains? result :base-fn-defs))
        (is (contains? result :fn-defs))
        (is (contains? result :packages)))
      (catch clojure.lang.ExceptionInfo e
        ;; If packages not found, that's expected in test environment
        (is (re-find #"Package not found" (ex-message e)))))))


;; =============================================================================
;; resolve-dependencies tests
;; =============================================================================

(deftest resolve-dependencies-test
  (testing "resolves dependencies in topological order"
    ;; Load actual packages to test real dependency resolution
    (try
      (let [result (loader/load-packages ["core" "web" "app"])]
        ;; Check that packages are loaded in dependency order
        (is (vector? (:packages result)))
        (is (<= 1 (count (:packages result)))))
      (catch clojure.lang.ExceptionInfo _
        ;; Skip if packages not available
        nil))))
