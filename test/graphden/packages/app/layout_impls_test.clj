(ns graphden.packages.app.layout-impls-test
  "Unit tests for the `app/layout` package's PURE impls — the four
   base-fns the `parse → build → place → strip-facts` graph pipeline
   composes (`fns.edn`). Everything below runs on hand-built data: no
   container, no storage, no executor context.

   Why these matter beyond the src-level layout tests: each defbase is
   a thin boundary whose VALUE is the exact call it makes — the
   positional args it hardcodes, the exception classes it lets escape,
   and the fact that stage B consumes exactly what stage A emits. That
   contract has no other assertion; `layout-api-errors-test` exercises
   it only through the graph on a live container, and the src tests
   call `graphden.layout.*` directly, bypassing these impls entirely.

   `:_load-graph-cached` is deliberately absent — it reads storage +
   the per-context graph cache."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.test-infra.impls :as impls]))


(use-fixtures :once (impls/impls-fixture "app" "layout"))


(defn- call
  "Invoke a base-fn impl the way the executor does: args as delays.
   `delay` is a macro, so the map cannot be built with `update-vals`."
  [kw args]
  ((impls/impl-of kw)
   (into {} (map (fn [[k v]] [k (delay v)])) args)
   nil))


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


;; =============================================================================
;; :_layout-build-apply / :_layout-place-apply — stages A and B
;; =============================================================================

(deftest build-rejects-a-root-that-is-not-in-the-loaded-graph
  (testing "an unknown root-id throws :execution-error/not-found"
    ;; This is the stale-editor case (a fn deleted in another tab): the
    ;; graph's `:try` turns this exact type into `{:ok false :error}`.
    ;; An NPE or an empty result here would render a blank canvas with
    ;; no explanation instead.
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                  (call :_layout-build-apply
                        {:graph {:fns [] :args []}
                         :parsed {:root-id (random-uuid) :expansions {}}})))]
      (is (= :execution-error/not-found (:type (ex-data e)))))))


(deftest build-output-feeds-place-unchanged
  (testing "stage B grid-places exactly the node ids stage A emitted"
    ;; The two stages are separate base-fns glued by a fn-def, so the
    ;; shape contract between them ({:data {:id …}}-wrapped nodes) is
    ;; only enforced here. If build's node shape drifts, place silently
    ;; finds no root and returns an empty grid.
    (let [root (random-uuid)
          elements (call :_layout-build-apply
                         {:graph {:fns [{:id root :name "lit-root"}] :args []}
                          :parsed {:root-id root :expansions {}}})
          node-id (get-in (first (:nodes elements)) [:data :id])
          placed (call :_layout-place-apply {:elements elements})]
      (is (= (str "fn-" root) node-id))
      (is (contains? (:grid-pos placed) node-id)
          "the built node got a grid cell")
      (is (= (:nodes elements) (:nodes placed)) "nodes pass through untouched")
      (is (= (:edges elements) (:edges placed)) "edges pass through untouched")
      (is (true? (get-in placed [:validation :valid]))))))


(deftest place-of-an-empty-element-set-is-valid-not-an-error
  (testing "no nodes → empty grid, still :valid — an empty canvas is not a failure"
    (let [placed (call :_layout-place-apply {:elements {:nodes [] :edges []}})]
      (is (= {} (:grid-pos placed)))
      (is (true? (get-in placed [:validation :valid])))
      (is (empty? (get-in placed [:validation :issues]))))))


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
      (is (= [arg-node stale] out))))
  (testing "edges are never touched by the annotation pass"
    (let [elements {:nodes [] :edges [{:data {:source "a" :target "b"}}]}]
      (is (= (:edges elements)
             (:edges (call :_layout-strip-facts-apply
                           {:elements elements :graph {:fns []}})))))))
