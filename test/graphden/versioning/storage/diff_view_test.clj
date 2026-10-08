(ns ^:integration graphden.versioning.storage.diff-view-test
  "Tests for `graphden.versioning.storage.diff-view` — the grouped
   display model over `diff-branches` (diff v2): per-owning-fn groups,
   slot-name labels, field-level before/after pairs, ref-value name
   resolution and one-sided previews.

   Storage stack mirrors `versioning.storage.core-test`."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.schema.graph.schema :as gds]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.schema.traits.schema :as vts]
    [graphden.schema.versioned.schema :as vds]
    [graphden.storage.postgres.core :as pg]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as th]
    [graphden.versioning.storage.core :as vs]
    [graphden.versioning.storage.diff-view :as dv]))


(def ^:dynamic *container* nil)


(use-fixtures :once (th/create-container-fixture #'*container*))
(use-fixtures :each (th/create-clean-db-fixture #'*container*))


(defn- base-storage
  []
  (let [schema (-> (mds/create-builder)
                   (gds/extend-builder)
                   (vds/extend-builder)
                   (vts/extend-builder)
                   (ds/build))]
    (-> (pg/create-storage (th/get-container-config *container*))
        (sp/initialize-with-cleanup! schema))))


(deftest diff-branches-view-test
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [main-id (vs/current-branch-id v)
            ;; Seed on main: owner fn + a slot + a bound value.
            type-fn (sp/create-entity v :fn {:name "dv-type" :parent-ids []
                                             :description "t"})
            owner (sp/create-entity v :fn {:name "dv-owner" :parent-ids []
                                           :description "d0"})
            slot  (sp/create-entity v :slot {:name "port"
                                             :type-fn-id (:id type-fn)})
            _     (sp/create-entity v :fn-slot {:fn-id (:id owner)
                                                :slot-id (:id slot)
                                                :position 0})
            bnd   (sp/create-entity v :binding {:fn-id (:id owner)
                                                :slot-id (:id slot)
                                                :value 8080})
            feature (vs/create-branch! v "dv-feature")
            vf      (vs/switch-branch v (:id feature))
            ;; On the feature branch: retune the binding, touch the fn's
            ;; description, and add a brand-new fn.
            _ (sp/update-entity vf :binding (:id bnd) {:value 9090})
            _ (sp/update-entity vf :fn (:id owner)
                                {:description "d1-on-feature"})
            fresh (sp/create-entity vf :fn {:name "dv-fresh" :parent-ids []
                                            :description "hi"})
            {:keys [groups] :as view} (dv/diff-branches-view
                                        base (:id feature) main-id)
            by-fn-id (into {} (map (juxt :fn-id identity)) groups)
            owner-g (get by-fn-id (str (:id owner)))
            fresh-g (get by-fn-id (str (:id fresh)))]

        (testing "envelope carries branch ids + total row count"
          (is (= (:id feature) (:source-branch-id view)))
          (is (= main-id (:target-branch-id view)))
          (is (pos? (:count view))))

        (testing "modified owner fn groups its own row and its binding"
          (is (some? owner-g))
          (is (= :modified (:change owner-g)))
          (is (= "dv-owner" (:fn-name owner-g)))
          (is (= ":dv-owner" (:fn-label owner-g)))
          (is (false? (:branch-local? owner-g)))
          (let [entries (:entries owner-g)
                fn-e (first (filter #(= :fn (:entity-name %)) entries))
                b-e  (first (filter #(= :binding (:entity-name %)) entries))]
            (is (= :fn (:entity-name (first entries)))
                "fn's own row sorts first in the group")
            (is (some #(= "description" (:field %)) (:fields fn-e)))
            (is (= "port" (:slot-name b-e)) "binding labeled by its slot")
            (is (= (str (:id slot)) (:slot-id b-e)))
            (is (= (str (:id bnd)) (:binding-id b-e)))
            (let [vf-field (first (filter #(= "value" (:field %))
                                          (:fields b-e)))]
              (is (some? vf-field))
              (is (= "9090" (:source vf-field)))
              (is (= "8080" (:target vf-field))))))

        (testing "a fn present only on the source is its own added group"
          (is (some? fresh-g))
          (is (= :added-in-source (:change fresh-g)))
          (is (= "dv-fresh" (:fn-name fresh-g)))
          (let [fn-e (first (filter #(= :fn (:entity-name %))
                                    (:entries fresh-g)))]
            (is (= :added-in-source (:change fn-e)))
            (is (nil? (:fields fn-e)) "one-sided rows carry a preview, not fields")
            (is (= "“hi”" (:preview fn-e)))))

        (testing "diffing a branch against itself yields no groups"
          (let [self (dv/diff-branches-view base (:id feature) (:id feature))]
            (is (zero? (:count self)))
            (is (empty? (:groups self))))))
      (finally (sp/close base)))))


(deftest sibling-tutorial-merge-sandbox-cleans-all-identities-test
  (let [base (base-storage)
        v (vs/wrap-with-versioning base)
        type-fn (sp/create-entity v :fn {:name "existing-public-type" :parent-ids []})
        counts #(into {} (map (fn [entity] [entity (count (sp/query-entities base entity {}))]))
                      [:branch :branch-merge :fn :fn-version :slot :fn-slot :fn-slot-version
                       :binding :binding-version])
        before (counts)]
    (try
      ;; Repeat the exact names, as a reader retaking the same lesson does.
      (dotimes [_ 2]
        (let [sandbox (vs/create-branch! v "tutorial-sandbox")
              common (vs/switch-branch v (:id sandbox))
              seed (sp/create-entity common :fn {:name "branch-demo" :parent-ids []})
              slot (sp/create-entity common :slot {:name "value" :type-fn-id (:id type-fn)})
              _ (sp/create-entity common :fn-slot {:fn-id (:id seed) :slot-id (:id slot) :position 0})
              binding (sp/create-entity common :binding {:fn-id (:id seed) :slot-id (:id slot) :value 1})
              source (vs/create-branch! common "tutorial-branch")
              target (vs/create-branch! common "tutorial-merge-target")
              source-view (vs/switch-branch v (:id source))
              target-view (vs/switch-branch v (:id target))]
          (sp/update-entity source-view :binding (:id binding) {:value 2})
          (sp/update-entity source-view :fn (:id seed) {:description "changed field"})
          (sp/create-entity source-view :fn {:name "branch-added" :parent-ids [(:id seed)]})
          (vs/merge-branch! target-view (:id source))
          (is (= 2 (:value (sp/read-entity target-view :binding (:id binding)))))
          (is (= "changed field" (:description (sp/read-entity target-view :fn (:id seed)))))
          (sp/update-entity source-view :binding (:id binding) {:value 3})
          (vs/merge-branch! target-view (:id source))
          (is (= 3 (:value (sp/read-entity target-view :binding (:id binding)))))
          ;; The target has no children; removing it releases the source's
          ;; merge references. The common parent then has neither child.
          (doseq [branch [target source sandbox]]
            (is (true? (vs/delete-branch! v (:id branch)))))
          (is (= before (counts))
              "Actual identity counts return to baseline, including quota-counted fn rows")))
      (testing "exact UUID revival preserves identity; a reused fn name creates a new identity"
        (let [original (sp/create-entity v :fn {:name "revivable-tutorial-fn" :parent-ids []})]
          (binding [vs/*tombstone-delete?* true]
            (sp/delete-entity v :fn (:id original)))
          (is (nil? (sp/read-entity v :fn (:id original))))
          (is (true? (vs/revive-entity! v :fn (:id original))))
          (is (= (:id original) (:id (sp/read-entity v :fn (:id original)))))
          (is (= (inc (:fn before)) (:fn (counts))))
          (binding [vs/*tombstone-delete?* true]
            (sp/delete-entity v :fn (:id original)))
          (let [replacement (sp/create-entity v :fn {:name "revivable-tutorial-fn" :parent-ids []})]
            (is (not= (:id original) (:id replacement)))
            (is (= "revivable-tutorial-fn" (:name (sp/read-entity v :fn (:id replacement)))))
            (is (= (+ 2 (:fn before)) (:fn (counts))))
            ;; Names are labels, unlike binding/fn-slot natural keys.
            ;; Cleanup retains both exact UUIDs, including the tombstone.
            (sp/delete-entity v :fn (:id replacement))
            (sp/delete-entity v :fn (:id original))
            (is (= before (counts))))))
      (finally (sp/close base)))))


(deftest diff-branches-view-structural-test
  ;; STRUCTURAL diff kinds — the graph's shape changing, not a value:
  ;; a new slot exposed on the fn (fn-slot), a new binding that REFERS
  ;; to another fn (ref-fn-id resolved to its name), and a list-item
  ;; edit under a list binding. These are the entries the modal labels
  ;; "slot …", "arg … ref → :name" and "item N of …".
  (let [base (base-storage)
        v    (vs/wrap-with-versioning base)]
    (try
      (let [main-id (vs/current-branch-id v)
            type-fn (sp/create-entity v :fn {:name "dvs-ref-target" :parent-ids []
                                             :description "t"})
            owner (sp/create-entity v :fn {:name "dvs-owner" :parent-ids []
                                           :description "d"})
            ;; a LIST slot bound on main, with one item
            slot-c (sp/create-entity v :slot {:name "items"
                                              :type-fn-id (:id type-fn)})
            _ (sp/create-entity v :fn-slot {:fn-id (:id owner)
                                            :slot-id (:id slot-c)
                                            :position 0})
            lb (sp/create-entity v :binding {:fn-id (:id owner)
                                             :slot-id (:id slot-c)
                                             :list-append true})
            item (sp/create-entity v :binding-list-item {:binding-id (:id lb)
                                                         :position 0
                                                         :value 1})
            feature (vs/create-branch! v "dvs-feature")
            vf      (vs/switch-branch v (:id feature))
            ;; STRUCTURE grows on the branch: a new slot + a ref binding
            slot-b (sp/create-entity vf :slot {:name "handler"
                                               :type-fn-id (:id type-fn)})
            _ (sp/create-entity vf :fn-slot {:fn-id (:id owner)
                                             :slot-id (:id slot-b)
                                             :position 1})
            _ (sp/create-entity vf :binding {:fn-id (:id owner)
                                             :slot-id (:id slot-b)
                                             :ref-fn-id (:id type-fn)})
            ;; and the list ITEM is retuned
            _ (sp/update-entity vf :binding-list-item (:id item) {:value 2})
            {:keys [groups]} (dv/diff-branches-view base (:id feature) main-id)
            owner-g (first (filter #(= (str (:id owner)) (:fn-id %)) groups))
            entries (:entries owner-g)
            by-kind (group-by :entity-name entries)]

        (testing "everything groups under the one owning fn"
          (is (some? owner-g))
          (is (= :modified (:change owner-g))
              "the fn itself didn't change — only its parts moved"))

        (testing "a slot newly exposed on the branch → fn-slot entry"
          (let [fs (first (get by-kind :fn-slot))]
            (is (some? fs))
            (is (= :added-in-source (:change fs)))
            (is (= "handler" (:slot-name fs)))
            (is (= (str (:id slot-b)) (:slot-id fs)))
            (is (= "at position 1" (:preview fs)))))

        (testing "a new ref binding shows the referenced fn by NAME"
          (let [b (first (get by-kind :binding))]
            (is (some? b))
            (is (= :added-in-source (:change b)))
            (is (= "handler" (:slot-name b)))
            (is (= "ref → :dvs-ref-target" (:preview b)))))

        (testing "a list-item edit → item entry with position + value pair"
          (let [it (first (get by-kind :binding-list-item))]
            (is (some? it))
            (is (= :modified (:change it)))
            (is (= "items" (:slot-name it)) "labels via its owning binding's slot")
            (is (= (str (:id slot-c)) (:slot-id it)))
            (is (= (str (:id lb)) (:binding-id it)))
            (is (= (str (:id item)) (:item-id it)))
            (is (zero? (:position it)))
            (let [vf-field (first (filter #(= "value" (:field %)) (:fields it)))]
              (is (= "2" (:source vf-field)))
              (is (= "1" (:target vf-field)))))))
      (finally (sp/close base)))))
