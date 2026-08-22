(ns graphden.packages.web.effect-trace-test
  "Smoke tests that the runtime-effect instrumentation added to web/crud
   and web/http base-fn impls actually fires when the impl runs.

   These are contract tests: production behaviour relies on
   `(cr/record-effect! :db)` / `:network` being present in each
   defbase body so the fn-execution reaper can snapshot the observed
   effect set. A regression here would silently empty `:runtime-effects`
   on persisted rows.

   Pattern matches `reitit-test`: load impls dynamically via
   `load-file` + `find-ns` so the test doesn't pull all classpath
   resources through the package loader."
  (:require
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing]]
    [graphden.executor.compile-runtime :as cr]))


;; =============================================================================
;; Dynamic impl loading (mirrors reitit-test)
;; =============================================================================

(defn- load-impls-ns
  [resource-path ns-sym]
  (let [r (io/resource resource-path)]
    (when r
      (load-file (java.io.File/.getPath (io/file r))))
    (find-ns ns-sym)))


(def ^:private crud-ns
  (load-impls-ns "packages/web/crud/impls.clj"
                 'graphden.packages.web.crud.impls))


(def ^:private crud-types-ns
  ;; `web.crud` is one graph namespace spread over seven MODULE directories
  ;; (see `web/crud/fns.edn`); `to-set` lives in the type-API one. The impls
  ;; `ns` symbol follows the directory, so it loads by its own path.
  (load-impls-ns "packages/web/crud-types/impls.clj"
                 'graphden.packages.web.crud-types.impls))


(def ^:private http-ns
  (load-impls-ns "packages/web/http/impls.clj"
                 'graphden.packages.web.http.impls))


(def ^:private registry-ns
  ;; registry is its own package now — impls moved to packages/registry/registry/;
  ;; the impls `ns` symbol is unchanged (identity is uuid-v5(namespace,name), and
  ;; `load-impls-ns` loads by PATH + reads the ns from the file).
  (load-impls-ns "packages/registry/registry/impls.clj"
                 'graphden.packages.app.registry.impls))


(defn- unwrap
  [ns-obj sym]
  (when ns-obj
    @(ns-resolve ns-obj sym)))


;; =============================================================================
;; web/http — :http-stop records :network with no real server side-effect
;;
;; http-server starts an http-kit listener (binds a port) so it isn't a
;; safe target for a unit test; http-stop is the simpler shim. Both
;; record :network via `(cr/record-effect! :network)`, so verifying one
;; covers the instrumentation contract for the pair.
;; =============================================================================

(deftest http-stop-records-network-effect-test
  (let [http-stop (unwrap http-ns 'http-stop)
        trace (atom #{})]
    (is (some? http-stop) "http-stop impl loaded")
    (binding [cr/*effect-trace* trace]
      ;; nil server — the `(when server ...)` branch skips; we only
      ;; care that record-effect! fires before it.
      (http-stop {:server nil} nil))
    (is (contains? @trace :network)
        ":network recorded on http-stop call")))


;; =============================================================================
;; web/crud — each of the 5 entity CRUD base-fns must record :db
;;
;; Calling list-entities / get-entity / create-entity / update-entity /
;; delete-entity needs an actual storage handle to dispatch off ctx.
;; We instead test the record-effect! contract by invoking each impl
;; with a deliberately-broken ctx that triggers an exception INSIDE the
;; entities/* call but AFTER (cr/record-effect! :db) has fired.
;; =============================================================================

(defn- assert-records-db
  [impl-sym args]
  (let [impl (unwrap crud-ns impl-sym)
        trace (atom #{})]
    (is (some? impl) (str impl-sym " impl loaded"))
    (binding [cr/*effect-trace* trace]
      (try (impl args nil)
           ;; record-effect! runs first; the entities/* call then
           ;; explodes on the nil ctx (no :storage key). We don't
           ;; care about the exception — only about whether the
           ;; effect was recorded BEFORE it threw.
           (catch Exception _)))
    (is (contains? @trace :db)
        (str ":db recorded on " impl-sym " call"))))


(deftest get-entity-records-db-effect-test
  (assert-records-db 'get-entity
                     {:entity-type :branch :id (java.util.UUID/randomUUID)}))


(deftest delete-entity-records-db-effect-test
  (assert-records-db 'delete-entity
                     {:entity-type :branch :id (java.util.UUID/randomUUID)}))


;; =============================================================================
;; app/registry — :publish-package-apply records BOTH :db and :time
;;
;; The fns.edn declaration is `:effects #{:db :time}`. The impl records
;; both at the top of the body (before `require-storage`), so a nil ctx
;; explodes on storage AFTER both effects have fired. Guards against the
;; declaration and the runtime instrumentation drifting apart — the
;; :time effect exists because the impl stamps `Instant/now` on the row.
;; (`:publish-package` itself is a graph `:if` over this core now.)
;; =============================================================================

(deftest publish-package-apply-records-db-and-time-effects-test
  (let [impl (unwrap registry-ns 'publish-package-apply)
        trace (atom #{})]
    (is (some? impl) "publish-package-apply impl loaded")
    (binding [cr/*effect-trace* trace]
      (try (impl {:pkg-name "x" :pkg-version "1.0.0" :bundle {:fns [{:name :x}]}} nil)
           (catch Exception _)))
    (is (contains? @trace :db)
        ":db recorded on publish-package-apply call")
    (is (contains? @trace :time)
        ":time recorded on publish-package-apply call (matches fns.edn :effects)")))


(deftest pure-impls-do-not-record-effects-test
  (testing "control: a known-pure impl from this same registry does NOT touch *effect-trace*"
    ;; `to-set-fn` is the pure-coercion control case — `(set coll)`
    ;; with no record-effect! call in the body. (Pre-fix this used
    ;; `all-rich-types`, but that one ACTUALLY does read storage via
    ;; `cached-or-load-graph` and now correctly records :db; using
    ;; it here was hiding the missing-record-effect bug across the
    ;; whole `_*-apply` family. `to-set-fn` is genuinely pure.)
    (let [impl (unwrap crud-types-ns 'to-set-fn)
          trace (atom #{})]
      (is (some? impl))
      (binding [cr/*effect-trace* trace]
        (try (impl {:coll [1 2 3]} nil) (catch Exception _)))
      (is (empty? @trace)
          "pure impl did NOT record any effect"))))
