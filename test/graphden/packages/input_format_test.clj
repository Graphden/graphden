(ns ^:serial graphden.packages.input-format-test
  "Serial because the synthetic resource provider replaces io/resource.
   These tests load only tiny fixtures, never the application's packages."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest is]]
    [graphden.packages.loaded :as loaded]
    [graphden.packages.loader :as loader])
  (:import
    (java.nio.file
      Files)))


(def ^:dynamic *on-effect* nil)


(defn record-effect!
  []
  (*on-effect*))


(defn- with-sources
  [sources f]
  (let [root (.toFile (Files/createTempDirectory "graphden-format-test-" (make-array java.nio.file.attribute.FileAttribute 0)))
        resource io/resource
        reads (atom [])]
    (try
      (doseq [[path content] sources]
        (let [file (io/file root path)]
          (io/make-parents file)
          (spit file content)))
      (binding [loaded/*roster-override* (atom [])]
        (with-redefs [io/resource (fn [path]
                                    (if (str/starts-with? path "packages/format-fixture-")
                                      (do
                                        (swap! reads conj path)
                                        (let [file (io/file root path)]
                                          (when (.isFile file) (.toURL (.toURI file)))))
                                      (resource path)))]
          (f {:root root :reads reads})))
      (finally
        (when (find-ns 'graphden.packages.input-format-fixture)
          (remove-ns 'graphden.packages.input-format-fixture))
        (doseq [file (reverse (file-seq root))] (io/delete-file file))))))


(defn- package-source
  [modules dependencies]
  (pr-str {:name "format-fixture-main" :version "1.0.0"
           :modules modules :dependencies dependencies}))


(def ^:private impl-source
  (str "(ns graphden.packages.input-format-fixture)\n"
       "(graphden.packages.input-format-test/record-effect!)\n"
       "(def impls {:echo (fn [args _ctx] (:x args))})"))


(def ^:private early-source
  (pr-str {:namespace "format.fixture"
           :fns [{:name :echo :args {:x :int} :return-type :int}]}))


(defn- input-format-error
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))


(deftest late-unsupported-header-prevents-all-earlier-implementation-effects
  (doseq [format [2 19 nil "2"]
          across-dependency? [false true]]
    (let [effects (atom 0)
          early-package (if across-dependency? "format-fixture-dep" "format-fixture-main")
          early-root (str "packages/" early-package "/")
          sources (cond-> {"packages/format-fixture-main/package.edn"
                           (package-source (if across-dependency? ["late"] ["early" "late"])
                                           (if across-dependency? ["format-fixture-dep"] []))
                           (str early-root "early/fns.edn") early-source
                           (str early-root "early/impls.clj") impl-source
                           ;; Invalid legacy keys would fail the old validator:
                           ;; the header must win, before validation or eval.
                           "packages/format-fixture-main/late/fns.edn"
                           (pr-str {:input-format format :fns [{:name :late "bad-key" 1}]})}
                    across-dependency?
                    (assoc "packages/format-fixture-dep/package.edn"
                           (pr-str {:name "format-fixture-dep" :version "1.0.0" :modules ["early"]})))]
      (binding [*on-effect* #(swap! effects inc)]
        (with-sources sources
          (fn [{:keys [reads]}]
            (let [failure (input-format-error #(loader/load-packages ["format-fixture-main"]))]
              (is (= :package-error/input-format (:type failure)))
              (is (= "packages/format-fixture-main/late/fns.edn" (:path failure)))
              (is (zero? @effects))
              (is (not-any? #(str/ends-with? % "/impls.clj") @reads))
              (is (empty? (loaded/read-roster))))))))))


(deftest valid-v1-consumes-the-validated-snapshot-and-only-requested-closure
  (doseq [explicit-version? [false true]]
    (let [effects (atom 0)
          late-source (cond-> {:namespace "format.fixture"
                               :fns [{:name :later :parent :echo
                                      :args {:x 3 :input {:value {:bind 1 :input 2}}
                                             :bind {:value false}}}]}
                        explicit-version? (assoc :input-format 1))
          late-path "packages/format-fixture-main/late/fns.edn"]
      (with-sources {"packages/format-fixture-main/package.edn" (package-source ["early" "late"] [])
                     "packages/format-fixture-main/early/fns.edn" early-source
                     "packages/format-fixture-main/early/impls.clj" impl-source
                     late-path (pr-str late-source)
                     "packages/format-fixture-unrequested/package.edn" "malformed"}
        (fn [{:keys [root reads]}]
          (binding [*on-effect* (fn []
                                  (swap! effects inc)
                                  ;; The source validated earlier remains the
                                  ;; source used later, even if a file changes.
                                  (spit (io/file root late-path)
                                        "{:input-format 2 :fns []}"))]
            (let [result (loader/load-packages ["format-fixture-main"])
                  echo (get-in result [:base-fn-defs :echo])
                  later (first (:fn-defs result))]
              (is (= 1 @effects))
              (is (= 7 ((:impl echo) {:x 7} nil)))
              (is (= {:x {:type :int :required true}} (:args echo)))
              (is (= :later (:name later)))
              (is (= {:x 3 :input {:value {:bind 1 :input 2}} :bind {:value false}}
                     (:args later)))
              (is (= late-path (:source-file later)))
              (is (= 1 (count (loaded/read-roster))))
              (is (= 1 (count (filter #{late-path} @reads))))
              (is (= 1 (count (filter #{"packages/format-fixture-main/package.edn"} @reads))))
              (is (not-any? #(str/includes? % "unrequested") @reads)))))))))


(deftest unversioned-and-v1-structured-forms-cannot-bypass-guard
  (doseq [explicit-version? [false true]
          key [:input :bind :configure]]
    (let [effects (atom 0)
          source (cond-> {:fns [{:name :late key {}}]}
                   explicit-version? (assoc :input-format 1))]
      (binding [*on-effect* #(swap! effects inc)]
        (with-sources {"packages/format-fixture-main/package.edn" (package-source ["early" "late"] [])
                       "packages/format-fixture-main/early/fns.edn" early-source
                       "packages/format-fixture-main/early/impls.clj" impl-source
                       "packages/format-fixture-main/late/fns.edn" (pr-str source)}
          (fn [_]
            (let [failure (input-format-error #(loader/load-packages ["format-fixture-main"]))]
              (is (= :mixed-input-format (:code failure)))
              (is (zero? @effects)))))))))
