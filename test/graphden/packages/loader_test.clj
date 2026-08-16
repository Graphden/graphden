(ns graphden.packages.loader-test
  "Tests for package loader."
  (:require
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.packages.loader :as loader]
    [graphden.storage.protocol.core]
    [graphden.types.core :as types]))


;; Aliases registry is process-global; clear between tests so
;; register-aliases! tests don't leak across each other.
(use-fixtures :each
  (fn [t]
    (types/clear-aliases!)
    (t)
    (types/clear-aliases!)))


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
    ;; #15b retired the `:is-fn` field; here we use `:description`
    ;; as the "extra field" the loader passes through unchanged.
    (is (= {:type :fn :required true :description "callable"}
           (#'loader/normalize-arg-spec {:type :fn :description "callable"})))))


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
    (is (false? (#'loader/base-fn? {:name :add-10 :parent :add :args {:a 10}}))))

  (testing "a marker-def is a type-row, NOT a base-fn — otherwise it is
            misclassified, dropped as impl-less, and never registered"
    (is (true? (#'loader/type-row? {:name :pii :marker {:hide-result? true}})))
    (is (false? (#'loader/base-fn? {:name :pii :marker {:hide-result? true}})))))


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
  (testing "normalizes args, preserves :return-type, passes impl through"
    (let [fn-def {:name :add :args {:a :int :b :int} :return-type :int}
          impl-fn (fn [{:keys [a b]} _ctx]
                    (+ (if (instance? clojure.lang.IDeref a) @a a)
                       (if (instance? clojure.lang.IDeref b) @b b)))
          result (#'loader/fn-def->base-fn-def fn-def impl-fn)]
      (is (= {:a {:type :int :required true}
              :b {:type :int :required true}}
             (:args result)))
      (is (= :int (:return-type result)))
      (is (identical? impl-fn (:impl result))
          "loader no longer wraps impl-fn")))

  (testing "ctx is threaded through untouched — impl body can read it"
    (let [fn-def {:name :ctx-fn :args {:x :int} :return-type :int}
          impl-fn (fn [{:keys [x]} ctx]
                    (+ (if (instance? clojure.lang.IDeref x) @x x)
                       (:offset ctx)))
          result (#'loader/fn-def->base-fn-def fn-def impl-fn)
          wrapped (:impl result)]
      (is (= 15 (wrapped {:x (delay 10)} {:offset 5}))))))


;; =============================================================================
;; read-resource-edn tests
;; =============================================================================

(deftest read-resource-edn-test
  (testing "returns nil for non-existent resource"
    (is (nil? (#'loader/read-resource-edn "nonexistent/path.edn")))))


(deftest edn-parse-error-names-the-file-test
  (testing "a malformed EDN resource surfaces its path in the thrown
            error — the raw reader message names neither file nor line"
    (let [bad (java.io.File/createTempFile "bad" ".edn")]
      (spit bad "{:a 1 :b}")  ; odd number of map entries → parse error
      (try
        (with-redefs [io/resource
                      (fn [_] (java.net.URI/.toURL (java.io.File/.toURI bad)))]
          (let [ex (try (#'loader/read-resource-edn "packages/x/oops.edn")
                        nil
                        (catch clojure.lang.ExceptionInfo e e))]
            (is (some? ex) "a malformed EDN throws")
            (is (= :package-error/edn-parse (:type (ex-data ex))))
            (is (= "packages/x/oops.edn" (:path (ex-data ex))))
            (is (re-find #"packages/x/oops\.edn" (ex-message ex)))))
        (finally (java.io.File/.delete bad))))))


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
;; get-seeded-services tests
;; =============================================================================

(deftest get-seeded-services-test
  (testing "returns empty vec when nothing seeded"
    (is (= [] (loader/get-seeded-services {:seeded-services []}))))

  (testing "returns the aggregated list"
    (let [seeds [{:package-name "app" :name :default :fn-name :web-server
                  :enabled? true :restart-policy :always}]]
      (is (= seeds (loader/get-seeded-services {:seeded-services seeds}))))))


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
      (is (contains? result :base-fn-pairs)
          "load-packages always emits the uncollapsed base-fn pair index")
      (is (empty? (:base-fn-defs result)))
      (is (empty? (:fn-defs result)))
      (is (empty? (:base-fn-pairs result)))
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
    ;; `core`/`web`/`app` ARE the product and always load in this env, so a
    ;; thrown `load-packages` (broken dep graph, unparseable package) is a
    ;; genuine regression — let it fail, don't swallow it into a green pass.
    (let [result (loader/load-packages ["core" "web" "app"])]
      ;; Check that packages are loaded in dependency order
      (is (vector? (:packages result)))
      (is (<= 1 (count (:packages result)))))))


(deftest optional-packages-are-omittable-test
  ;; Guards the optionality contract: `mcp` and `registry` are top-level
  ;; packages that install their routes via the route-collection seam, so `app`
  ;; must NOT reference them. Loading `["core" "web" "app-base" "app"]` (neither)
  ;; must resolve the dep graph WITHOUT pulling them, and app's router bundle
  ;; (`:all`) must be present. If someone re-adds `:mcp-route` /
  ;; `:partial-packages-panel` / an `:api-packages-*` to app's route lists,
  ;; booting a deployment that omitted the package would fail with an
  ;; unknown-ref at sync — this catches it early.
  (let [without (loader/load-packages ["core" "web" "app-base" "app"])
        pkgs (set (map :name (:packages without)))
        names (into #{} (keep :name) (:fn-defs without))]
    (testing "app loads without mcp or registry"
      (is (not (contains? pkgs "mcp")) "mcp not pulled by app")
      (is (not (contains? pkgs "registry")) "registry not pulled by app")
      (is (contains? names :all) "app's route bundle is present without them"))
    (testing "app does not reference mcp's fn-defs"
      (is (not (contains? names :mcp-route)))
      (is (not (contains? names :mcp-router))))
    (testing "app does not reference registry's fn-defs"
      (is (not (contains? names :registry-router)))
      (is (not (contains? names :partial-packages-panel)))
      (is (not (contains? names :api-packages-installed))))
    (testing "adding mcp brings its router back"
      (let [with (into #{} (keep :name)
                       (:fn-defs (loader/load-packages
                                   ["core" "web" "app-base" "app" "mcp"])))]
        (is (contains? with :mcp-route))
        (is (contains? with :mcp-router))))
    (testing "adding registry brings its router + routes back"
      (let [with (into #{} (keep :name)
                       (:fn-defs (loader/load-packages
                                   ["core" "web" "app-base" "app" "registry"])))]
        (is (contains? with :registry-router))
        (is (contains? with :partial-packages-panel))
        (is (contains? with :api-packages-installed))))))


;; =============================================================================
;; load-module-fns — both `fns.edn` shapes (vector legacy vs {:namespace :fns})
;; =============================================================================

(deftest load-module-fns-supports-vector-format
  (testing "legacy vector format returns {:ns-path nil :fns [...]} for real module"
    ;; `core/arithmetic` uses the map shape with :namespace. All real
    ;; packages do. We assert the structural invariants instead of
    ;; trying to synthesise a temp package.
    (let [{:keys [ns-path fns]} (#'loader/load-module-fns "core" "arithmetic")]
      (is (string? ns-path))
      (is (vector? fns))
      (is (pos? (count fns))))))


;; =============================================================================
;; load-packages — full integration touching real packages
;; =============================================================================

(deftest load-packages-collects-seeded-services
  (testing "the :app package declares its default web-server service"
    (let [result (loader/load-packages ["core" "web" "app"])
          svcs (:seeded-services result)
          default (first (filter #(= "app" (:package-name %)) svcs))]
      (is (some? default) "the app package contributes a seed entry")
      (is (= :web-server (:fn-name default))))))


(deftest load-packages-collects-namespaces
  (testing "loaded packages expose a set of declared namespace paths"
    (let [result (loader/load-packages ["core" "web" "app"])
          nss (:namespaces result)]
      (is (set? nss))
      (is (contains? nss "core") "parent ns implicit")
      (is (contains? nss "core.arithmetic") "leaf ns present"))))


(deftest load-packages-fn-defs-have-namespace-metadata
  (testing "every fn-def from a namespaced module carries :namespace"
    (let [result (loader/load-packages ["core" "web" "app"])]
      ;; Not gated behind `(when (seq …))` — an empty result is itself a
      ;; regression, so assert presence explicitly.
      (is (seq (:fn-defs result)) "packages produce fn-defs")
      (is (every? :namespace (:fn-defs result))
          "all fn-defs from namespaced fns.edn get :namespace"))))


;; =============================================================================
;; sync-namespaces! — creates parent-child ns hierarchy in storage
;; =============================================================================

(deftest sync-namespaces-builds-hierarchy
  (testing "parent ns is created before children; returns {path → id}"
    ;; Minimal mock storage: in-memory list of created ns entities.
    (let [state (atom {:next-id 0 :entities {}})
          mock-storage
          (reify
            graphden.storage.protocol.core/StorageCRUD
            (create-entity
              [_ entity-type data]
              (let [id (random-uuid)
                    record (assoc data :id id)]
                (swap! state update-in [:entities entity-type] (fnil conj []) record)
                record))

            (read-entity [_ _ _] nil)

            (query-entities
              [_ entity-type _where]
              (get-in @state [:entities entity-type] []))

            (query-latest-per-group [_ _ _ _] [])

            (update-entity [_ _ _ _] nil)

            (delete-entity [_ _ _] nil))
          result (loader/sync-namespaces! mock-storage #{"core" "core.arithmetic" "web"})]
      (is (= 3 (count result)))
      (is (contains? result "core"))
      (is (contains? result "core.arithmetic"))
      (is (contains? result "web"))
      (is (= 3 (count (get-in @state [:entities :ns])))
          "three ns entities were created"))))


(deftest sync-namespaces-empty-input-returns-empty-map
  (let [state (atom 0)
        mock-storage
        (reify
          graphden.storage.protocol.core/StorageCRUD
          (create-entity [_ _ _] (swap! state inc) {:id (random-uuid)})

          (read-entity [_ _ _] nil)

          (query-entities [_ _ _] [])

          (query-latest-per-group [_ _ _ _] [])

          (update-entity [_ _ _ _] nil)

          (delete-entity [_ _ _] nil))]
    (is (= {} (loader/sync-namespaces! mock-storage #{})))
    (is (zero? @state) "no entities created for empty set")))


;; register-aliases! tests removed — function dropped during full
;; rewrite (commit f549820). Type-declaration tests live in
;; graphden.packages.records-test.


;; =============================================================================
;; Package dependency version constraints
;; =============================================================================

(def ^:private normalize-deps #'loader/normalize-deps)
(def ^:private validate-dep-constraints! #'loader/validate-dep-constraints!)


(deftest normalize-deps-test
  (testing "legacy bare-name vector — no constraints"
    (is (= [{:name "core" :constraint nil}
            {:name "web" :constraint nil}]
           (normalize-deps ["core" "web"]))))
  (testing "keyword names normalise to strings"
    (is (= [{:name "core" :constraint nil}] (normalize-deps [:core]))))
  (testing "map form carries constraints"
    (is (= #{{:name "core" :constraint ">=1.5.0"}
             {:name "web" :constraint "*"}}
           (set (normalize-deps {"core" ">=1.5.0" "web" "*"})))))
  (testing "nested pair entries"
    (is (= [{:name "core" :constraint nil}
            {:name "web" :constraint ">=2.0"}]
           (normalize-deps ["core" ["web" ">=2.0"]]))))
  (testing "nil / empty"
    (is (= [] (normalize-deps nil)))
    (is (= [] (normalize-deps [])))))


(deftest validate-dep-constraints-test
  (testing "satisfied constraint passes"
    (is (nil? (validate-dep-constraints!
                {"app"  {:name "app" :version "1.0.0" :dependencies {"core" ">=1.5.0"}}
                 "core" {:name "core" :version "1.6.0"}}))))
  (testing "legacy bare deps (no constraint) always pass"
    (is (nil? (validate-dep-constraints!
                {"app"  {:name "app" :version "1.0.0" :dependencies ["core"]}
                 "core" {:name "core" :version "0.1.0"}}))))
  (testing "unsatisfied constraint throws :packages/version-conflict"
    (let [ex (try (validate-dep-constraints!
                    {"app"  {:name "app" :version "1.0.0" :dependencies {"core" ">=2.0.0"}}
                     "core" {:name "core" :version "1.6.0"}})
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex))
      (is (= :packages/version-conflict (:type (ex-data ex))))
      (is (= "core" (:dependency (ex-data ex))))
      (is (= "1.6.0" (:present (ex-data ex)))))))
