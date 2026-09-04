(ns ^:integration graphden.packages.app.mcp-endpoint-test
  "End-to-end tests of the MCP server (app/mcp — ROADMAP Block 9.1):
   the JSON-RPC dispatch, the Phase-A tool set (list-namespaces /
   search-fns / read-fn / execute-fn) and the ai-context resource,
   driven through `:_mcp-dispatch` exactly as POST /mcp would."
  (:require
    [cheshire.core :as json]
    [clojure.edn :as edn]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.test-infra.golden-app :as ga]))


;; mcp is its own package now (extracted from `app`, installed via the
;; route-collection seam), so `app` no longer pulls it — bootstrap the golden
;; WITH "mcp" so `:_mcp-dispatch` + its ref closure are present.
(use-fixtures :once (ga/fixture (ns-name *ns*) ["core" "web" "app" "registry" "mcp"]))


(defn- rpc!
  "POST one JSON-RPC message through the dispatch; returns
   {:status … :rpc <decoded body>}."
  [msg]
  (let [resp (ga/exec-handler :_mcp-dispatch
                              {:headers {"content-type" "application/json"}
                               :body (json/generate-string msg)})]
    {:status (:status resp)
     :rpc (when (seq (:body resp))
            (json/parse-string (:body resp) true))}))


(defn- tool-text
  "The first text content item of a tools/call result, JSON-decoded."
  [rpc]
  (-> rpc :result :content first :text (json/parse-string true)))


(deftest initialize-handshake
  (let [{:keys [status rpc]} (rpc! {:jsonrpc "2.0" :id 1 :method "initialize"
                                    :params {:protocolVersion "2025-03-26"}})]
    (is (= 200 status))
    (is (= 1 (:id rpc)) "id echoed")
    (is (= "2025-03-26" (get-in rpc [:result :protocolVersion])))
    (is (= "graphden" (get-in rpc [:result :serverInfo :name])))
    (is (map? (get-in rpc [:result :capabilities :tools])))))


(deftest initialized-notification-gets-202
  (let [{:keys [status rpc]} (rpc! {:jsonrpc "2.0" :method "notifications/initialized"})]
    (is (= 202 status))
    (is (nil? rpc) "no body on a notification reply")))


(deftest unknown-method-is-32601
  (let [{:keys [rpc]} (rpc! {:jsonrpc "2.0" :id 7 :method "no/such"})]
    (is (= -32601 (get-in rpc [:error :code])))
    (is (= 7 (:id rpc)))))


