(ns graphden.crud.types-api-test
  "Tests for `graphden.crud.types-api` — the Clojure bodies the
   `/api/types`, `/api/types/candidates` and `/api/types/usages` graph
   handlers call, plus the shared graph-cache loaders and role / rich-type derivations.

   The pure helpers (`compute-fn-role`, `json->type`,
   `constraint-contains-type-ref?`) need no fixture;
   the rest go through the shared container. The endpoints' parse /
   validate stages and the candidates enumeration are graph fn-defs —
   covered through the handlers in `types-api-graph-test`."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.crud.types-api :as ta]
    [graphden.executor.context :as ctx]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]))


(use-fixtures :once (setup/create-container-fixture))


(defn- test-ctx
  [storage]
  (ctx/create-context {:storage storage :base-fns {}}))


;; ============================================================================
;; compute-fn-role — pure
;; ============================================================================

(deftest compute-fn-role-test
  (testing "parent-ids present → :composed"
    (is (= :composed (ta/compute-fn-role {:parent-ids [(random-uuid)]} false {}))))

  (testing "return-type-fn-id → :base-fn"
    (is (= :base-fn (ta/compute-fn-role {:parent-ids [] :return-type-fn-id (random-uuid)} false {}))))

  (testing "no return-type-fn-id but a registry entry with args → :base-fn"
    (is (= :base-fn
           (ta/compute-fn-role {:name "regfn" :parent-ids []} false
                               {:regfn {:args {:a :int}}}))))

  (testing "base-fn-id → :refinement, element-fn-id → :list"
    (is (= :refinement (ta/compute-fn-role {:base-fn-id (random-uuid)} false {})))
    (is (= :list (ta/compute-fn-role {:element-fn-id (random-uuid)} false {}))))

  (testing "constraint head → :union / :variant / :fn-type"
    (is (= :union    (ta/compute-fn-role {:constraint [:union :int :text]} false {})))
    (is (= :variant  (ta/compute-fn-role {:constraint [:variant :ok :int]} false {})))
    (is (= :fn-type  (ta/compute-fn-role {:constraint [:fn {} :int]} false {}))))

  (testing "plain row → :record when it has slots, :primitive otherwise"
    (is (= :record (ta/compute-fn-role {:name "rec"} true {})))
    (is (= :primitive (ta/compute-fn-role {:name "prim"} false {})))))


