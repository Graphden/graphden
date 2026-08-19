(ns graphden.packages.app.value-repr-test
  "Typed value representations in the execute-result body — the
   `:_value-repr-registry` dispatch (`app/reprs`), the component-
   preview clause, and the safety properties: repr purity enforcement
   and hiccup sanitization. Runs `:_er-succeeded-body` over the real
   synced graph (golden clone)."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.crud.value-form :as vform]
    [graphden.crud.value-repr :as vrepr]
    [graphden.executor.interface :as exec]
    [graphden.test-infra.exec-harness :as harness]))


(use-fixtures :once (harness/exec-fixture (str (ns-name *ns*))))


(defn- in-tree?
  [form x]
  (boolean (some #(= x %) (tree-seq coll? seq form))))


(defn- tree-string-containing
  [form s]
  (some #(and (string? %) (str/includes? % s))
        (tree-seq coll? seq form)))


(defn- succeeded-body
  ([result] (succeeded-body result nil))
  ([result fn-id]
   (exec/execute-by-name harness/*context* "_er-succeeded-body"
                         {:exec (cond-> {:status "succeeded" :result result}
                                  fn-id (assoc :fn-id (str fn-id)))})))


(deftest numeric-list-sparkline-test
  (testing "a numeric list renders the sparkline repr (runtime-shape fallback dispatch)"
    (let [f (succeeded-body [3 1 4 1 5 9 2 6])]
      (is (in-tree? f "repr-sparkline-wrap"))
      (is (in-tree? f "polyline") "sanitized output carries string tags")
      (is (tree-string-containing f "8 values"))))

  (testing "floats count as numeric"
    (is (in-tree? (succeeded-body [1.5 2.25 0.5]) "repr-sparkline-wrap")))

  (testing "a mixed list keeps the plain list pane"
    (let [f (succeeded-body [1 "two" 3])]
      (is (not (in-tree? f "repr-sparkline-wrap")))
      (is (in-tree? f "execute-result-list"))))

  (testing "a scalar keeps the scalar pane"
    (let [f (succeeded-body 42)]
      (is (not (in-tree? f "repr-sparkline-wrap")))
      (is (in-tree? f "execute-result-scalar")))))


(deftest component-preview-test
  (harness/sync! [{:name :vr-comp
                   :parent :const
                   :return-type :hiccup-node
                   :args {:value ["div" {"class" "c"} "hello-comp"]}}
                  {:name :vr-scalar
                   :parent :const
                   :return-type :int
                   :args {:value 42}}])
  (testing "the declared return type is recorded and readable"
    (let [rt (exec/execute-by-name harness/*context* "fn-return-type"
                                   {:fn-id (str (harness/fn-id "vr-comp"))})]
      (is (some? rt) (str "fn-return-type for vr-comp: " (pr-str rt)))))
  (testing "a declared :hiccup-node return renders the sandboxed component preview"
    (let [f (succeeded-body ["div" {"class" "c"} "hello-comp"]
                            (harness/fn-id "vr-comp"))
          attrs (first (filter #(and (map? %) (contains? % :srcdoc))
                               (tree-seq coll? seq f)))]
      (is (in-tree? f :iframe))
      (is (in-tree? f "Component preview"))
      (is (some? attrs) "iframe attrs carry the rendered markup as srcdoc")
      (is (str/includes? (str (:srcdoc attrs)) "hello-comp"))
      (is (= "" (:sandbox attrs))
          "component preview is fully sandboxed — no scripts, no same-origin")))

  (testing "without the fn-id the same value renders as a plain list"
    (let [f (succeeded-body ["div" {"class" "c"} "hello-comp"])]
      (is (not (in-tree? f :iframe)))))

  (testing "a scalar-returning fn never lands in an iframe (union-membership trap)"
    (let [f (succeeded-body 42 (harness/fn-id "vr-scalar"))]
      (is (not (in-tree? f :iframe)))
      (is (in-tree? f "execute-result-scalar")))))


(deftest repr-safety-test
  (try
    (testing "an effectful repr target is refused (runtime effect gate)"
      ;; The `:env` read is FORCED (it is coalesce's `:value`), so the
      ;; repr genuinely performs an effect at render time — the
      ;; `:allowed-effects #{}` gate must throw. The represented value
      ;; arrives via `:default {:as :value}`.
      (harness/sync! [{:name :_vr-effectful-repr
                       :parent :coalesce
                       :args {:value {:parent :env
                                      :args {:name {:value "GRAPHDEN_VR_TEST_UNSET"}}}
                              :default {:as :value}}}
                      {:name :_value-repr-registry
                       :namespace "app.reprs"
                       :parent :const
                       :args {:value [["any" "_vr-effectful-repr"]]}}])
      (let [f (succeeded-body [1 2 3])]
        (is (not (tree-string-containing f "execute-result-repr")))
        (is (in-tree? f "execute-result-list") "falls back to the shape pane")))

    (testing "repr output passes the hiccup sanitizer before inlining"
      (harness/sync! [{:name :_vr-identity-repr
                       :parent :coalesce
                       :args {:default {:value "n/a"}}}
                      {:name :_value-repr-registry
                       :namespace "app.reprs"
                       :parent :const
                       :args {:value [["any" "_vr-identity-repr"]]}}])
      (testing "hostile attrs are stripped, content survives"
        (let [f (succeeded-body ["div" {"hx-get" "/api/secret"
                                        "onclick" "alert(1)"
                                        "class" "ok"}
                                 "evil-content"])]
          (is (tree-string-containing f "execute-result-repr") "repr pane fired")
          (is (in-tree? f "evil-content"))
          (is (not (in-tree? f "hx-get")))
          (is (not (in-tree? f "onclick")))))
      (testing "a script-rooted repr sanitizes to nothing and falls through"
        (let [f (succeeded-body ["script" {} "alert(1)"])]
          (is (not (tree-string-containing f "execute-result-repr")))
          (is (in-tree? f "execute-result-list")))))

    (finally
      ;; Restore the FULL shipped registry so sibling deftests in this
      ;; NS (whatever order kaocha runs them in) see the real dispatch —
      ;; a partial restore is an order-dependent flake (caught by a
      ;; landing gate whose seed ran repr-safety first).
      (harness/sync! [{:name :_value-repr-registry
                       :namespace "app.reprs"
                       :parent :const
                       :args {:value [[["list" "numeric"] "_repr-numeric-list"]
                                      [["list" ["map" "keyword" "any"]] "_repr-record-list"]]}}]))))


(deftest render-value-repr-direct-test
  (testing "the base-fn itself: numeric list -> sanitized sparkline hiccup"
    (let [h (exec/execute-by-name harness/*context* "render-value-repr"
                                  {:value [1 2 3] :fn-id nil})]
      (is (vector? h))
      (is (in-tree? h "polyline"))))

  (testing "nil / unrepresentable values -> nil"
    (is (nil? (exec/execute-by-name harness/*context* "render-value-repr"
                                    {:value nil :fn-id nil})))
    (is (nil? (exec/execute-by-name harness/*context* "render-value-repr"
                                    {:value "plain" :fn-id nil})))))


(deftest record-list-table-repr-test
  ;; Self-contained: sync the shipped registry value explicitly — the
  ;; golden template DB is cached per PACKAGE-NAME set (not content)
  ;; and shared across the gate's unit/perf JVMs, so a clone may carry
  ;; a pre-table registry row regardless of this checkout's fns.edn.
  (harness/sync! [{:name :_value-repr-registry
                   :namespace "app.reprs"
                   :parent :const
                   :args {:value [[["list" "numeric"] "_repr-numeric-list"]
                                  [["list" ["map" "keyword" "any"]] "_repr-record-list"]]}}])
  (testing "a list of keyword-keyed records renders as a table"
    (let [v [{:name "a" :n 1 :extra {:deep true}}
             {:name "b" :n 2 :extra nil}]
          f (succeeded-body v)]
      (is (tree-string-containing f "repr-record-table")
          (str "DIAG dt=" (pr-str (vrepr/dispatch-type nil v))
               " pairs=" (pr-str (vform/registry-pairs
                                   harness/*context* "_value-repr-registry"))
               " direct=" (pr-str (try (vrepr/render-repr
                                         harness/*context* v nil)
                                       (catch Exception e (ex-message e))))))
      (is (in-tree? f "th") "header cells present")
      (is (in-tree? f "name") "column from record keys")
      (is (in-tree? f "a") "simple cell as text")
      (is (some #(and (string? %) (str/includes? % "deep"))
                (tree-seq coll? seq f))
          "complex cell falls back to JSON text")
      (is (tree-string-containing f "2 rows"))))

  (testing "records with disagreeing shapes keep the plain list pane"
    (let [f (succeeded-body [{:a 1} "not-a-record" {:b 2}])]
      (is (not (tree-string-containing f "repr-record-table")))
      (is (in-tree? f "execute-result-list"))))

  (testing "a single record (not a list) keeps the record pane"
    (let [f (succeeded-body {:a 1 :b "x"})]
      (is (not (tree-string-containing f "repr-record-table")))
      (is (in-tree? f "execute-result-record"))))

  (testing "numeric lists still sparkline (registry rows are disjoint)"
    (is (in-tree? (succeeded-body [1 2 3]) "repr-sparkline-wrap"))))
