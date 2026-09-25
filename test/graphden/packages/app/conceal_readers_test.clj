(ns ^:integration ^:serial graphden.packages.app.conceal-readers-test
  "The layout, the rich-types by-id reads, the rule owner, the card /
   Inspector branch-local seed and the fn version history honour the
   view-impl seam (`crud.entities/view-impl-filter`, docs/TENANCY_SEAM.md,
   SECURITY_MODEL.md layer 9): a viewer who may not see a fn's composition
   learns it from none of them, and a fn the viewer's storage cannot read
   has no registry entry and no history for them.

   `^:serial` — the seam is a process-global atom; a stub filter installed
   here would conceal fns from every suite running beside it."
  (:require
    [cheshire.core :as json]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.crud.entities :as entities]
    [graphden.crud.entities.list :as entity-list]
    [graphden.executor.registry.core :as registry]
    [graphden.executor.test-setup :as setup]
    [graphden.layout.core :as lc]
    [graphden.layout.graph :as lg]
    [graphden.layout.strip-facts :as strip-facts]
    [graphden.storage.protocol.core :as sp]
    [graphden.test-infra.golden-app :as ga]))


(use-fixtures :once (ga/fixture (ns-name *ns*)))


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


(defn- storage
  []
  (:storage ga/*bootstrap*))


(defn- ctx
  []
  (assoc (:ctx ga/*bootstrap*) :storage (storage)))


(defn- run-fn
  [fn-name args]
  (setup/exec-with-storage (:ctx ga/*bootstrap*) (storage) (ga/fn-id fn-name) args))


;; ---------------------------------------------------------------------------
;; POST /api/graph/layout
;; ---------------------------------------------------------------------------

(defn- layout-nodes
  "The fn-nodes of `root-name`'s layout with the root and every node
   whose fn is in `also-expand` expanded three levels."
  ([root-name] (layout-nodes root-name #{}))
  ([root-name also-expand]
   (let [root (ga/fn-id root-name)
         graph (lg/load-graph-entities-uncached (storage))
         spec {:full-depth 3 :partial-fns #{}}
         build #(:nodes (lc/build-elements-for-viewer graph root %))
         first-pass (build {(str "fn-" root) spec})
         more (into {}
                    (keep (fn [n]
                            (when (contains? also-expand (some-> (get-in n [:data :originalFnId]) parse-uuid))
                              [(get-in n [:data :id]) spec])))
                    first-pass)]
     (build (assoc more (str "fn-" root) spec)))))


(defn- fn-ids-of
  [nodes]
  (into #{} (keep #(some-> (get-in % [:data :originalFnId]) parse-uuid)) nodes))


(deftest a-hidden-root-lays-out-as-its-signature
  (let [ws (ga/fn-id :web-server)
        handler (ga/fn-id :_app-error-bounded)
        control (fn-ids-of (layout-nodes :web-server))]
    (is (contains? control handler)
        "control — expanded, the card draws the handler it is built on")
    (with-filter* (hide-named "web-server")
      (fn []
        (is (= #{ws} (fn-ids-of (layout-nodes :web-server)))
            "concealed: the root card alone — nothing it is built from is drawn")))))


(deftest a-hidden-inner-fn-draws-no-children
  (let [inner (ga/fn-id :_app-error-bounded)
        control (fn-ids-of (layout-nodes :web-server #{inner}))]
    (is (< 2 (count control)) "control — the expanded handler draws what it is built from")
    (with-filter* (hide-named "_app-error-bounded")
      (fn []
        (let [seen (fn-ids-of (layout-nodes :web-server #{inner}))]
          (is (contains? seen inner) "the concealed fn's own card stays")
          (is (= #{(ga/fn-id :web-server) inner} seen)
              "nothing beneath it is drawn, however far it is expanded"))))))


;; ---------------------------------------------------------------------------
;; Strip facts + the Inspector's branch-local row
;; ---------------------------------------------------------------------------

(defn- branch-local-of
  [fn-name]
  (let [graph (lg/load-graph-entities-uncached (storage))
        id (ga/fn-id fn-name)
        node {:data {:originalFnId (str id)}}]
    (get-in (strip-facts/annotate {:nodes [node]} graph) [:nodes 0 :data :branchLocal])))


(deftest an-inherited-seed-is-unnamed-behind-concealed-composition
  (testing "control — web-server inherits branch-local from http-server"
    (is (= {:own false :seed "http-server"} (branch-local-of :web-server))))
  (with-filter* (hide-named "web-server")
    (fn []
      (is (= {:own false} (branch-local-of :web-server))
          "the flag stays (it is how the fn merges), the ancestor goes")
      (is (= {:own true :seed "http-server"} (branch-local-of :http-server))
          "a fn's OWN seed is itself — nothing to conceal")
      (is (= {:own false} (run-fn :_fn-branch-local-seed {:fn-id (ga/fn-id :web-server)}))
          "the Inspector's Merge row reads the same"))))


(defn- rule-owned-fn
  "Some composed golden fn whose return type a base-fn rule computes."
  []
  (->> (sp/query-entities (storage) :fn {})
       (filter #(and (seq (:parent-ids %)) (:name %)
                     (registry/rule-owner-info-of-id (:id %))))
       first))


(deftest a-concealed-fn-names-no-rule-owner
  (let [f (rule-owned-fn)
        owner #(entities/viewer-rule-owner (ctx) (:id f))]
    (is (some? f) "the golden graph has a rule-owned composed fn")
    (is (some? (owner)) "control")
    (with-filter* (hide-named (:name f))
      #(is (nil? (owner)) "the base-fn it bottoms out in is its composition"))
    (with-filter* (hide-named "nothing-by-this-name")
      #(is (some? (owner)) "a filter that hides nothing keeps it"))))


;; ---------------------------------------------------------------------------
;; Rich-types registry read by id
;; ---------------------------------------------------------------------------

(deftest registry-reads-by-id-drop-a-hidden-fns-internals
  (let [id (ga/fn-id :web-server)
        entry #(run-fn :rich-type-of-id {:fn-id id})]
    (testing "control — the raw entry carries the chain's bindings"
      (is (seq (:resolved-bindings (entry))))
      (is (some? (:primary-parent (entry)))))
    (with-filter* (hide-named "web-server")
      (fn []
        (let [e (entry)]
          (is (some? (:return e)) "the signature stays")
          (is (not-any? #(contains? e %) entity-list/concealed-entry-fields)
              "no bindings, no primary parent, no per-binding effects"))))))


(deftest a-fn-the-viewer-cannot-read-has-no-registry-entry
  (let [id (ga/fn-id :web-server)
        entry (registry/rich-type-of-id id)
        blind #_{:clj-kondo/ignore [:missing-protocol-method]}
        (reify sp/StorageCRUD
          (read-entity [_ _ _] nil))]
    (is (some? entry))
    (testing "no filter installed — single-tenant reads everything"
      (is (identical? entry (entities/viewer-rich-entry blind id entry))))
    (with-filter* (hide-named "nothing-by-this-name")
      #(is (nil? (entities/viewer-rich-entry blind id entry))
           "another org's private fn: the global index must not answer for it"))))


;; ---------------------------------------------------------------------------
;; GET /api/fns/:id/versions
;; ---------------------------------------------------------------------------

(defn- versions-resp
  [fn-id]
  (let [resp (ga/exec-handler :list-fn-versions-handler
                              {:uri (str "/api/fns/" fn-id "/versions")
                               :request-method :get :headers {}})]
    {:status (:status resp) :body (json/parse-string (:body resp) true)}))


(deftest versions-of-an-unreadable-fn-are-not-found
  (let [{:keys [status body]} (versions-resp (random-uuid))]
    (is (= 404 status) "no history for a fn the viewer's storage cannot read")
    (is (false? (:ok body)))))


(deftest versions-of-a-hidden-fn-carry-no-composition-columns
  (let [id (ga/fn-id :web-server)
        rows #(:versions (:body (versions-resp id)))]
    (testing "control"
      (is (seq (rows)))
      (is (every? #(contains? % :anonymous-hash) (rows))))
    (with-filter* (hide-named "web-server")
      (fn []
        (is (seq (rows)) "the history itself stays — the fn is the viewer's to read")
        (is (not-any? #(or (contains? % :anonymous-hash) (contains? % :base-fn-id)) (rows))
            "no composition hash, no base-fn")))))