;; ============================================================================
;; project-rich-type-entry — pure projection from in-memory registry shape
;; to the JSON-safe wire shape served by `/api/types`. Each per-base-fn
;; type-rule key (the Clojure-fn values that can't survive serialization)
;; must be replaced by a JSON-safe boolean flag so the editor can detect
;; "this entry's return-type was rule-computed" without ever seeing the
;; rule's impl.
;; ============================================================================

(deftest project-rich-type-entry-test
  (testing "pure entry with no rule keys passes through unchanged"
    (let [entry {:return :int :args {:a :int} :effects [] :description "add"}]
      (is (= entry (ta/project-rich-type-entry entry)))))

  (testing "rule fns are stripped — never leak to JSON"
    (let [rule (fn [_ _] :int)
          projected (ta/project-rich-type-entry
                      {:return :any
                       :return-type-rule rule
                       :slot-types-rule rule
                       :nav-types-rule rule})]
      (is (= {:return :any} projected)
          "all three rule keys dropped, no replacement flags — the
           rule-owner fact ships via layout strip facts / the
           return-type-rule partial, not the /api/types wire")))

  (testing "every value in the projected entry is JSON-encodable"
    ;; A future contributor adding a non-encodable side-channel will
    ;; trip this — the assertion stays naive (no Clojure-fns / instances).
    (let [projected (ta/project-rich-type-entry
                      {:return :any
                       :return-type-rule (fn [_ _] :int)
                       :slot-types-rule (fn [_ _] :int)
                       :nav-types-rule (fn [_ _] :int)
                       :args {:m :any}
                       :effects []})]
      (doseq [v (vals projected)]
        (is (not (fn? v))
            (str "projected entry should not carry a Clojure fn, got: "
                 (type v)))))))


;; ============================================================================
;; json->type — pure
;; ============================================================================

(deftest json->type-test
  (testing "strings → keywords, scalars pass through"
    (is (= :int (ta/json->type "int")))
    (is (= 42 (ta/json->type 42)))
    (is (true? (ta/json->type true)))
    (is (nil? (ta/json->type nil))))

  (testing "fn-type / refinement / record JSON shapes round-trip"
    (is (= [:fn {:x :int} :int]
           (ta/json->type ["fn" {"x" "int"} "int"])))
    (is (= [:refine :int [:>= 0]]
           (ta/json->type ["refine" "int" [">=" 0]])))
    (is (= {:a :int :b :text}
           (ta/json->type {"a" "int" "b" "text"}))))

  (testing "union vectors, maps (string keys → keywords), scalars"
    (is (= [:union :int :text]
           (ta/json->type ["union" "int" "text"])))
    (is (= {:x :int :y :text}
           (ta/json->type {"x" "int" "y" "text"})))
    (is (= 7 (ta/json->type 7)))
    (is (nil? (ta/json->type nil))))

  (testing "type variables come back as SYMBOLS, not keywords"
    ;; The wire writes `'a` as "a" — indistinguishable from a type name.
    ;; Decoded as `:a` the variable is rigid, and a polymorphic slot such
    ;; as map's `[:fn {:item a} b]` accepts nothing but never-returning fns.
    (is (= '[:fn {:item a} b] (ta/json->type ["fn" {"item" "a"} "b"])))
    (is (= '[:list a-4609] (ta/json->type ["list" "a-4609"]))
        "a freshened variable keeps its suffix")
    (is (= :int (ta/json->type "int")) "a type NAME stays a keyword")
    (is (= :non-empty-text (ta/json->type "non-empty-text")))
    (is (true? (ta/wire-type-var? "b")))
    (is (false? (ta/wire-type-var? "ab")) "two letters is a name, not a variable")
    (is (false? (ta/wire-type-var? "A")) "variables are lowercase"))

  (testing "refinement constraints keep string literal values intact"
    ;; JSON can't tell a keyword from a string; a blind decode once
    ;; keywordized constraint values — `[:not= ""]` → `[:not= :]`,
    ;; silently making `:non-empty-text` accept `""`.
    (is (= [:refine :text [:not= ""]]
           (ta/json->type ["refine" "text" ["not=" ""]])))
    (is (= [:refine :text [:= "x"]]
           (ta/json->type ["refine" "text" ["=" "x"]])))
    (is (= [:refine :text [:not= ":literal"]]
           (ta/json->type ["refine" "text" [":not=" ":literal"]])))
    (is (= [:refine :text [:matches "^[a-z]+$"]]
           (ta/json->type ["refine" "text" ["matches" "^[a-z]+$"]])))
    (is (= [:refine :int [:and [:>= 1] [:<= 5]]]
           (ta/json->type ["refine" "int" ["and" [">=" 1] ["<=" 5]]]))))

  (testing "a :keyword-based refinement keeps keyword constraint values"
    ;; The refinement base disambiguates: a `:keyword` base means the
    ;; operands ARE keywords (`[:in [:get :post]]`, `[:= :ok]`), so a
    ;; structural-only fix that never keywordized would be wrong too.
    (is (= [:refine :keyword [:in [:get :post :delete]]]
           (ta/json->type
             ["refine" "keyword" ["in" ["get" "post" "delete"]]])))
    (is (= [:refine :keyword [:= :ok]]
           (ta/json->type ["refine" "keyword" ["=" "ok"]])))
    (is (= [:refine :keyword [:= :ok]]
           (ta/json->type ["refine" "keyword" [":=" ":ok"]])))
    ;; A refinement OF a keyword-refinement: the base resolves to
    ;; `:keyword` only by recursing through the nested `[:refine …]`.
    (is (= [:refine [:refine :keyword [:in [:get :post]]] [:not= :patch]]
           (ta/json->type
             ["refine" ["refine" "keyword" ["in" ["get" "post"]]]
              ["not=" "patch"]]))))

  (testing "a refinement nested inside a union still round-trips"
    (is (= [:union :null [:refine :text [:not= ""]]]
           (ta/json->type
             ["union" "null" ["refine" "text" ["not=" ""]]]))))

  (testing "an unrecognised constraint op still gets a keyword head"
    ;; The decoder must not depend on an enumerated op set — a future
    ;; op should keywordise its head, not silently decode to a string.
    (is (= [:refine :text [:future-op "v"]]
           (ta/json->type ["refine" "text" ["future-op" "v"]])))))


;; ============================================================================
;; constraint-contains-type-ref? — pure
;; ============================================================================

(deftest constraint-contains-type-ref-test
  (testing "a name nested in a union / fn-type constraint is found"
    (is (true? (ta/constraint-contains-type-ref? [:union :int :my-type] :my-type)))
    (is (true? (ta/constraint-contains-type-ref? [:fn {:x :my-type} :int] "my-type"))))

  (testing "an absent name → false; nil name → false"
    (is (false? (ta/constraint-contains-type-ref? [:union :int :text] :missing)))
    (is (false? (ta/constraint-contains-type-ref? [:union :int] nil)))))


;; ============================================================================
;; Graph-cache loaders
;; ============================================================================

(deftest graph-cache-loader-test
  (let [storage (setup/create-test-storage)
        c (test-ctx storage)]
    (try
      (testing "load-graph-entities-uncached returns the five graph tables"
        (let [g (ta/load-graph-entities-uncached storage)]
          (is (every? #(contains? g %)
                      [:fns :slots :fn-slots :bindings :list-items]))))

      (testing "cached-or-load-graph fills the ctx cache on first call"
        (is (nil? (ctx/cached-graph c)))
        (let [g (ta/cached-or-load-graph c)]
          (is (contains? g :fns))
          (is (some? (ctx/cached-graph c)))))
      (finally (sp/close storage)))))


;; ============================================================================
;; rich-types-with-type-rows / all-rich-types
;; ============================================================================

(deftest rich-types-with-type-rows-test
  (let [storage (setup/create-test-storage)
        c (test-ctx storage)]
    (try
      (testing "a storage-only refinement type-row surfaces with its structural form"
        (let [int-id (get setup/primitive-fn-ids :int)
              _      (sp/create-entity storage :fn
                                       {:name "rttr-pos" :parent-ids []
                                        :base-fn-id int-id :constraint [:> 0]})
              snap   (ta/rich-types-with-type-rows c)
              entry  (get snap :rttr-pos)]
          (is (some? entry))
          (is (true? (:type-row? entry)))
          (is (= [:refine :int [:> 0]] (:return entry)))))

      (testing "all-rich-types is the same snapshot"
        (is (map? (ta/all-rich-types c))))

      (finally (sp/close storage))))

  ;; Fresh storage — `cached-or-load-graph` memoises on the ctx, so a
  ;; second batch of writes needs a clean ctx to be visible.
  (let [storage (setup/create-test-storage)
        c (test-ctx storage)]
    (try
      (testing "record / union / variant / list / fn type-rows all surface"
        (let [int-id  (get setup/primitive-fn-ids :int)
              text-id (get setup/primitive-fn-ids :text)
              ;; record type-row — a fn with own slots, no parent/impl
              rec   (sp/create-entity storage :fn
                                      {:name "rttr-rec" :parent-ids []
                                       :description "a record row"})
              s1    (setup/create-slot! storage "title" :text)
              s2    (setup/create-slot! storage "count" :int)
              _     (setup/attach-slot! storage (:id rec) (:id s1) 0)
              _     (setup/attach-slot! storage (:id rec) (:id s2) 1)
              _     (sp/create-entity storage :fn
                                      {:name "rttr-union" :parent-ids []
                                       :constraint [:union :int :text]})
              _     (sp/create-entity storage :fn
                                      {:name "rttr-list" :parent-ids []
                                       :element-fn-id int-id})
              _     (sp/create-entity storage :fn
                                      {:name "rttr-fn" :parent-ids []
                                       :constraint [:fn text-id int-id]})
              snap  (ta/rich-types-with-type-rows c)]
          ;; every storage-only type-row is flagged and carries its name
          (is (every? #(some? (get snap %))
                      [:rttr-rec :rttr-union :rttr-list :rttr-fn]))
          (is (true? (:type-row? (get snap :rttr-rec))))
          (is (= "a record row" (:description (get snap :rttr-rec))))))
      (finally (sp/close storage)))))
