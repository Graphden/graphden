(ns graphden.crud.types-api-test
  "Tests for `graphden.crud.types-api` — the bodies behind `/api/types`,
   `/api/types/compatible`, `/api/types/candidates`, `/api/types/usages`,
   plus the shared graph-cache loaders and role / rich-type derivations.

   The pure helpers (`compute-fn-role`, `json->type`, `describe-mismatch`,
   `constraint-contains-type-ref?`, `types-compatible`) need no fixture;
   the rest go through the shared container."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.crud.types-api :as ta]
    [graphden.executor.context :as ctx]
    [graphden.executor.registry.core :as registry]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]))


(use-fixtures :once (setup/create-container-fixture))


(defn- test-ctx
  [storage]
  (ctx/create-context {:storage storage :base-fns {}}))


;; ----------------------------------------------------------------------------
;; The type-API handlers are decomposed into parse → validate → apply
;; `src/` stages, glued in production by the `:types-compatible` /
;; `:types-candidates` / `:types-usages` `:if` graph fn-defs.
;; `validate-*` returns the `{:ok false :error}` rejection directly, so
;; the helpers below are a plain `(or rejection apply)` — the
;; test-level equivalent of the graph.
;; ----------------------------------------------------------------------------

(defn- types-compatible
  [request]
  (let [parsed (ta/parse-types-compatible-request request)]
    (or (ta/validate-types-compatible parsed)
        (ta/apply-types-compatible parsed))))


(defn- types-candidates
  [request ctx]
  (let [parsed (ta/parse-types-candidates-request request)]
    (or (ta/validate-types-candidates parsed)
        (ta/apply-types-candidates parsed ctx))))


