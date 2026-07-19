(ns ^:integration graphden.packages.app.layout-strip-facts-test
  "End-to-end test of the layout strip-facts annotation
   (`graphden.layout.strip-facts`, wired as the `:_layout-strip-facts`
   stage of `:get-layout-data`) — the server-computed replacement for
   the three JS walks `editor-overlay-strips.js` used to keep:
   return-type-alias inheritance BFS, rule-owner primary-parent walk,
   and the transitive branch-local walk."
  (:require
    [cheshire.core :as json]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.core :as registry-core]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.postgres-test-helpers :as pth]
    [graphden.test-infra.shared-bootstrap :as sb]))


(def ^:dynamic *container* nil)
(def ^:dynamic *bootstrap* nil)


(use-fixtures :once
  (pth/create-container-fixture #'*container*)
  exec/with-clean-registry
  ;; `rule-owner-of` reads composed fn-defs' registry entries; the
  ;; plain golden bootstrap seeds only base-fn entries. Overlay the
  ;; cached full type-check sweep so the registry looks like
  ;; production's.
  exec/with-isolated-rich-types
  (fn [f]
    (binding [*bootstrap* (setup/bootstrap-crud-graph-from-golden!)]
      (reset! registry-core/*rich-types-override*
              (sb/ensure-swept-rich-types! ["core" "web" "app"]))
      (f))))


(defn- layout-response
  "Run `:get-layout-data` for the named root fn."
  [root-name]
  (let [{:keys [ctx storage]} *bootstrap*
        root-id (get (:all-name->id *bootstrap*) root-name)
        handler-id (get (:all-name->id *bootstrap*) :get-layout-data)
        body (json/generate-string {:root-id (str root-id) :expansions {}})
        resp (setup/exec-with-storage ctx storage handler-id
                                      {:request {:body body}})]
    (is (vector? (:nodes resp)) "layout returns a node vector")
    (assoc resp ::root-id root-id)))


(defn- layout-for
  "Run `:get-layout-data` for the named root fn and return the node
   whose `:originalFnId` is the root's id."
  [root-name]
  (let [resp (layout-response root-name)
        root-id (::root-id resp)]
    (some #(when (= (str root-id) (get-in % [:data :originalFnId])) %)
          (:nodes resp))))


(deftest rule-owner-fact
  ;; `:_fn-row-by-id-hsql` has `:parent :assoc` — the `↳` badge gate.
  (let [node (layout-for :_fn-row-by-id-hsql)]
    (is (some? node))
    (is (= "assoc" (get-in node [:data :ruleOwner])))))


(deftest return-type-alias-and-branch-local-facts
  ;; `:web-server` inherits `:return-type-fn-id` from `:http-server`
  ;; (the alias `http-server-handle`) AND `:http-server` is a
  ;; branch-local seed, so the descendant carries the inherited flag.
  (let [node (layout-for :web-server)]
    (is (some? node))
    (testing "inherited return-type alias resolved server-side"
      (is (= "http-server-handle" (get-in node [:data :returnTypeAlias]))))
    (testing "transitive branch-local seed attribution"
      (let [bl (get-in node [:data :branchLocal])]
        (is (map? bl))
        (is (false? (:own bl)))
        (is (= "http-server" (:seed bl)))))))


(deftest edge-desc-source-fact
  ;; Every arg edge should carry `:descSource` — the server-resolved
  ;; description precedence (closest binding with a description in the
  ;; parent-ids closure, else the slot row). The editor reads the TEXT
  ;; by id from its lookups; only the WALK moved server-side.
  (let [resp (layout-response :web-server)
        arg-edges (filter #(get-in % [:data :slotId]) (:edges resp))]
    (is (seq arg-edges) "web-server layout has arg edges")
    (doseq [e arg-edges]
      (let [ds (get-in e [:data :descSource])]
        (is (map? ds) (str "edge " (get-in e [:data :id]) " carries descSource"))
        (is (contains? #{"binding" "slot"} (:entityType ds)))
        (is (string? (:entityId ds)))))))


(deftest base-fn-node-carries-no-owner-fact
  ;; `:add` is a base-fn — no primary parent, no rule owner, no alias
  ;; inherited from anywhere else, not branch-local.
  (let [node (layout-for :add)]
    (is (some? node))
    (is (nil? (get-in node [:data :ruleOwner])))
    (is (nil? (get-in node [:data :branchLocal])))))
