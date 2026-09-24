(ns ^:integration ^:serial graphden.packages.app.mcp-view-impl-test
  "The MCP graph reads honour the view-impl seam the tenancy addon installs
   (`crud.entities/view-impl-filter`, docs/TENANCY_SEAM.md) exactly like
   GET /api/graph/entities does: a fn the viewer may not see the internals
   of reads as its signature only — through `read-fn` (both formats),
   `search-fns` and `describe-fn`.

   `^:serial` — the seam is a process-global atom; a stub filter installed
   here would conceal fns from every suite running beside it."
  (:require
    [cheshire.core :as json]
    [clojure.edn :as edn]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.crud.entities :as entities]
    [graphden.test-infra.golden-app :as ga]))


(use-fixtures :once (ga/fixture (ns-name *ns*) ["core" "web" "app" "registry" "mcp"]))


(defn- hide-named
  "A stub view-impl filter, shaped like the tenancy addon's: conceal the
   composition of every fn in the dump named `fn-name`."
  [fn-name]
  (fn [graph]
    (entities/strip-impl-of graph (into #{}
                                        (comp (filter #(= fn-name (:name %))) (map :id))
                                        (:fns graph)))))


(defn- with-filter*
  [f body]
  (reset! entities/view-impl-filter f)
  (try (body) (finally (reset! entities/view-impl-filter nil))))


(defn- tool!
  [tool-name arguments]
  (let [resp (ga/exec-handler :_mcp-dispatch
                              {:headers {"content-type" "application/json"}
                               :body (json/generate-string
                                       {:jsonrpc "2.0" :id 1 :method "tools/call"
                                        :params {:name tool-name :arguments arguments}})})]
    (-> (:body resp) (json/parse-string true) :result :content first :text)))


(deftest impl-visible?-probes-the-seam
  (let [row {:id (random-uuid) :name "x"}]
    (is (true? (entities/impl-visible? row)) "no filter installed → visible")
    (with-filter* (hide-named "x")
      #(is (false? (entities/impl-visible? row)) "the filter hides it → not visible"))
    (with-filter* (hide-named "other")
      #(is (true? (entities/impl-visible? row)) "the filter hides something else → visible"))))


(deftest read-fn-edn-exports-a-hidden-fn-as-its-signature
  (testing "control — unfiltered, the composition and what it is built from ride along"
    (let [defs (edn/read-string (tool! "read-fn" {:name "web-server"}))
          names (set (map :name defs))]
      (is (contains? names :http-server))
      (is (contains? names :_app-error-bounded))))
  (with-filter* (hide-named "web-server")
    (fn []
      (let [defs (edn/read-string (tool! "read-fn" {:name "web-server"}))
            names (set (map :name defs))
            root (first (filter #(= :web-server (:name %)) defs))]
        (is (some? root) "the fn itself is still readable")
        (is (nil? (:parent root)) "its parent is concealed")
        (is (empty? (:args root)) "its bindings are concealed")
        (is (not (contains? names :http-server)) "the parent does not ride along")
        (is (not (contains? names :_app-error-bounded))
            "nothing it is built from rides along")))))


(deftest read-fn-rows-conceal-a-hidden-fn
  (with-filter* (hide-named "web-server")
    (fn []
      (let [data (json/parse-string (tool! "read-fn" {:name "web-server" :format "rows"}) true)
            root (first (filter #(= "web-server" (:name %)) (:fns data)))]
        (is (some? root))
        (is (empty? (:parent-ids root)) "parent-ids blanked")
        (is (not-any? #(= (:id root) (:fn-id %)) (:bindings data)) "no bindings of the hidden fn")
        (is (not-any? #(#{"http-server" "_app-error-bounded"} (:name %)) (:fns data))
            "the closure is walked AFTER concealment — no helper rows")))))


(deftest search-fns-conceals-parent-ids
  (with-filter* (hide-named "web-server")
    (fn []
      (let [data (json/parse-string (tool! "search-fns" {:q "web-server"}) true)
            row (first (filter #(= "web-server" (:name %)) (:fns data)))]
        (is (some? row) "still discoverable")
        (is (empty? (:parent-ids row)) "its parent is concealed")))))


(deftest describe-fn-hides-unread-bindings-of-a-hidden-fn
  (with-filter* (constantly {:fns [] :bindings []})
    (fn []
      (let [data (json/parse-string (tool! "describe-fn" {:name "web-server"}) true)]
        (is (= "web-server" (:name data)) "the contract is still answered")
        (is (= [] (:unread-bindings data)))))))