(defn- types-usages
  [request ctx]
  (let [parsed (ta/parse-types-usages-request request)]
    (or (ta/validate-types-usages parsed)
        (ta/apply-types-usages parsed ctx))))


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

  (testing "refinement constraints keep string literal values intact"
    ;; JSON can't tell a keyword from a string; a blind decode once
    ;; keywordized constraint values — `[:not= ""]` → `[:not= :]`,
    ;; silently making `:non-empty-text` accept `""`.
    (is (= [:refine :text [:not= ""]]
           (ta/json->type ["refine" "text" ["not=" ""]])))
    (is (= [:refine :text [:= "x"]]
           (ta/json->type ["refine" "text" ["=" "x"]])))
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
;; describe-mismatch — pure
;; ============================================================================

(deftest describe-mismatch-test
  (testing ":any on either side has a dedicated message"
    (is (re-find #"subtype of :any" (ta/describe-mismatch :any :int)))
    (is (re-find #":any is not a subtype" (ta/describe-mismatch :int :any))))

  (testing "refinement expected, plain candidate → constraint-missing message"
    (is (re-find #"lacks the refinement constraint"
                 (ta/describe-mismatch [:refine :int [:> 0]] :int))))

  (testing "two primitives → primitive-subtype message"
    (is (re-find #"not a primitive subtype"
                 (ta/describe-mismatch :int :text))))

  (testing "two fn-types → signature-mismatch message"
    (is (re-find #"function signature mismatch"
                 (ta/describe-mismatch [:fn {:x :int} :int]
                                       [:fn {} :int]))))

  (testing "two refinements with different constraints → constraints-differ"
    (is (re-find #"refinement constraints differ"
                 (ta/describe-mismatch [:refine :int [:> 0]]
                                       [:refine :int [:> 5]]))))

  (testing "mismatched type kinds fall through to the generic message"
    (is (re-find #"is not a subtype of"
                 (ta/describe-mismatch :int [:list :int])))))


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
;; types-compatible
;; ============================================================================

(deftest types-compatible-test
  (testing "missing 'expected' / 'candidate' → {:ok false}"
    (is (false? (:ok (types-compatible {:body {:candidate "int"}}))))
    (is (false? (:ok (types-compatible {:body {:expected "int"}})))))

  (testing "compatible pair → ok true"
    (let [res (types-compatible {:body {:expected "int" :candidate "int"}})]
      (is (true? (:ok res)))
      (is (= :int (:expected res)))))

  (testing "incompatible pair → ok false with a reason"
    (let [res (types-compatible {:body {:expected "int" :candidate "text"}})]
      (is (false? (:ok res)))
      (is (string? (:reason res))))))


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


;; ============================================================================
;; api-rich-types — the HTTP wire-shaping layer over all-rich-types
;; (lean bulk only; the per-fn ?fn= branch died with its one consumer).
;; Finding K: docs/PERF_BUDGETS.md.
;; ============================================================================

(deftest api-rich-types-test
  (let [storage (setup/create-test-storage)
        c (test-ctx storage)]
    (try
      ;; A record type-row carrying a :description — :description is one
      ;; of the fields the lean bulk omits, so it is a deterministic
      ;; probe for the projection without needing a resolved-bindings
      ;; fixture.
      (let [rec  (sp/create-entity storage :fn
                                   {:name "arttr-rec" :parent-ids []
                                    :description "a described row"})
            s1   (setup/create-slot! storage "title" :text)
            s2   (setup/create-slot! storage "count" :int)
            _    (setup/attach-slot! storage (:id rec) (:id s1) 0)
            _    (setup/attach-slot! storage (:id rec) (:id s2) 1)
            full (ta/all-rich-types c)
            lean (ta/api-rich-types c)]
        (testing "lean bulk keeps every fn (same key set as the full snapshot)"
          (is (= (set (keys full)) (set (keys lean)))))
        (testing "lean bulk strips every omitted field from every entry"
          (is (every? (fn [e] (not-any? #(contains? e %) ta/bulk-omitted-fields))
                      (vals lean))))
        (testing "lean bulk retains the fields the chip/strip paint reads"
          (let [e (get lean :arttr-rec)]
            (is (some? e))
            (is (contains? e :return))
            (is (not (contains? e :description))))))
      (finally (sp/close storage)))))


;; ============================================================================
;; types-candidates
;; ============================================================================

(deftest types-candidates-test
  (let [storage (setup/create-test-storage)
        c (test-ctx storage)]
    (try
      (testing "missing 'expected' → {:ok false}"
        (is (false? (:ok (types-candidates {:body {}} c)))))

      (testing "expected :any enumerates candidates; :count matches the vector"
        (let [res (types-candidates {:body {:expected "any"}} c)]
          (is (true? (:ok res)))
          (is (vector? (:candidates res)))
          (is (= (:count res) (count (:candidates res))))))

      (testing "the effects filter keeps only candidates within the allowed set"
        (let [res (types-candidates
                    {:body {:expected "any" :effects []}} c)]
          (is (true? (:ok res)))
          ;; effects=[] → only pure (no-effect) producers survive
          (is (every? #(empty? (:effects %)) (:candidates res)))))

      (testing "the name-prefix filter restricts by fn-name"
        (let [res (types-candidates
                    {:body {:expected "any" :name-prefix "zzz-no-such"}} c)]
          (is (true? (:ok res)))
          (is (zero? (:count res)))))

      ;; A fn-typed slot receives the CALLABLE, not its result — so
      ;; admissibility is "candidate signature ⊆ slot", the rule
      ;; `check-binding!` already applies on write. Comparing the candidate's
      ;; RETURN against the slot (the pre-fix behaviour) answered zero for
      ;; every ordinary fn: the picker read "Compatible · 0" while that very
      ;; bind succeeded through "Other" → "Pick anyway".
      (binding [registry/*rich-types-override* (atom {})]
        (registry/record-rich-types-raw! :tick     {:return :text :args {} :effects #{}})
        (registry/record-rich-types-raw! :counter  {:return :int  :args {} :effects #{}})
        (registry/record-rich-types-raw! :greeter  {:return :text :args {:who :text} :effects #{}})
        (registry/record-rich-types-raw! :producer {:return [:fn {} :text] :args {} :effects #{}})
        (testing "a zero-arity callable slot admits ordinary fns"
          (let [res (ta/apply-types-candidates {:expected [:fn {} :any]} c)
                names (set (map :name (:candidates res)))]
            (is (true? (:ok res)))
            (is (contains? names :tick)
                "a plain `() → text` fn belongs in a `() → any` slot")
            (is (contains? names :greeter)
                "so does one with free args — hof-wrap captures them")
            (is (contains? names :producer)
                "and a callable-producing fn still qualifies")))

        (testing "the callable slot's return type still filters"
          (let [names (set (map :name (:candidates
                                        (ta/apply-types-candidates
                                          {:expected [:fn {} :text]} c))))]
            (is (contains? names :tick))
            (is (not (contains? names :counter))
                "`() → text` rejects a fn returning :int")))

        (testing "a 1-arg callable slot checks the argument contravariantly"
          (let [names (set (map :name (:candidates
                                        (ta/apply-types-candidates
                                          {:expected [:fn {:who :text} :text]} c))))]
            (is (contains? names :greeter)
                "the single-arg callee matches a single-arg slot")
            (is (not (contains? names :counter))
                "a fn returning :int is still out"))))
      (finally (sp/close storage)))))


;; ============================================================================
;; types-usages
;; ============================================================================

(deftest types-usages-test
  (let [storage (setup/create-test-storage)
        c (test-ctx storage)]
    (try
      (testing "missing / invalid type-fn-id → {:ok false}"
        (is (false? (:ok (types-usages {:body {}} c))))
        (is (false? (:ok (types-usages {:body {:type-fn-id "not-a-uuid"}} c)))))

      (testing "a slot typed against the target type-row is reported as a usage"
        (let [int-id  (get setup/primitive-fn-ids :int)
              type-row (sp/create-entity storage :fn
                                         {:name "tu-type" :parent-ids []
                                          :base-fn-id int-id :constraint [:> 0]})
              host    (setup/create-base-fn! storage "tu-host")
              slot    (setup/create-slot! storage "field" (:id type-row))
              _       (setup/attach-slot! storage (:id host) (:id slot) 0)
              res     (types-usages {:body {:type-fn-id (str (:id type-row))}} c)]
          (is (true? (:ok res)))
          (is (pos? (:count res)))
          (is (some #(= :slot-of (:kind %)) (:usages res)))))

      (finally (sp/close storage))))

  ;; Fresh storage — the cached graph from the slot-usage call above
  ;; would otherwise hide these later writes.
  (let [storage (setup/create-test-storage)
        c (test-ctx storage)]
    (try
      (testing "composition plane: children, arg refs, list refs, resolvers"
        (let [target  (setup/create-base-fn! storage "fu-target")
              _child  (setup/create-composed-fn! storage "fu-child" (:id target))
              host    (setup/create-base-fn! storage "fu-host")
              callee  (setup/create-slot! storage "callee" :any)
              items   (setup/create-slot! storage "items" :any)
              resolvd (setup/create-slot! storage "resolved" :any)
              _       (setup/attach-slot! storage (:id host) (:id callee) 0)
              _       (setup/attach-slot! storage (:id host) (:id items) 1)
              _       (setup/attach-slot! storage (:id host) (:id resolvd) 2)
              caller  (setup/create-composed-fn! storage "fu-caller" (:id host))
              _       (sp/create-entity storage :binding
                                        {:fn-id (:id caller) :slot-id (:id callee)
                                         :ref-fn-id (:id target)})
              list-b  (sp/create-entity storage :binding
                                        {:fn-id (:id caller) :slot-id (:id items)
                                         :list-append true})
              _       (sp/create-entity storage :binding-list-item
                                        {:binding-id (:id list-b) :position 0
                                         :ref-fn-id (:id target)})
              _       (sp/create-entity storage :binding-list-item
                                        {:binding-id (:id list-b) :position 1
                                         :ref-fn-id (:id target)})
              _       (sp/create-entity storage :binding
                                        {:fn-id (:id caller) :slot-id (:id resolvd)
                                         :resolver-fn-id (:id target)})
              ;; `fn-id` is the synonym the /api/fns/usages alias sends.
              res     (types-usages {:body {:fn-id (str (:id target))}} c)
              by-kind (group-by :kind (:usages res))]
          (is (true? (:ok res)))
          (is (= ["fu-child"] (map :fn-name (:parent-of by-kind)))
              "a child extending the target is a parent-of usage")
          (is (= [["fu-caller" "callee"] ["fu-caller" "items"]]
                 (sort (map (juxt :fn-name :slot-name) (:ref-of by-kind))))
              "one ref-of per (fn, slot) — the two list items collapse to one")
          (is (= [["fu-caller" "resolved"]]
                 (map (juxt :fn-name :slot-name) (:resolver-of by-kind)))
              "a resolver-fn-id reference is a resolver-of usage")))
      (finally (sp/close storage))))

  (let [storage (setup/create-test-storage)
        c (test-ctx storage)]
    (try
      (testing "a union branch + a binding type-override referencing the target"
        (let [int-id   (get setup/primitive-fn-ids :int)
              type-row (sp/create-entity storage :fn
                                         {:name "tu-target" :parent-ids []
                                          :base-fn-id int-id :constraint [:> 0]})
              ;; a union type-row whose constraint names the target
              _        (sp/create-entity storage :fn
                                         {:name "tu-union" :parent-ids []
                                          :constraint [:union :tu-target :text]})
              ;; a binding whose :type-override-fn-id is the target
              host     (setup/create-base-fn! storage "tu-ovr-host")
              slot     (setup/create-slot! storage "n" :int)
              _        (setup/attach-slot! storage (:id host) (:id slot) 0)
              comp-fn     (setup/create-composed-fn! storage "tu-ovr-comp-fn" (:id host))
              _        (sp/create-entity storage :binding
                                         {:fn-id (:id comp-fn) :slot-id (:id slot)
                                          :type-override-fn-id (:id type-row)})
              res      (types-usages
                         {:body {:type-fn-id (str (:id type-row))}} c)
              kinds    (set (map :kind (:usages res)))]
          (is (true? (:ok res)))
          (is (contains? kinds :union-branch))
          (is (contains? kinds :binding-of))))
      (finally (sp/close storage)))))
