(ns graphden.input.migration-renames-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.input.migration :as migration]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]))


(defn- fixture
  []
  (let [defs [{:namespace "fixture" :name :encode :args {:value :text} :return-type :text}
              {:namespace "fixture" :name :alias :parent :encode :args {:value {:as :body :type :text}}}]
        prepared (parse/prepare-module defs {} {})
        source (update-vals (group-by :kind (parse/parse-prepared-module prepared))
                            #(mapv (fn [row] (dissoc row :kind)) %))
        owner (ids/fn-id "fixture" :alias)
        alias-id (ids/slot-id owner :body)
        member (some #(when (= alias-id (:slot-id %)) %) (:fn-slot source))
        binding (first (:binding source))
        stamps {:branch-id (random-uuid) :change-revision 3 :change-id (random-uuid)
                :created-at #inst "2026-11-21T00:00:00"}
        member-version (merge stamps member {:id (random-uuid) :fn-slot-id (:id member)})
        binding-version (merge stamps binding {:id (random-uuid) :binding-id (:id binding)})]
    (assoc source :fn-slot-version [member-version] :binding-version [binding-version])))


(deftest pure-scalar-rename-keeps-its-original-binding-and-historical-stamps
  (let [source (fixture)
        old (first (:binding source))
        old-version (first (:binding-version source))
        {:keys [exceptions additions] :as plan} (migration/plan source)
        [row] (:input-binding additions)
        [version] (:input-binding-version additions)
        alias-id (ids/slot-id (:fn-id old) :body)
        path-keys [:scope-path :target-path :projection-scope-path :projection-path :expression-kind]]
    (is (empty? exceptions) (pr-str exceptions))
    (is (= {:scope-path [] :target-path [(:slot-id old)]
            :projection-scope-path [] :projection-path [alias-id] :expression-kind "projection"}
           (select-keys row path-keys) (select-keys version path-keys)))
    (is (= (:id old) (:id row) (:input-binding-id version)
           (migration/binding-origin plan [(:fn-id old) []] [(:slot-id old)])))
    (is (= (select-keys old-version [:id :branch-id :change-revision :change-id :created-at])
           (select-keys version [:id :branch-id :change-revision :change-id :created-at])))))


(deftest projection-does-not-guess-on-ambiguous-history-or-a-value-expression
  (let [source (fixture)
        different-branch (assoc-in source [:binding-version 0 :branch-id] (random-uuid))
        value-form (update-in source [:binding-version 0] assoc :value-present true :value nil)
        type-change (assoc-in source [:binding-version 0 :type-override-fn-id] (ids/primitive-fn-id :int))
        removed-membership (assoc-in source [:fn-slot-version 0 :deleted-at] #inst "2026-11-21T00:01:00")]
    (doseq [blocked [different-branch value-form type-change removed-membership]]
      (let [{:keys [exceptions additions]} (migration/plan blocked)]
        (is (some #(= :rename-projection-required (:code %)) exceptions))
        (is (nil? (get-in additions [:input-binding 0 :projection-path])))))))
