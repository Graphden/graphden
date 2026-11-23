(ns graphden.input.source-list-scope-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.input.list-builder :as builder]
    [graphden.input.list-records :as records]
    [graphden.input.list-signature :as signature]
    [graphden.input.source-scope :as scope]
    [graphden.packages.records.ids :as ids]))


(defn- fixture
  []
  (let [[owner root reader target] (repeatedly 4 random-uuid)
        origin (builder/origin owner target)
        fragment (merge origin
                        (builder/parse-fragment (:binding-id origin)
                                                [:list-builder {:items [[:value {:ref :reader :input [:value :data]}]
                                                                        [:ref :reader]] :tail :closed}]
                                                {:reader reader} owner))
        field {:type [:list :any] :type-id (ids/primitive-fn-id :sequence)}
        rows (records/materialize-chain [fragment] field {})
        trace (assoc (builder/link-fragments [fragment]) :fragments [fragment] :field field :records rows)
        entry (signature/entry owner {} trace)
        fragment (:list-fragment entry)
        builder (:builder-id fragment)
        builder-use [root [(:binding-id fragment)]]
        from [[root []] [target]]
        link {:from from :to builder-use :binding-id (:binding-id fragment) :target-fn-id builder :mode :value}
        grouped (group-by :kind rows)
        row (some #(when (= (:id (get-in fragment [:items 1])) (:id %)) %) (:input-binding grouped))]
    {:snapshot {:source-list-fragments {builder fragment}
                :fns (into {} (map (juxt :id identity)) (:fn grouped))
                :bindings (into {} (map (juxt :id identity)) (:input-binding grouped))
                :members (group-by :fn-id (:fn-slot grouped))}
     :graph {:scopes {[] {:fn-id root :declaration-order [owner root]
                          :bindings {from {:binding-id (:binding-id fragment) :origin-fn-id owner
                                           :expression {:kind :ref :fn-id builder}}}}
                      (second builder-use) {:fn-id builder}}
             :dependencies [link]}
     :builder-use builder-use :row row :builder builder :fragment fragment}))


(deftest generated-reference-keeps-its-source-position-and-attached-use
  (let [{:keys [snapshot graph builder-use row builder fragment]} (fixture)
        check (fn [g s r] (#'scope/list-reference-origin g s builder-use r))
        fragment-path [:source-list-fragments builder]]
    (is (= (ids/binding-list-item-id (:binding-id fragment) 1) (:id row)))
    (is (not= (ids/binding-id builder (first (:target-path row))) (:id row)))
    (is (check graph snapshot row))
    (doseq [tampered [(update snapshot :source-list-fragments dissoc builder)
                      (assoc-in snapshot (conj fragment-path :items 1 :position) 0)
                      (assoc-in snapshot (conj fragment-path :items 1 :id) (random-uuid))
                      (assoc-in snapshot (conj fragment-path :items 1 :fn-id) (random-uuid))
                      (assoc-in snapshot (conj fragment-path :owner-id) (random-uuid))
                      (assoc-in snapshot (conj fragment-path :builder-id) (random-uuid))
                      (assoc-in snapshot (conj fragment-path :target-id) (random-uuid))
                      (assoc-in snapshot (conj fragment-path :scope-path) [(random-uuid)])]]
      (is (not (check graph tampered row))))
    (doseq [detached [(assoc graph :dependencies [])
                      (assoc-in graph [:scopes [] :declaration-order] [(first builder-use)])
                      (assoc-in graph [:scopes (second builder-use) :fn-id] (random-uuid))]]
      (is (not (check detached snapshot row))))
    (let [input (get-in snapshot [:fns builder :input-type-fn-id])
          index (first (keep-indexed #(when (= (first (:target-path row)) (:slot-id %2)) %1)
                                     (get-in snapshot [:members input])))
          shifted (assoc-in snapshot [:members input index :position] 0)
          literal-ref (assoc row :id (get-in fragment [:items 0 :id])
                             :target-path [(ids/slot-id builder :item-0)])]
      (is (not (check graph shifted row)))
      (is (not (check graph snapshot literal-ref))))))


(deftest certificate-formulas-retain-the-preceding-list-link
  (let [{:keys [snapshot graph builder-use row builder fragment]} (fixture)
        prior (assoc (first (:dependencies graph))
                     :origin-fn-id (:owner-id fragment) :name :values
                     :source-list-fragment (dissoc fragment :items :tail))
        edge {:from [builder-use (:target-path row)] :origin-fn-id builder :name :item-1
              :binding-id (:id row) :target-fn-id (:ref-fn-id row)
              :source-list-operand (#'scope/list-reference-origin graph snapshot builder-use row)}]
    (is (signature/source-link? prior))
    (is (signature/source-operand? prior edge))
    (doseq [changed [(assoc-in edge [:source-list-operand :position] 0)
                     (assoc-in edge [:source-list-operand :binding-id] (random-uuid))
                     (assoc-in edge [:source-list-operand :item-id] (random-uuid))
                     (assoc-in edge [:source-list-operand :ref-fn-id] (random-uuid))
                     (assoc-in edge [:from 0 1] [])]]
      (is (not (signature/source-operand? prior changed))))
    (is (not (signature/source-operand? nil edge)))
    (is (not (signature/source-operand? (dissoc prior :source-list-fragment) edge)))
    (is (not (signature/source-link? (assoc-in prior [:source-list-fragment :scope-path] [(random-uuid)]))))))


(deftest a-generated-operand-cannot-fall-back-to-a-new-direct-binding-id
  (let [{:keys [snapshot graph builder-use row builder]} (fixture)
        field (first (:target-path row))
        check (fn [id]
                (let [changed (assoc row :id id)
                      prefix (conj (second builder-use) id)
                      from [builder-use [field]]
                      bound {:binding-id id :origin-fn-id builder
                             :expression {:kind :ref :fn-id (:ref-fn-id row)}}
                      graph (-> graph
                                (update :dependencies conj {:from from :to [(first builder-use) prefix] :binding-id id
                                                            :target-fn-id (:ref-fn-id row) :mode :value})
                                (assoc :public-input {:bindings {from bound}
                                                      :scope-contracts {builder-use {:fields [{:id field :name :item-1}]}}}))
                      snapshot (-> snapshot (update :bindings dissoc (:id row)) (assoc-in [:bindings id] changed))]
                  (try (#'scope/checked-edge graph snapshot (first builder-use) prefix)
                       (catch clojure.lang.ExceptionInfo failure (ex-data failure)))))]
    (is (= (:id row) (:binding-id (check (:id row)))))
    (is (= :unsupported-reference-use (:code (check (ids/binding-id builder field)))))
    (is (= :unsupported-reference-use (:code (check (random-uuid)))))))
