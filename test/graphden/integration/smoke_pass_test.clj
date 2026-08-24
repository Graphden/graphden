(ns ^:integration graphden.integration.smoke-pass-test
  "End-to-end smoke pass — drives all the manual browser scenarios
   through the Ring handler chain so the same flow regresses
   automatically.

   Runs in the PARALLEL pool (deliberately un-pinned in `7ef9d307`
   \"parallelise smoke-pass-test\" — this prose used to claim
   `^:serial`). The staged scenarios still need in-order execution,
   which one `smoke-pass-test` deftest with ordered `testing` blocks
   already guarantees (kaocha parallelises at NS granularity, never
   inside a deftest); the router/global mutations it makes are
   per-thread via the parallel plugin's isolation-vars.

   Test surface uses the same `br/dispatch` path http-kit feeds —
   any wrap / env-binding / closure-capture regression that breaks
   a real request will also break the corresponding `testing` block.
   Bootstrap is shared via `:once` fixture so the heavy cost is
   paid one time per JVM."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.auth.provider :as auth]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router :as br]
    [graphden.test-infra.shared-bootstrap :as sb]))


(def ^:dynamic *router* nil)
(def ^:dynamic *storage* nil)


(def ^:private test-auth-token "smoke-pass-token")


(use-fixtures :once
  (setup/create-container-fixture)
  exec/with-isolated-rich-types
  ;; inline heal — this ns merges; the post-commit RAW thread's heal
  ;; otherwise races into a CCE (see setup/inline-heal-fixture docstring).
  setup/inline-heal-fixture
  (fn [t]
    (exec/with-clean-registry
      #(let [storage (setup/create-versioned-test-storage 6)
             _ (sb/bootstrap-with-cached-sweep! storage ["core" "web" "app"])
             ctx (exec/create-context
                   {:storage storage
                    :auth-provider (auth/single-token-provider test-auth-token)})
             _ (cr/rebuild! ctx)
             router (br/create-router ctx "_app-ring-response")]
         (br/set-active-router! router)
         (try
           (binding [*router* router
                     *storage* storage]
             (t))
           (finally
             (br/clear-active-router!)
             (sp/close storage)))))))


(defn- ring-call
  ([method path] (ring-call method path nil nil))
  ([method path body] (ring-call method path body nil))
  ([method path body branch]
   (br/dispatch
     *router*
     (cond-> {:request-method method
              :uri path
              :headers (cond-> {"content-type" "application/json"
                                "authorization" (str "Bearer " test-auth-token)}
                         branch (assoc "x-graphden-branch" branch))
              :query-string nil}
       body (assoc :body (json/generate-string body))))))


(defn- form-call
  ([method path form] (form-call method path form nil))
  ([method path form branch]
   (br/dispatch
     *router*
     (cond-> {:request-method method
              :uri path
              :headers (cond-> {"content-type" "application/x-www-form-urlencoded"
                                "authorization" (str "Bearer " test-auth-token)}
                         branch (assoc "x-graphden-branch" branch))
              :query-string nil}
       form (assoc :body
                   (str/join "&"
                             (map (fn [[k v]]
                                    (str (name k) "="
                                         (java.net.URLEncoder/encode
                                           ^String (str v) "UTF-8")))
                                  form)))))))


(defn- json-body
  [resp]
  (let [b (:body resp)]
    (when (string? b) (json/parse-string b true))))


(defn- entities
  []
  (json-body (ring-call :get "/api/graph/entities")))


