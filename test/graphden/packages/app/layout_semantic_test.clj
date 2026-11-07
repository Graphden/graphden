(ns graphden.packages.app.layout-semantic-test
  "Graph-level layout annotation and dynamic outbound trace composition."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.crud.value-form :as value-form]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.interface :as exec]
    [graphden.storage.protocol.core :as sp]
    [graphden.test-infra.exec-harness :as harness]))


(use-fixtures :once (harness/exec-fixture (str (ns-name *ns*))))


(defn- call
  [name args]
  (exec/execute-by-name harness/*context* (clojure.core/name name) args))


;; =============================================================================
;; :_layout-strip-facts-apply — the bottom-of-card metadata strips
;; =============================================================================

(defn- annotate
  [nodes fns]
  (:nodes (call :_layout-strip-facts-apply
                {:elements {:nodes nodes :edges []}
                 :graph {:fns fns}})))


(defn- fn-node
  [fn-id]
  {:data {:originalFnId (str fn-id) :type "fn"}})


(deftest strip-facts-inherits-the-return-type-alias-through-parents
  (testing "a composed fn shows the alias its PARENT declares"
    ;; `web-server`'s own row carries no :return-type-fn-id — the value
    ;; lives on `:http-server`. Without the BFS the editor's strip is
    ;; blank on exactly the fns users look at.
    (let [alias-row (random-uuid)
          parent (random-uuid)
          child (random-uuid)
          [n] (annotate [(fn-node child)]
                        [{:id alias-row :name "http-server-handle"}
                         {:id parent :name "par" :return-type-fn-id alias-row}
                         {:id child :name "kid" :parent-ids [parent]}])]
      (is (= "http-server-handle" (get-in n [:data :returnTypeAlias]))))))


(deftest strip-facts-suppresses-a-primitive-return-type
  (testing "a return-type row named after a primitive produces NO alias"
    ;; The strip exists to show `→ port` instead of the unfolded
    ;; structural form. Showing `→ int` on every arithmetic fn is noise,
    ;; so the primitives set gates it — pin that gate.
    (let [prim (random-uuid)
          f (random-uuid)
          [n] (annotate [(fn-node f)]
                        [{:id prim :name "int"}
                         {:id f :name "adder" :return-type-fn-id prim}])]
      (is (nil? (get-in n [:data :returnTypeAlias]))))))


(deftest strip-facts-marks-branch-local-own-vs-inherited
  (let [seed (random-uuid)
        child (random-uuid)
        fns [{:id seed :name "http-server" :branch-local? true}
             {:id child :name "web-server" :parent-ids [seed]}]]
    (testing "the seed itself is :own true"
      (is (= {:own true :seed "http-server"}
             (get-in (first (annotate [(fn-node seed)] fns)) [:data :branchLocal]))))
    (testing "a descendant inherits the flag but is NOT the owner"
      ;; `:own false` is what makes the editor say \"inherited from
      ;; http-server\" rather than offering to clear the flag here —
      ;; the flag is monotonic-OR and cannot be cleared on a descendant.
      (is (= {:own false :seed "http-server"}
             (get-in (first (annotate [(fn-node child)] fns)) [:data :branchLocal]))))
    (testing "an unrelated fn carries no branchLocal key at all"
      (let [other (random-uuid)]
        (is (nil? (get-in (first (annotate [(fn-node other)]
                                           (conj fns {:id other :name "plain"})))
                          [:data :branchLocal])))))))


(deftest strip-facts-passes-unknown-and-non-fn-nodes-through-untouched
  (testing "a node whose originalFnId is not in the graph is returned as-is"
    ;; Arg nodes and stale ids both land here; annotating them with a
    ;; nil-derived key would put empty strips on value cards.
    (let [arg-node {:data {:id "arg-1" :type "arg"}}
          stale (fn-node (random-uuid))
          out (annotate [arg-node stale] [{:id (random-uuid) :name "unrelated"}])]
      (is (vector? out) "node annotation keeps the indexed layout contract")
      (is (= [arg-node stale] out))
      (is (= stale (get out 1)))))
  (testing "edges are never touched by the annotation pass"
    (let [elements {:nodes [] :edges [{:data {:source "a" :target "b"}}]}
          out (call :_layout-strip-facts-apply
                    {:elements elements :graph {:fns []}})]
      (is (= [] (:nodes out)))
      (is (vector? (:nodes out)))
      (is (= (:edges elements) (:edges out))))))


(deftest trace-header-graph-follows-dynamic-execution
  (let [id (random-uuid)
        trace-id (random-uuid)]
    (is (= {} (call :trace-headers {})))
    (binding [cr/*execution* {:id id}]
      (is (= {"X-Graphden-Trace" (str id ";" id)} (call :trace-headers {}))))
    (binding [cr/*execution* {:id id :trace-id trace-id}]
      (is (= {"X-Graphden-Trace" (str trace-id ";" id)} (call :trace-headers {}))))))


(deftest slot-seal-wording-is-graph-composition
  (is (= {} (call :_fn-slot-seals {})))
  (doseq [[fields expected]
          [[{:optional? true} "optional"]
           [{:optional? true :required-by "ancestor"} "required since ancestor"]
           [{:optional? false :sealed-by "parent" :list-closed-by "root"}
            "sealed in parent · list closed in root"]
           [{:optional? true :sealed-by "parent" :list-closed-by "root" :required-by "required"}
            "sealed in parent · list closed in root · required since required"]]]
    (is (= [:slot (assoc fields :note expected)]
           (call :_seal-entry {:item [:slot fields]}))))
  (is (false? (call :_seal-present? {:item [:slot {:optional? false}]}))))


(deftest slot-provenance-graph-retains-canonical-tier-values
  (let [fn-id (harness/fn-id "add")
        slot-id (:slot-id (first (sp/query-entities harness/*storage* :fn-slot {:fn-id fn-id})))
        parsed {:fn-id fn-id :slot-id slot-id}]
    (is (some? slot-id))
    (let [native (value-form/slot-type-provenance harness/*storage* parsed)
          composed (call :slot-type-provenance {:parsed parsed})]
      (is (= native composed))
      (is (vector? (:tiers native)))
      (is (vector? (:tiers composed)))
      (is (= :override (get-in composed [:tiers 0 :key])))
      (is (= :slot (get-in composed [:tiers 3 :key]))))
    (is (nil? (call :slot-type-provenance {:parsed (assoc parsed :item-id (random-uuid))}))))
  (is (nil? (call :slot-type-provenance {:parsed {}}))))


(defn- body
  [s]
  {:body (java.io.ByteArrayInputStream. (String/.getBytes ^String s "UTF-8"))})


;; =============================================================================
;; :_parse-layout-body — the request boundary
;; =============================================================================

(deftest parse-coerces-root-id-and-keeps-expansion-keys-as-strings
  (testing "root-id becomes a UUID while expansion keys stay STRINGS"
    ;; Regression guard for per-call-site node ids: a non-root node id
    ;; is `fn-<caller-tag>-<source-arg-id>`, which is NOT a parseable
    ;; UUID. If this ever keywordizes or uuid-coerces the keys, every
    ;; nested expansion silently stops matching the ids layout assigned
    ;; while building, and the graph refuses to expand past depth 1.
    (let [id (random-uuid)
          out (call :_parse-layout-body
                    {:request (body (str "{\"root-id\":\"" id "\","
                                         "\"expansions\":{\"fn-" id "\":2}}"))})]
      (is (= id (:root-id out)))
      (is (uuid? (:root-id out)))
      (is (= {(str "fn-" id) 2} (:expansions out)))))
  (testing "an absent :expansions key parses to an empty map, never nil"
    ;; `build-elements` indexes into it; nil would be a downstream NPE.
    (let [out (call :_parse-layout-body
                    {:request (body (str "{\"root-id\":\"" (random-uuid) "\"}"))})]
      (is (= {} (:expansions out))))))


(deftest parse-partial-expansion-spec-normalises-partial-fns-to-uuids
  (testing "a map spec keeps :full-depth and turns :partial-fns into a UUID set"
    (let [root (random-uuid)
          partial-id (random-uuid)
          out (call :_parse-layout-body
                    {:request (body (str "{\"root-id\":\"" root "\","
                                         "\"expansions\":{\"fn-" root "\":"
                                         "{\"full-depth\":1,"
                                         "\"partial-fns\":[\"" partial-id "\"]}}}"))})]
      (is (= {:full-depth 1 :partial-fns #{partial-id}}
             (get (:expansions out) (str "fn-" root)))))))


(deftest parse-lets-each-rejection-escape-as-its-own-exception-class
  ;; The graph's `:try` in fns.edn dispatches on the exception CLASS to
  ;; pick the user-facing message. Collapsing two of these into one
  ;; class (or catching them here) would silently reroute a 400 to the
  ;; wrong branch — the impl's job is to let them through untouched.
  (testing "a body with no root-id throws a typed ExceptionInfo"
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                  (call :_parse-layout-body {:request (body "{}")})))]
      (is (= :execution-error/invalid-args (:type (ex-data e))))))
  (testing "a non-map :expansions is rejected, not silently ignored"
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                  (call :_parse-layout-body
                        {:request (body (str "{\"root-id\":\"" (random-uuid) "\","
                                             "\"expansions\":[1,2]}"))})))]
      (is (= :execution-error/invalid-args (:type (ex-data e))))
      (is (= "PersistentVector" (:got (ex-data e))))))
  (testing "an unparseable root-id surfaces as IllegalArgumentException"
    (is (thrown? IllegalArgumentException
          (call :_parse-layout-body
                {:request (body "{\"root-id\":\"not-a-uuid\"}")})))))
