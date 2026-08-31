(ns graphden.crud.entities.seq-test
  "Unit tests for the pure request-parsing / resolution helpers of the
   sequence-binding CRUD — `resolve-sequence-payload` (wire → item
   shape), `requested-insert-pos` (optional :position validation) and
   `find-sequence-binding` (in-memory graph-cache resolution). The
   `apply-*-core` write units stay integration-covered."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.crud.entities.seq :as seq-crud]))


;; =============================================================================
;; resolve-sequence-payload
;; =============================================================================
;;
;; The :ref / :value / invalid-body branches never touch storage — pass
;; nil. (The :ref-name branch queries storage and is covered by the
;; integration suite.)

(deftest ref-branch-parses-uuid
  (testing "a well-formed :ref UUID string becomes :ref-fn-id"
    (let [id (random-uuid)]
      (is (= {:ref-fn-id id}
             (seq-crud/resolve-sequence-payload nil {:ref (str id)}))))))


(deftest ref-branch-rejects-malformed-uuid
  (testing "non-UUID :ref values (untrusted client JSON) raise the
            mapped 400, never a bare IllegalArgumentException"
    (doseq [bad ["not-a-uuid" 123 {:nested true} ""]]
      (let [ex (try (seq-crud/resolve-sequence-payload nil {:ref bad})
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex) (pr-str bad))
        (is (= :validation-error/invalid-uuid (:type (ex-data ex))))
        (is (= bad (:ref (ex-data ex))))))))


(deftest value-branch-restores-keyword-wire-form
  (testing "a \":foo\"-shaped string is the wire form of a keyword
            literal — restored with :literal true"
    (is (= {:value :foo :literal true}
           (seq-crud/resolve-sequence-payload nil {:value ":foo"})))))


(deftest value-branch-passes-plain-values-through
  (testing "ordinary values pass through untouched, no :literal flag"
    (is (= {:value "abc"} (seq-crud/resolve-sequence-payload nil {:value "abc"})))
    (is (= {:value 42} (seq-crud/resolve-sequence-payload nil {:value 42})))
    (is (= {:value nil} (seq-crud/resolve-sequence-payload nil {:value nil})))
    (is (= {:value {"k" 1}} (seq-crud/resolve-sequence-payload nil {:value {"k" 1}})))))


(deftest value-branch-lone-colon-stays-text
  (testing "a bare \":\" (length 1) is plain text, not an empty keyword"
    (is (= {:value ":"} (seq-crud/resolve-sequence-payload nil {:value ":"})))))


(deftest invalid-body-throws
  (testing "a body with none of :ref / :ref-name / :value is rejected"
    (let [ex (try (seq-crud/resolve-sequence-payload nil {:other 1})
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex))
      (is (= :sequence-op/invalid-body (:type (ex-data ex)))))))


;; =============================================================================
;; requested-insert-pos
;; =============================================================================

(def ^:private insert-pos
  #'seq-crud/requested-insert-pos)


(deftest absent-position-means-append-at-end
  (is (nil? (insert-pos {}))))


(deftest whole-positions-accepted
  (testing "non-negative ints — and the whole Doubles JSON decoders
            produce — coerce to a long :pos"
    (is (= {:pos 0} (insert-pos {:position 0})))
    (is (= {:pos 3} (insert-pos {:position 3})))
    (is (= {:pos 3} (insert-pos {:position 3.0})))))


(deftest malformed-positions-rejected
  (testing "negative, fractional and non-numeric values return the
            error shape"
    (doseq [bad [-1 2.5 "3" :three [3]]]
      (is (= {:error "Optional :position must be a non-negative integer"}
             (insert-pos {:position bad}))
          (pr-str bad)))))


;; =============================================================================
;; find-sequence-binding — resolves entirely against the in-memory
;; graph cache, so a ctx carrying a pre-filled :graph-cache atom needs
;; no storage at all.
;; =============================================================================

(def ^:private seq-type-id (random-uuid))
(def ^:private parent-id (random-uuid))
(def ^:private child-id (random-uuid))
(def ^:private slot-id (random-uuid))


(defn- ctx-with-graph
  [graph]
  {:graph-cache (atom (merge {:fns [] :slots [] :fn-slots []
                              :bindings [] :list-items []}
                             graph))})


(def ^:private base-graph
  "parent fn owns one sequence-typed slot; child inherits it."
  {:fns [{:id seq-type-id :name "sequence"}
         {:id parent-id :name "add"}
         {:id child-id :name "add-10" :parent-ids [parent-id]}]
   :slots [{:id slot-id :name "nums" :type-fn-id seq-type-id}]
   :fn-slots [{:fn-id parent-id :slot-id slot-id}]})


(deftest own-sequence-slot-without-binding-yields-synthetic
  (testing "a fn exposing a sequence slot but no binding yet gets the
            synthetic placeholder pinning where the binding will go"
    (is (= {:fn-id parent-id :slot-id slot-id :synthetic true}
           (seq-crud/find-sequence-binding (ctx-with-graph base-graph)
                                           parent-id)))))


(deftest existing-binding-row-is-returned
  (let [binding-row {:id (random-uuid) :fn-id parent-id :slot-id slot-id
                     :list-append true}]
    (is (= binding-row
           (seq-crud/find-sequence-binding
             (ctx-with-graph (assoc base-graph :bindings [binding-row]))
             parent-id)))))


(deftest inherited-slot-resolves-through-parent-chain
  (testing "the child finds the parent's sequence slot through the
            parent-ids walk, but the binding is keyed on the CHILD —
            a parent's own binding row is not returned for the child"
    (let [parent-binding {:id (random-uuid) :fn-id parent-id :slot-id slot-id}]
      (is (= {:fn-id child-id :slot-id slot-id :synthetic true}
             (seq-crud/find-sequence-binding
               (ctx-with-graph (assoc base-graph :bindings [parent-binding]))
               child-id))))))


(deftest no-sequence-slot-yields-nil
  (testing "a fn whose slots are all non-sequence-typed resolves nil"
    (let [other-type (random-uuid)]
      (is (nil? (seq-crud/find-sequence-binding
                  (ctx-with-graph
                    (assoc base-graph
                           :slots [{:id slot-id :name "n"
                                    :type-fn-id other-type}]))
                  parent-id))))))


(deftest missing-sequence-type-fn-yields-nil
  (testing "without a fn named \"sequence\" in the graph no slot can
            qualify"
    (is (nil? (seq-crud/find-sequence-binding
                (ctx-with-graph (update base-graph :fns
                                        (fn [fns]
                                          (filterv #(not= seq-type-id (:id %))
                                                   fns))))
                parent-id)))))


(deftest parent-cycle-terminates
  (testing "a parent-ids cycle is walked once per fn (seen set) — no
            hang, and the slot still resolves"
    (let [a (random-uuid)
          b (random-uuid)
          graph {:fns [{:id seq-type-id :name "sequence"}
                       {:id a :name "a" :parent-ids [b]}
                       {:id b :name "b" :parent-ids [a]}]
                 :slots [{:id slot-id :name "nums" :type-fn-id seq-type-id}]
                 :fn-slots [{:fn-id b :slot-id slot-id}]}]
      (is (= {:fn-id a :slot-id slot-id :synthetic true}
             (seq-crud/find-sequence-binding (ctx-with-graph graph) a))))))
