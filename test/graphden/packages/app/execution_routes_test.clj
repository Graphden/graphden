(ns graphden.packages.app.execution-routes-test
  "Contract tests for the /api/execute* routes' auth posture. The
   actual auth-required middleware is wired via `:parent` selection
   in `resources/packages/app/routes/fns.edn` — concrete routes
   inherit from `:post` / `:get-auth-required` / `:put` (templates in
   `app.routes.auth` that bake in `auth-required-middleware`), or from
   `:post-route` / `:get-route` (which DON'T).

   Without these tests, accidentally re-parenting an execution route
   from `:post` to `:post-route` (or moving `:auth-check` off
   `:get-auth-required`) would silently expose admin endpoints and no
   CI signal would fire. We assert each route by name carries an
   auth-required parent — pure EDN comparison, no HTTP roundtrip."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing]]))


(def ^:private auth-required-parents
  "Parents from `app.routes.auth` that bake in
   `auth-required-middleware`. Concrete routes that inherit from any
   of these are auth-protected by construction."
  #{:post :get-auth-required :put})


(defn- read-fns-edn
  [resource-path]
  (-> (io/resource resource-path)
      slurp
      edn/read-string))


(defn- fn-defs
  [edn-content]
  ;; Both legacy (vector) and `{:namespace … :fns […]}` shapes.
  (if (vector? edn-content)
    edn-content
    (:fns edn-content)))


(defn- fn-by-name
  [defs nm]
  (some #(when (= nm (:name %)) %) defs))


(def ^:private routes-edn
  (read-fns-edn "packages/app/routes/fns.edn"))


(def ^:private routes-defs (fn-defs routes-edn))


(def ^:private guarded-routes
  "Routes that MUST stay behind auth. Update when adding a new
   admin-mutable endpoint; reading this list and walking each through
   the lint catches accidental re-parenting at CI time."
  [:api-execute
   :api-execution-by-id
   :api-execution-cancel
   :api-executions-list
   :api-services-reconcile
   :api-services-list])


(deftest api-execute-routes-are-auth-required
  (testing "every execution + services route is wired to an auth-required parent"
    (doseq [route-name guarded-routes]
      (let [route (fn-by-name routes-defs route-name)]
        (is (some? route)
            (str route-name " route is declared in app/routes/fns.edn"))
        (is (contains? auth-required-parents (:parent route))
            (str route-name " is :parent "
                 (pr-str (:parent route))
                 " — expected one of "
                 (pr-str auth-required-parents)
                 ". A non-auth parent (`:get-route` / `:post-route`) "
                 "would expose this endpoint without bearer-token check."))))))


(deftest api-execute-routes-carry-path-and-handler
  (testing "each route binds :path and :handler as the route templates require"
    (doseq [route-name guarded-routes]
      (let [route (fn-by-name routes-defs route-name)
            args (:args route)]
        (is (string? (:path args))
            (str route-name " has a string :path"))
        (is (keyword? (:handler args))
            (str route-name " has a keyword :handler ref"))))))


(deftest auth-check-route-still-auth-required
  ;; Sanity check that the canonical example (`/api/auth/check`) we
  ;; modelled the executions routes after hasn't itself drifted —
  ;; protects against the whole vocabulary shifting underneath this
  ;; test.
  (let [route (fn-by-name routes-defs :auth-check)]
    (is (some? route))
    (is (contains? auth-required-parents (:parent route)))))
