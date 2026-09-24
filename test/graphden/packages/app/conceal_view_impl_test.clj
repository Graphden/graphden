(ns ^:integration ^:serial graphden.packages.app.conceal-view-impl-test
  "Execution traces and Explorer views honour the view-impl seam the
   tenancy addon installs (`crud.entities/view-impl-filter`,
   docs/TENANCY_SEAM.md): a viewer who may not see a fn's composition
   learns it neither from the call tree of a run (the fn's frame is a
   leaf, nothing beneath it ships) nor from a view (no `uses` match
   THROUGH it, no `:parent-ids` on its row).

   `^:serial` — the seam is a process-global atom; a stub filter installed
   here would conceal fns from every suite running beside it."
  (:require
    [cheshire.core :as json]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.crud.entities :as entities]
    [graphden.crud.fn-execution :as fn-exec]
    [graphden.crud.fn-execution.conceal :as conceal]
    [graphden.test-infra.golden-app :as ga]))


(use-fixtures :once (ga/fixture (ns-name *ns*) ["core" "web" "app" "registry" "mcp"]))


(defn- hide-where
  "A stub view-impl filter shaped like the tenancy addon's: conceal the
   composition of every fn in the dump matching `hidden?`."
  [hidden?]
  (fn [graph]
    (entities/strip-impl-of graph (into #{} (comp (filter hidden?) (map :id)) (:fns graph)))))


(defn- hide-named
  [fn-name]
  (hide-where #(= fn-name (:name %))))


(defn- with-filter*
  [f body]
  (reset! entities/view-impl-filter f)
  (try (body) (finally (reset! entities/view-impl-filter nil))))


(defn- ctx
  []
  (assoc (:ctx ga/*bootstrap*) :storage (:storage ga/*bootstrap*)))


(defn- traced-run!
  "Execute `fn-name` traced + persisted through the MCP `execute-fn` tool
   (the shared apply pipeline). Returns the tool's answer map."
  [fn-name]
  (let [resp (ga/exec-handler :_mcp-dispatch
                              {:headers {"content-type" "application/json"}
                               :body (json/generate-string
                                       {:jsonrpc "2.0" :id 1 :method "tools/call"
                                        :params {:name "execute-fn"
                                                 :arguments {:name fn-name :args {} :trace true}}})})]
    (-> (:body resp) (json/parse-string true) :result :content first :text
        (json/parse-string true))))


(defn- stored-entries
  [execution-id]
  (:entries (:path-trace (fn-exec/get-execution (ctx) (parse-uuid execution-id)))))


(def ^:private traced-fn
  "A fn with a multi-frame call tree on the golden graph."
  "current-branch-chain")


(deftest a-hidden-root-traces-as-one-leaf
  (let [control (traced-run! traced-fn)]
    (testing "control — unfiltered, the tree shows the frames inside the fn"
      (is (< 1 (count (get-in control [:trace :rows]))))
      (is (< 1 (count (stored-entries (:execution-id control)))))))
  (with-filter* (hide-named traced-fn)
    (fn []
      (let [data (traced-run! traced-fn)
            rows (get-in data [:trace :rows])]
        (is (= "succeeded" (:status data)) "concealment never changes the run")
        (is (= [traced-fn] (mapv :fn-name rows))
            "MCP trace rows: the concealed fn is the only frame")
        (let [entries (stored-entries (:execution-id data))]
          (is (= 1 (count entries)) "GET /api/execute/:id serves the leaf only")
          (is (true? (:concealed? (first entries))))
          (is (zero? (:seq (first entries))) "renumbered — no gap counts hidden frames"))))))


(deftest execution-history-serves-concealed-traces
  (with-filter* (hide-named traced-fn)
    (fn []
      (let [run (traced-run! traced-fn)
            resp (ga/exec-handler :list-executions-handler
                                  {:request-method :get
                                   :query-string (str "fn-id=" (ga/fn-id (keyword traced-fn)))})
            rows (:executions (json/parse-string (:body resp) true))
            row (first (filter #(= (:execution-id run) (:id %)) rows))]
        (is (some? row) "the traced run is in the history")
        (is (= 1 (count (get-in row [:path-trace :entries])))
            "GET /api/executions ships the concealed trace, not the raw row's")))))


(deftest a-hidden-inner-fn-collapses-to-a-leaf
  (let [control (get-in (traced-run! traced-fn) [:trace :rows])
        ;; an inner frame that has frames of its own beneath it
        [inner] (keep (fn [[a b]] (when (and (pos? (:depth a)) (> (:depth b) (:depth a))) a))
                      (partition 2 1 control))]
    (is (some? inner) "the golden fn has a nested inner frame")
    (with-filter* (hide-named (:fn-name inner))
      (fn []
        (let [rows (get-in (traced-run! traced-fn) [:trace :rows])
              idx (first (keep-indexed #(when (= (:fn-name inner) (:fn-name %2)) %1) rows))
              after (get rows (inc idx))]
          (is (some? idx) "the concealed fn's own frame stays")
          (is (< (count rows) (count control)) "its internal frames are gone")
          (is (or (nil? after) (<= (:depth after) (:depth (get rows idx))))
              "nothing nests under the concealed frame"))))))


(deftest inline-execute-response-is-concealed
  (with-filter* (hide-named traced-fn)
    (fn []
      (let [id (ga/fn-id (keyword traced-fn))
            out (fn-exec/apply-execute (ctx) {:fn-id id :args {} :timeout-ms 10000
                                              :persist? true :trace? true})]
        (is (= :succeeded (:status out)))
        (is (= [(str id)] (mapv (comp str :fn-id) (:entries (:path-trace out))))
            "the inline /api/execute answer carries the leaf only")))))


(deftest unknown-ancestry-fails-closed-for-a-tenant
  (let [visible-id (ga/fn-id :web-server)
        pt {:entries [{:seq 7 :parent-seq 3 :fn-id (str visible-id) :duration-ms 1}
                      {:fn-id (str visible-id) :duration-ms 1}]
            :path-truncated? true}
        storage (:storage ga/*bootstrap*)]
    (testing "a tenant-shaped filter (only named fns visible): orphan + linkless frames drop"
      (with-filter* (hide-where #(not= "web-server" (:name %)))
        #(is (= [] (:entries (conceal/conceal-path-trace storage pt))))))
    (testing "a filter that shows unknown fns keeps them"
      (with-filter* (hide-named "nothing-by-this-name")
        #(is (identical? pt (conceal/conceal-path-trace storage pt)))))
    (testing "no filter installed → untouched"
      (is (identical? pt (conceal/conceal-path-trace storage pt))))))
