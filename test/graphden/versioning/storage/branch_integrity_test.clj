(ns ^:integration graphden.versioning.storage.branch-integrity-test
  "Per-branch resolved-view uniqueness ACROSS branches (a write shows on
   every branch forked off the writer), merge-time uniqueness, the
   revival's collision checks, and what a branch delete reclaims."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.crud.secrets :as secrets]
    [graphden.schema.graph.schema :as gds]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.schema.traits.schema :as vts]
    [graphden.schema.versioned.schema :as vds]
    [graphden.storage.postgres.core :as pg]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as th]
    [graphden.versioning.storage.core :as vs]))


(def ^:dynamic *container* nil)


(use-fixtures :once (th/create-container-fixture #'*container*))
(use-fixtures :each (th/create-clean-db-fixture #'*container*))


(defn- with-versioned
  "Call `(f base v)` — Postgres and versioned over it, on main; closes the
   backend."
  [f]
  (let [base (-> (pg/create-storage (th/get-container-config *container*))
                 (sp/initialize-with-cleanup! (-> (mds/create-builder) (gds/extend-builder)
                                                  (vds/extend-builder) (vts/extend-builder)
                                                  (ds/build))))]
    (try (f base (vs/wrap-with-versioning base))
         (finally (sp/close base)))))


(defn- fork
  [v branch-name]
  (vs/switch-branch v (:id (vs/create-branch! v branch-name))))


(defn- refusal
  "The `:type` a throwing `f` refuses with, nil when it went through."
  [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))


(defn- make-list-binding!
  [v label]
  (let [owner (sp/create-entity v :fn {:name (str label "-fn") :parent-ids [] :description "h"})
        slot (sp/create-entity v :slot {:name (str label "-xs") :type-fn-id (:id owner)})]
    (sp/create-entity v :fn-slot {:fn-id (:id owner) :slot-id (:id slot) :position 0})
    (sp/create-entity v :binding {:fn-id (:id owner) :slot-id (:id slot) :list-append true})))


(defn- fn-fields
  [v]
  {:parent-ids [] :description (str "on " (vs/current-branch-id v))})


;; ============================================================================
;; An ancestor's write lands on its descendants
;; ============================================================================

(deftest a-name-a-descendant-already-uses-is-refused-on-the-ancestor
  ;; The branch created `dup` first; main creating `dup` afterwards would
  ;; leave the branch with two live fns of that name (the check used to
  ;; look only at the writer's own view).
  (with-versioned (fn [_base v]
                    (let [child (fork v "has-dup")]
                      (sp/create-entity child :fn (assoc (fn-fields child) :name "dup"))
                      (let [ex (try (sp/create-entity v :fn (assoc (fn-fields v) :name "dup")) nil
                                    (catch clojure.lang.ExceptionInfo e e))]
                        (is (= :constraint-violation/fn-name-collision (:type (ex-data ex))))
                        (is (= (vs/current-branch-id child) (:descendant-branch-id (ex-data ex))))
                        (is (re-find #"on branch \"has-dup\"" (ex-message ex))
                            "the message names the branch that holds the name"))
                      (is (= 1 (count (sp/query-entities child :fn {:name "dup"}))))))))


(deftest a-rename-onto-a-descendants-name-is-refused
  (with-versioned (fn [_base v]
                    (let [child (fork v "renames")
                          f (sp/create-entity v :fn (assoc (fn-fields v) :name "before"))]
                      (sp/create-entity child :fn (assoc (fn-fields child) :name "after"))
                      (is (= :constraint-violation/fn-name-collision
                             (refusal #(sp/update-entity v :fn (:id f) {:name "after"}))))))))


(deftest a-descendant-that-renamed-the-row-itself-does-not-collide
  ;; The branch renamed main's `a` to `b` on its own; main renaming `a` to
  ;; `b` too surfaces nowhere new — the branch keeps its own version.
  (with-versioned (fn [_base v]
                    (let [f (sp/create-entity v :fn (assoc (fn-fields v) :name "a"))
                          child (fork v "own-rename")]
                      (sp/update-entity child :fn (:id f) {:name "b"})
                      (is (nil? (refusal #(sp/update-entity v :fn (:id f) {:name "b"}))))))))


(deftest unrelated-branches-still-diverge-freely
  (with-versioned (fn [_base v]
                    (let [a (fork v "sib-a")
                          b (fork v "sib-b")]
                      (sp/create-entity a :fn (assoc (fn-fields a) :name "same"))
                      (is (nil? (refusal #(sp/create-entity b :fn (assoc (fn-fields b) :name "same")))))))))


(deftest an-override-path-a-descendant-already-uses-is-refused-on-the-ancestor
  (with-versioned (fn [_base v]
                    (let [child (fork v "has-path")]
                      (sp/create-entity child :resource-override {:path "x.css" :content "a{}"})
                      (is (= :constraint-violation/resource-override-path-collision
                             (refusal #(sp/create-entity v :resource-override
                                                         {:path "x.css" :content "b{}"}))))))))


(deftest an-ancestor-list-append-is-never-refused-and-the-merge-asks-for-the-fix
  ;; List positions take the other side of the trade-off
  ;; (docs/CONSTRAINTS.md): refusing main's append because some branch
  ;; holds that position would leave main's author no way out — they may
  ;; not even see that branch. The branch shows both items until its author
  ;; moves one, and merging it back is refused until then.
  (with-versioned (fn [_base v]
                    (let [b (make-list-binding! v "anc")
                          child (fork v "li-first")
                          own (sp/create-entity child :binding-list-item
                                                {:binding-id (:id b) :position 0 :value "child"})]
                      (is (nil? (refusal #(sp/create-entity v :binding-list-item
                                                            {:binding-id (:id b) :position 0 :value "main"})))
                          "main's append goes through")
                      (is (= #{"child" "main"}
                             (set (map :value (sp/query-entities child :binding-list-item {:binding-id (:id b)}))))
                          "the branch sees both")
                      (is (= :constraint-violation/position-collision
                             (refusal #(vs/merge-branch! v (vs/current-branch-id child))))
                          "the merge refuses the duplicate position")
                      (sp/update-entity child :binding-list-item (:id own) {:position 1})
                      (is (nil? (refusal #(vs/merge-branch! v (vs/current-branch-id child))))
                          "once the branch moved its item, the merge goes through")))))


;; ============================================================================
;; Merge
;; ============================================================================

(deftest a-merge-surfacing-a-taken-override-path-is-refused
  (with-versioned (fn [_base v]
                    (let [a (fork v "ro-a")
                          b (fork v "ro-b")]
                      (sp/create-entity a :resource-override {:path "y.css" :content "a{}"})
                      (sp/create-entity b :resource-override {:path "y.css" :content "b{}"})
                      (is (= :constraint-violation/resource-override-path-collision
                             (refusal #(vs/merge-branch! a (vs/current-branch-id b)))))
                      (is (= 1 (count (sp/query-entities a :resource-override {:path "y.css"})))
                          "the refused merge left the target's view as it was")))))


;; ============================================================================
;; Revival
;; ============================================================================

(deftest a-revival-onto-a-taken-position-is-refused
  (with-versioned (fn [_base v]
                    (let [b (make-list-binding! v "rv")
                          gone (sp/create-entity v :binding-list-item {:binding-id (:id b) :position 0 :value "old"})]
                      (binding [vs/*tombstone-delete?* true]
                        (sp/delete-entity v :binding-list-item (:id gone)))
                      (sp/create-entity v :binding-list-item {:binding-id (:id b) :position 0 :value "new"})
                      (is (= :constraint-violation/position-collision
                             (refusal #(vs/revive-entity! v :binding-list-item (:id gone)))))
                      (is (= ["new"] (mapv :value (sp/query-entities v :binding-list-item {:binding-id (:id b)}))))))))


(deftest a-revival-onto-a-taken-override-path-is-refused
  (with-versioned (fn [_base v]
                    (let [gone (sp/create-entity v :resource-override {:path "z.css" :content "old"})]
                      (binding [vs/*tombstone-delete?* true]
                        (sp/delete-entity v :resource-override (:id gone)))
                      (sp/create-entity v :resource-override {:path "z.css" :content "new"})
                      (is (= :constraint-violation/resource-override-path-collision
                             (refusal #(vs/revive-entity! v :resource-override (:id gone)))))))))


;; ============================================================================
;; Branch delete
;; ============================================================================

(deftest a-branch-delete-purges-the-rows-it-created
  ;; Rows created on the branch have no version anywhere once its versions
  ;; go. They used to stay — resolving nowhere, counted by the tenancy quota
  ;; (which counts identity rows), never reclaimed.
  (with-versioned (fn [base v]
                    (let [kept (sp/create-entity v :fn (assoc (fn-fields v) :name "kept"))
                          vb (fork v "scratch")
                          ret (sp/create-entity vb :fn (assoc (fn-fields vb) :name "made-type"))
                          made (sp/create-entity vb :fn (assoc (fn-fields vb) :name "made"
                                                               :return-type-fn-id (:id ret)))
                          slot (sp/create-entity vb :slot {:name "xs" :type-fn-id (:id kept)})
                          fs (sp/create-entity vb :fn-slot {:fn-id (:id made) :slot-id (:id slot) :position 0})
                          b (sp/create-entity vb :binding {:fn-id (:id made) :slot-id (:id slot) :list-append true})
                          item (sp/create-entity vb :binding-list-item {:binding-id (:id b) :position 0 :value 1})
                          raw (fn [entity id] (seq (sp/query-entities base entity {:id id})))]
                      (sp/update-entity vb :fn (:id kept) {:description "edited on the branch"})
                      (is (true? (vs/delete-branch! v (vs/current-branch-id vb))))
                      (testing "every identity the branch created is gone"
                        (is (nil? (raw :fn (:id made))))
                        (is (nil? (raw :fn (:id ret))) "including one only the branch's own fn referenced")
                        (is (nil? (raw :fn-slot (:id fs))))
                        (is (nil? (raw :binding (:id b))))
                        (is (nil? (raw :binding-list-item (:id item)))))
                      (testing "a row main still versions stays, with main's content"
                        (is (raw :fn (:id kept)))
                        (is (= (:description (fn-fields v)) (:description (sp/read-entity v :fn (:id kept))))))))))


(deftest a-branch-delete-hands-back-its-secret-bindings
  (with-versioned (fn [_base v]
                    (let [resolver (sp/create-entity v :fn (assoc (fn-fields v) :name "vault-get"))
                          owner (sp/create-entity v :fn (assoc (fn-fields v) :name "db"))
                          slot (sp/create-entity v :slot {:name "pw" :type-fn-id (:id owner)})
                          vb (fork v "secrets")
                          _ (sp/create-entity vb :binding {:fn-id (:id owner) :slot-id (:id slot)
                                                           :value "branch/pw" :resolver-fn-id (:id resolver)})
                          handed (atom nil)]
                      (vs/delete-branch! v (vs/current-branch-id vb)
                                         {:reclaim-secrets! #(reset! handed %)})
                      (is (= #{{:path "branch/pw" :org nil}}
                             (secrets/secret-refs @handed (constantly nil)))
                          "the caller gets the branch's secret bindings to reclaim the vault values")))))