(defn- fn-named
  [nm]
  (when-let [d (entities)]
    (first (filter #(= (str nm) (:name %)) (:fns d)))))


(defn- slot-by-fn-and-name
  [d fn-name slot-name]
  (let [fns-by-id (into {} (map (juxt :id identity)) (:fns d))
        slots-by-id (into {} (map (juxt :id identity)) (:slots d))]
    (some (fn [fs]
            (let [f (get fns-by-id (:fn-id fs))
                  s (get slots-by-id (:slot-id fs))]
              (when (and (= (:name f) fn-name) (= (:name s) slot-name))
                s)))
          (:fn-slots d))))


(deftest smoke-pass-test
  ;; The complete smoke pass through one deftest so the steps run
  ;; in source order without racing kaocha's parallel runner.
  (testing "[0] Built-in :add reachable + executes correctly"
    (let [resp (ring-call :get "/api/graph/entities")]
      (is (= 200 (:status resp)))
      (is (some? (fn-named "add"))))
    (let [resp (ring-call :post "/api/execute"
                          {:fn-name "add" :args {:nums [1 2 3]}})
          body (json-body resp)]
      (is (= "succeeded" (:status body)))
      (is (= 6 (:result body)))))

  (testing "[1] Create namespace via /api/entities/ns"
    (let [resp (form-call :post "/api/entities/ns" [[:name "smoke"]])]
      (is (= 200 (:status resp))
          (str "namespace create response: " (pr-str resp))))
    (let [ns-row (some #(when (= "smoke" (:name %)) %)
                       (:namespaces (entities)))]
      (is (some? ns-row))))

  (testing "[2] Create composed fn add-42 (parent :add) + bind :nums = [42]"
    (let [smoke-ns (->> (entities) :namespaces
                        (some #(when (= "smoke" (:name %)) (:id %))))
          add-id (:id (fn-named "add"))]
      (form-call :post "/api/entities/fn"
                 [[:name "add-42"]
                  [:namespace-id smoke-ns]
                  [:parent-ids add-id]])
      (let [nums-slot (slot-by-fn-and-name (entities) "add" "nums")
            add-42 (fn-named "add-42")]
        (form-call :post "/api/entities/binding"
                   [[:fn-id (:id add-42)]
                    [:slot-id (:id nums-slot)]
                    [:list-append "true"]])
        (let [binding-id (some #(when (and (= (:id add-42) (:fn-id %))
                                           (= (:id nums-slot) (:slot-id %)))
                                  (:id %))
                               (:bindings (entities)))]
          (form-call :post "/api/entities/binding-list-item"
                     [[:binding-id binding-id]
                      [:position 0]
                      [:value "42"]])))
      (let [resp (ring-call :post "/api/execute"
                            {:fn-name "add-42" :args {}})
            body (json-body resp)]
        (is (= "succeeded" (:status body)))
        (is (= 42 (:result body))))))

  (testing "[3] Create refinement type-row positive-test"
    (let [smoke-ns (->> (entities) :namespaces
                        (some #(when (= "smoke" (:name %)) (:id %))))
          int-fn (:id (fn-named "int"))
          resp (form-call :post "/api/entities/fn"
                          [[:name "positive-test"]
                           [:namespace-id smoke-ns]
                           [:base-fn-id int-fn]
                           [:constraint "[\">\", 0]"]])]
      (is (= 200 (:status resp))
          (str "type-row create: " (pr-str resp))))
    (let [type-row (fn-named "positive-test")]
      (is (some? type-row))
      (is (= "refinement" (:role type-row)))
      (is (= [">" 0] (:constraint type-row)))))

  (testing "[4] /api/executions returns 200 (synth-parsed recursion regression)"
    (let [add-id (:id (fn-named "add"))
          resp (br/dispatch
                 *router*
                 {:request-method :get
                  :uri "/api/executions"
                  :query-string (str "fn-id=" add-id)
                  :headers {"authorization" (str "Bearer " test-auth-token)}})
          body (json-body resp)]
      (is (= 200 (:status resp))
          (str "expected 200, got " (:status resp) " body: " (:body resp)))
      (is (true? (:ok body)))
      (is (vector? (:executions body)))))

  (testing "[5] Branch lifecycle — create, diverge, merge with cache invalidate"
    (let [add-42 (fn-named "add-42")
          binding (some #(when (= (:id add-42) (:fn-id %)) %)
                        (:bindings (entities)))
          item-id (some #(when (= (:id binding) (:binding-id %)) (:id %))
                        (:list-items (entities)))]
      (is (some? item-id))
      ;; Create feature branch
      (let [resp (ring-call :post "/api/branches"
                            {:name "smoke-feature"})]
        (is (true? (:ok (json-body resp)))))
      ;; Update list-item value on feature
      (form-call :put (str "/api/entities/binding-list-item/" item-id)
                 [[:value "999"]]
                 "smoke-feature")
      ;; Verify divergence
      (let [main-resp (ring-call :post "/api/execute"
                                 {:fn-name "add-42" :args {}})
            feat-resp (ring-call :post "/api/execute"
                                 {:fn-name "add-42" :args {}}
                                 "smoke-feature")]
        (is (= 42 (:result (json-body main-resp))))
        (is (= 999 (:result (json-body feat-resp)))))
      ;; Merge feature → main, verify cache invalidate
      (let [resp (ring-call :post "/api/branches/main/merge"
                            {:source "smoke-feature"})]
        (is (true? (:ok (json-body resp)))
            (str "merge response: " (:body resp))))
      (let [resp (ring-call :post "/api/execute"
                            {:fn-name "add-42" :args {}})
            body (json-body resp)]
        (is (= 999 (:result body))
            "main must see feature's overlay after merge"))))

  (testing "[6] List-closed enforcement on descendant append"
    (let [add-42 (fn-named "add-42")
          smoke-ns (->> (entities) :namespaces
                        (some #(when (= "smoke" (:name %)) (:id %))))
          binding (some #(when (= (:id add-42) (:fn-id %)) %)
                        (:bindings (entities)))
          nums-slot (slot-by-fn-and-name (entities) "add" "nums")]
      ;; Close the ancestor's list
      (form-call :put (str "/api/entities/binding/" (:id binding))
                 [[:list-closed "true"]])
      ;; Create child fn parented from add-42
      (form-call :post "/api/entities/fn"
                 [[:name "add-42-child"]
                  [:namespace-id smoke-ns]
                  [:parent-ids (:id add-42)]])
      ;; Child cannot append to closed list
      (let [child (fn-named "add-42-child")
            resp (form-call :post "/api/entities/binding"
                            [[:fn-id (:id child)]
                             [:slot-id (:id nums-slot)]
                             [:list-append "true"]])]
        (is (= 400 (:status resp))
            "list-closed must reject descendant append")
        (is (str/includes? (str (:body resp)) "list-closed")))
      ;; Re-open releases the seal
      (form-call :put (str "/api/entities/binding/" (:id binding))
                 [[:list-closed "false"]])
      ;; Ancestor can extend re-opened list
      (let [resp (form-call :post "/api/entities/binding-list-item"
                            [[:binding-id (:id binding)]
                             [:position 1]
                             [:value "8"]])]
        (is (= 200 (:status resp))))
      ;; Execution reflects appended item: 999 + 8 = 1007
      (let [resp (ring-call :post "/api/execute"
                            {:fn-name "add-42" :args {}})
            body (json-body resp)]
        (is (= 1007 (:result body))))))

  (testing "[7] Branch-local fn never propagates across merges"
    ;; The seeded :http-server base-fn is marked :branch-local? true in
    ;; resources/packages/web/http/fns.edn — any descendant inherits
    ;; the flag via the monotonic-OR walker in
    ;; graphden.versioning.branch-local. We exercise the END-TO-END
    ;; path: create a feat-branch fn whose ancestor is :http-server,
    ;; merge feat → main, and assert the fn is NOT visible on main
    ;; afterwards (no own version + branch-local filter drops the
    ;; merge candidate). Compare against [5] above which proves that
    ;; non-branch-local edits DO propagate on the same merge.
    (let [smoke-ns (->> (entities) :namespaces
                        (some #(when (= "smoke" (:name %)) (:id %))))
          http-fn (fn-named "http-server")
          http-server-id (:id http-fn)]
      (is (some? http-server-id)
          "the :http-server base-fn must be loaded for the seed flag to take effect")
      (is (true? (:branch-local? http-fn))
          (str ":http-server must carry :branch-local? true on its identity row. Got: "
               (pr-str http-fn)))
      ;; New branch from main as the staging ground.
      (let [resp (ring-call :post "/api/branches" {:name "smoke-local-feat"})]
        (is (true? (:ok (json-body resp)))))
      ;; Create the fn ON feat (X-Graphden-Branch header).
      (let [resp (form-call :post "/api/entities/fn"
                            [[:name "smoke-local-server"]
                             [:namespace-id smoke-ns]
                             [:parent-ids http-server-id]]
                            "smoke-local-feat")]
        (is (= 200 (:status resp))
            (str "branch-local fn create: " (pr-str resp))))
      ;; Sanity: visible on feat.
      (let [resp (br/dispatch
                   *router*
                   {:request-method :get
                    :uri "/api/graph/entities"
                    :query-string nil
                    :headers {"authorization" (str "Bearer " test-auth-token)
                              "x-graphden-branch" "smoke-local-feat"}})
            fns (:fns (json-body resp))
            found (some #(when (= "smoke-local-server" (:name %)) %) fns)]
        (is (some? found)
            "feat-branch must see its own branch-local fn"))
      ;; Merge feat → main. Branch-local filter should drop the
      ;; smoke-local-server version row on main resolution even though
      ;; the branch-merge pointer is created. Audit log surfaces the
      ;; skipped fn in the response so API consumers can see what
      ;; didn't propagate.
      (let [resp (ring-call :post "/api/branches/main/merge"
                            {:source "smoke-local-feat"})
            body (json-body resp)]
        (is (true? (:ok body))
            (str "merge response: " (:body resp)))
        (let [skipped (get-in body [:skipped :branch-local])
              skipped-names (into #{} (map :fn-name) skipped)]
          (is (contains? skipped-names "smoke-local-server")
              (str "skipped audit lists smoke-local-server: "
                   (pr-str skipped)))))
      ;; Main must NOT see smoke-local-server post-merge.
      (let [fns (:fns (entities))
            found (some #(when (= "smoke-local-server" (:name %)) %) fns)]
        (is (nil? found)
            "branch-local fn must NOT propagate to main on merge")))))