(deftest tools-list-catalog
  (let [{:keys [rpc]} (rpc! {:jsonrpc "2.0" :id 2 :method "tools/list"})]
    (is (= #{"list-namespaces" "search-fns" "read-fn" "describe-fn" "execute-fn"
             "create-branch" "upsert-fn-defs" "run-tests" "list-branches"
             "diff-branch"}
           (into #{} (map :name) (get-in rpc [:result :tools]))))
    (testing "every tool carries an object inputSchema"
      (doseq [t (get-in rpc [:result :tools])]
        (is (= "object" (get-in t [:inputSchema :type])))))))


(deftest tool-list-namespaces
  (let [{:keys [rpc]} (rpc! {:jsonrpc "2.0" :id 3 :method "tools/call"
                             :params {:name "list-namespaces" :arguments {}}})]
    (is (false? (get-in rpc [:result :isError])))
    (let [data (tool-text rpc)]
      (is (seq (:namespaces data)) "tree scope returns namespaces"))))


(deftest tool-search-fns-by-description
  (testing "a concept phrase that appears only in a description still finds the fn"
    (let [{:keys [rpc]} (rpc! {:jsonrpc "2.0" :id 14 :method "tools/call"
                               :params {:name "search-fns" :arguments {:q "pooled Postgres datasource"}}})
          names (set (map :name (:fns (tool-text rpc))))]
      (is (contains? names "postgres-storage-impl")))))


(deftest tool-search-fns
  (let [{:keys [rpc]} (rpc! {:jsonrpc "2.0" :id 4 :method "tools/call"
                             :params {:name "search-fns" :arguments {:q "web-server"}}})
        data (tool-text rpc)]
    (is (some #(= "web-server" (:name %)) (:fns data)))))


(deftest tool-read-fn
  (testing "known fn → fns.edn EDN text of its subtree, the fn itself included"
    (let [{:keys [rpc]} (rpc! {:jsonrpc "2.0" :id 5 :method "tools/call"
                               :params {:name "read-fn" :arguments {:name "web-server"}}})
          text (-> rpc :result :content first :text)
          defs (edn/read-string text)]
      (is (vector? defs))
      (is (some #(= :web-server (:name %)) defs) "the root fn-def is in the bundle")
      (is (some #(= :http-server (:name %)) defs) "its parent base-fn rides along")
      (is (every? #(and (map? %) (keyword? (:name %))) defs) "every entry is a fn-def map")
      (is (re-find #"\n" text) "pretty-printed — one def per lines, not one line")))
  (testing "format rows → the raw subtree rows, JSON"
    (let [{:keys [rpc]} (rpc! {:jsonrpc "2.0" :id 15 :method "tools/call"
                               :params {:name "read-fn" :arguments {:name "web-server" :format "rows"}}})
          data (tool-text rpc)]
      (is (some #(= "web-server" (:name %)) (:fns data)))))
  (testing "an unknown branch name is a -32602, router or not"
    (let [{:keys [rpc]} (rpc! {:jsonrpc "2.0" :id 17 :method "tools/call"
                               :params {:name "read-fn" :arguments {:name "web-server" :branch "no/such-branch"}}})]
      (is (= -32602 (get-in rpc [:error :code])))
      (is (re-find #"no/such-branch" (get-in rpc [:error :message])))))
  (testing "a branch argument with no router installed is ignored — same answer"
    (let [{:keys [rpc]} (rpc! {:jsonrpc "2.0" :id 16 :method "tools/call"
                               :params {:name "read-fn" :arguments {:name "web-server" :branch "main" :format "rows"}}})
          data (tool-text rpc)]
      (is (some #(= "web-server" (:name %)) (:fns data)))))
  (testing "unknown fn → -32602 with the name in the message"
    (let [{:keys [rpc]} (rpc! {:jsonrpc "2.0" :id 6 :method "tools/call"
                               :params {:name "read-fn" :arguments {:name "no-such-fn-xyz"}}})]
      (is (= -32602 (get-in rpc [:error :code])))
      (is (re-find #"no-such-fn-xyz" (get-in rpc [:error :message]))))))


(deftest tool-describe-fn
  (testing "a listener: handler-time free args are not start-blocking; :process makes it service-eligible"
    (let [{:keys [rpc]} (rpc! {:jsonrpc "2.0" :id 50 :method "tools/call"
                               :params {:name "describe-fn" :arguments {:name "web-server"}}})
          data (tool-text rpc)]
      (is (= "web-server" (:name data)))
      (is (string? (:id data)))
      (is (empty? (:start-blocking-free-args data)))
      (is (some #{"process"} (:effects data)))
      (is (true? (:service-eligible? data)))))
  (testing "a pure base-fn: its slot is a free arg, no effects, not service-eligible"
    (let [{:keys [rpc]} (rpc! {:jsonrpc "2.0" :id 51 :method "tools/call"
                               :params {:name "describe-fn" :arguments {:name "add"}}})
          data (tool-text rpc)]
      (is (= ["nums"] (:free-args data)))
      (is (= ["nums"] (:start-blocking-free-args data)))
      (is (empty? (:effects data)))
      (is (contains? (:arg-types data) :nums))
      (is (false? (:service-eligible? data)))
      (is (= [] (:unread-bindings data)) "nothing bound, nothing unread")))
  (testing "unknown fn → -32602 with the name in the message"
    (let [{:keys [rpc]} (rpc! {:jsonrpc "2.0" :id 52 :method "tools/call"
                               :params {:name "describe-fn" :arguments {:name "no-such-fn-xyz"}}})]
      (is (= -32602 (get-in rpc [:error :code])))
      (is (re-find #"no-such-fn-xyz" (get-in rpc [:error :message]))))))


(deftest tool-execute-fn
  (let [{:keys [rpc]} (rpc! {:jsonrpc "2.0" :id 8 :method "tools/call"
                             :params {:name "execute-fn"
                                      :arguments {:name "add" :args {:nums [1 2 3]}}}})
        data (tool-text rpc)]
    (is (= "succeeded" (:status data)))
    (is (= 6 (:result data)))))


(deftest tool-execute-fn-trace
  (testing "trace true → the run is persisted and answers with its call tree"
    (let [{:keys [rpc]} (rpc! {:jsonrpc "2.0" :id 18 :method "tools/call"
                               :params {:name "execute-fn"
                                        :arguments {:name "current-branch-chain" :args {} :trace true}}})
          data (tool-text rpc)]
      (is (= "succeeded" (:status data)))
      (is (string? (:execution-id data)) "a traced run leaves an execution row")
      (is (map? (:trace data)))
      (is (true? (get-in data [:trace :found?])) "the call tree was captured")
      (is (every? #(string? (:fn-name %)) (get-in data [:trace :rows])) "rows carry joined fn names")
      (is (not (contains? data :path-trace)) "the raw id-keyed path-trace is replaced by the joined tree")))
  (testing "without trace the answer is as before — no trace key, no row"
    (let [{:keys [rpc]} (rpc! {:jsonrpc "2.0" :id 19 :method "tools/call"
                               :params {:name "execute-fn" :arguments {:name "add" :args {:nums [1 2]}}}})
          data (tool-text rpc)]
      (is (= 3 (:result data)))
      (is (not (contains? data :trace))))))


(deftest tool-unknown-name
  (let [{:keys [rpc]} (rpc! {:jsonrpc "2.0" :id 9 :method "tools/call"
                             :params {:name "rm-rf" :arguments {}}})]
    (is (= -32602 (get-in rpc [:error :code])))))


(deftest resources-list-and-read
  (let [{:keys [rpc]} (rpc! {:jsonrpc "2.0" :id 10 :method "resources/list"})]
    (is (= "graphden://ai-context"
           (-> rpc :result :resources first :uri))))
  (let [{:keys [rpc]} (rpc! {:jsonrpc "2.0" :id 11 :method "resources/read"
                             :params {:uri "graphden://ai-context"}})
        text (-> rpc :result :contents first :text)]
    (is (re-find #"Code is a graph in a database" text) "serves the real AI_CONTEXT doc")
    (is (re-find #"create-branch" text) "documents the mutation workflow"))
  (let [{:keys [rpc]} (rpc! {:jsonrpc "2.0" :id 12 :method "resources/read"
                             :params {:uri "graphden://nope"}})]
    (is (= -32002 (get-in rpc [:error :code])))))


;; ---------------------------------------------------------------------------
;; Phase B — the mutation cycle the launch story rests on:
;; branch → propose fn-defs → execute them → read the diff a human reviews.
;; ---------------------------------------------------------------------------

(defn- call-tool!
  [tool-name args]
  (:rpc (rpc! {:jsonrpc "2.0" :id 99 :method "tools/call"
               :params {:name tool-name :arguments args}})))


(deftest mutation-cycle
  (let [branch (str "ai/mcp-test-" (subs (str (random-uuid)) 0 8))]
    (testing "create-branch"
      (let [data (tool-text (call-tool! "create-branch" {:name branch}))]
        (is (true? (:ok data)) (str "branch created: " (pr-str data)))))

    (testing "upsert-fn-defs writes into THAT branch"
      (let [data (tool-text (call-tool! "upsert-fn-defs"
                                        {:branch branch
                                         :fn-defs "[{:name :mcp-test-sum :parent :add :args {:nums [40 2]}}]"}))]
        (is (true? (:ok data)))
        (is (= branch (:branch data)))
        (is (= 1 (count (:fn-ids data))))))

    (testing "the new fn is absent from main — the AI never touches it"
      (let [data (tool-text (call-tool! "search-fns" {:q "mcp-test-sum"}))]
        (is (not-any? #(= "mcp-test-sum" (:name %)) (:fns data))
            "main stays clean")))

    (testing "diff-branch shows the proposal the human reviews"
      (let [data (tool-text (call-tool! "diff-branch" {:branch branch}))]
        (is (map? data))
        (is (true? (:ok data)))
        (is (sequential? (:diffs data)) "diff carries the per-entity change list")
        (is (some (fn [d]
                    (and (= "fn" (:entity-name d))
                         (= "added-in-target" (:change d))
                         (= "mcp-test-sum" (get-in d [:target-version :name]))))
                  (:diffs data))
            (str "the proposed fn shows up as added-in-target: " (pr-str (:diffs data))))))))


(deftest mutation-guards
  (testing "unknown branch → -32602 naming create-branch"
    (let [rpc (call-tool! "upsert-fn-defs" {:branch "ai/does-not-exist"
                                            :fn-defs "[{:name :x :parent :add}]"})]
      (is (= -32602 (get-in rpc [:error :code])))
      (is (re-find #"create-branch" (get-in rpc [:error :message])))))

  (testing "malformed EDN → -32602, not a 500"
    (let [branch (str "ai/mcp-guard-" (subs (str (random-uuid)) 0 8))
          _ (call-tool! "create-branch" {:name branch})
          rpc (call-tool! "upsert-fn-defs" {:branch branch :fn-defs "{not a vector"})]
      (is (= -32602 (get-in rpc [:error :code])))))

  (testing "a fn-def without :name → -32602"
    (let [branch (str "ai/mcp-guard2-" (subs (str (random-uuid)) 0 8))
          _ (call-tool! "create-branch" {:name branch})
          rpc (call-tool! "upsert-fn-defs" {:branch branch :fn-defs "[{:parent :add}]"})]
      (is (= -32602 (get-in rpc [:error :code])))))

  (testing "tools/list advertises the mutation + verification tools"
    (let [{:keys [rpc]} (rpc! {:jsonrpc "2.0" :id 100 :method "tools/list"})]
      (is (= #{"list-namespaces" "search-fns" "read-fn" "describe-fn" "execute-fn"
               "create-branch" "upsert-fn-defs" "run-tests" "list-branches"
               "diff-branch"}
             (into #{} (map :name) (get-in rpc [:result :tools])))))))


(deftest mutation-platform-guard
  ;; The editor API refuses writes against package-synced fns
  ;; (crud.package-guard); the MCP sync path enforces the same protection —
  ;; a platform fn-def in the bundle is a -32602 unless the model passes
  ;; allow-platform-overwrite explicitly.
  (let [branch (str "ai/mcp-plat-" (subs (str (random-uuid)) 0 8))]
    (call-tool! "create-branch" {:name branch})
    (testing "a platform fn-def (core.arithmetic/add) is refused without the flag"
      ;; The guard is identity-targeted: the deterministic (namespace, name)
      ;; id must equal a package-synced row's. A bare `:add` with no
      ;; namespace is a DIFFERENT identity (a new root-ns fn) and passes —
      ;; same rule the editor's package-guard applies.
      (let [rpc (call-tool! "upsert-fn-defs"
                            {:branch branch
                             :fn-defs "[{:name :add :namespace \"core.arithmetic\" :parent :const :args {:value 1}}]"})
            msg (get-in rpc [:error :message] "")]
        (is (= -32602 (get-in rpc [:error :code])))
        (is (re-find #"platform-owned" msg))
        (is (re-find #"add" msg))
        (is (re-find #"allow-platform-overwrite" msg) "the message names the escape hatch")))
    (testing "the flag lets a deliberate bundle through (plumbing check on a harmless def)"
      (let [data (tool-text (call-tool! "upsert-fn-defs"
                                        {:branch branch
                                         :allow-platform-overwrite true
                                         :fn-defs "[{:name :mcp-plat-own :parent :add :args {:nums [1 2]}}]"}))]
        (is (true? (:ok data)) (pr-str data))))))


(deftest tool-run-tests-and-list-branches
  (testing "run-tests runs the branch's (empty) test set through the real runner"
    (let [data (tool-text (call-tool! "run-tests" {}))]
      (is (contains? data :total) (pr-str data))
      (is (int? (:total data)))
      (is (int? (:passed data)))
      (is (int? (:failed data)))))
  (testing "list-branches returns the /api/branches shape"
    (let [data (tool-text (call-tool! "list-branches" {}))]
      (is (true? (:ok data)))
      (is (pos? (:count data)) "at least main exists")
      (is (some #(= "main" (:name %)) (:branches data))))))
