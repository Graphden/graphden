(ns ^:integration graphden.packages.app.mcp-endpoint-test
  "End-to-end tests of the MCP server (app/mcp — ROADMAP Block 9.1):
   the JSON-RPC dispatch, the Phase-A tool set (list-namespaces /
   search-fns / read-fn / execute-fn) and the ai-context resource,
   driven through `:_mcp-dispatch` exactly as POST /mcp would."
  (:require
    [cheshire.core :as json]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.test-infra.golden-app :as ga]))


(use-fixtures :once (ga/fixture (ns-name *ns*)))


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
    (is (= #{"list-namespaces" "search-fns" "read-fn" "execute-fn"}
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


(deftest tool-search-fns
  (let [{:keys [rpc]} (rpc! {:jsonrpc "2.0" :id 4 :method "tools/call"
                             :params {:name "search-fns" :arguments {:q "web-server"}}})
        data (tool-text rpc)]
    (is (some #(= "web-server" (:name %)) (:fns data)))))


(deftest tool-read-fn
  (testing "known fn → subtree with the fn itself"
    (let [{:keys [rpc]} (rpc! {:jsonrpc "2.0" :id 5 :method "tools/call"
                               :params {:name "read-fn" :arguments {:name "web-server"}}})
          data (tool-text rpc)]
      (is (some #(= "web-server" (:name %)) (:fns data)))))
  (testing "unknown fn → -32602 with the name in the message"
    (let [{:keys [rpc]} (rpc! {:jsonrpc "2.0" :id 6 :method "tools/call"
                               :params {:name "read-fn" :arguments {:name "no-such-fn-xyz"}}})]
      (is (= -32602 (get-in rpc [:error :code])))
      (is (re-find #"no-such-fn-xyz" (get-in rpc [:error :message]))))))


(deftest tool-execute-fn
  (let [{:keys [rpc]} (rpc! {:jsonrpc "2.0" :id 8 :method "tools/call"
                             :params {:name "execute-fn"
                                      :arguments {:name "add" :args {:nums [1 2 3]}}}})
        data (tool-text rpc)]
    (is (= "succeeded" (:status data)))
    (is (= 6 (:result data)))))


(deftest tool-unknown-name
  (let [{:keys [rpc]} (rpc! {:jsonrpc "2.0" :id 9 :method "tools/call"
                             :params {:name "rm-rf" :arguments {}}})]
    (is (= -32602 (get-in rpc [:error :code])))))


(deftest resources-list-and-read
  (let [{:keys [rpc]} (rpc! {:jsonrpc "2.0" :id 10 :method "resources/list"})]
    (is (= "graphden://ai-context"
           (-> rpc :result :resources first :uri))))
  (let [{:keys [rpc]} (rpc! {:jsonrpc "2.0" :id 11 :method "resources/read"
                             :params {:uri "graphden://ai-context"}})]
    (is (re-find #"fn-defs" (-> rpc :result :contents first :text))))
  (let [{:keys [rpc]} (rpc! {:jsonrpc "2.0" :id 12 :method "resources/read"
                             :params {:uri "graphden://nope"}})]
    (is (= -32002 (get-in rpc [:error :code])))))
