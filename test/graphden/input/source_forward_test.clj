(ns graphden.input.source-forward-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.input.analysis :as analysis]
    [graphden.input.context :as context]
    [graphden.input.scoped-source :as scoped-source]
    [graphden.input.source :as source]
    [graphden.input.source-forward :as forward]
    [graphden.input.source-scope :as scope]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]))


(defn- fixture
  [args]
  (let [forms [{:name :combine :args {:left {:type :any} :right {:type :any}} :return-type :any}
               {:name :encode :args {:data {:type :text :required true}} :return-type :text}
               {:name :encode-body :parent :encode}
               {:name :response :parent :combine :args args}]
        declarations (mapv #(assoc % :namespace "fixture") forms)
        prepared (parse/prepare-module declarations {} {})]
    [prepared (last (:definitions (source/plan-module declarations prepared)))]))


(defn- rejection
  [prepared entry]
  (try
    (forward/verify-forwarded-field prepared entry :data)
    (catch clojure.lang.ExceptionInfo failure (:code (ex-data failure)))))


(deftest forwarded-field-keeps-its-own-id-and-the-exact-reference-use
  (let [[prepared entry] (fixture {:left :encode-body :data {:type :text}})
        owner (ids/fn-id "fixture" :response)
        source-id (ids/slot-id owner :data)
        target-id (ids/slot-id (ids/fn-id "fixture" :encode) :data)
        edge (ids/binding-id owner (ids/slot-id (ids/fn-id "fixture" :combine) :left))
        proof (forward/verify-forwarded-field prepared entry :data)
        tampered (update-in entry [:legacy :arguments]
                            (fn [arguments]
                              (mapv #(cond-> % (:source-slot-id %)
                                             (assoc :source-slot-id source-id)) arguments)))]
    (is (= :blocked (:status entry)))
    (is (= source-id (:field-id proof)))
    (is (= (ids/fn-slot-id owner source-id) (:membership-id proof)))
    (is (= (ids/fn-id "fixture" :encode-body) (:target-fn-id proof)))
    (is (= [:text :text] ((juxt :source-type :target-type) proof)))
    (is (= [[owner []] [source-id]] (:source-address proof)))
    (is (= [[owner [edge]] [target-id]] (:target-address proof)))
    (is (= proof (forward/verify-forwarded-field prepared entry :data)))
    (is (= :unproved-field-origin (rejection prepared tampered)))))


(deftest forwarding-does-not-conflate-two-uses-of-one-function
  (let [[prepared entry] (fixture {:left :encode-body :right :encode-body :data {:type :text}})]
    (is (= :nonunique-reference-use (rejection prepared entry)))))


(defn- checked-snapshot
  [{:keys [owner-id target-fn-id field-id target-address]}]
  (let [[[_ [edge]] [target-id]] target-address
        parent (ids/fn-id "fixture" :combine)
        reference-slot (ids/slot-id parent :left)
        sibling-slot (ids/slot-id parent :right)
        declaring-id (ids/fn-id "fixture" :encode)
        local-type (random-uuid)
        parent-type (random-uuid)
        remote-type (random-uuid)
        declaring-type (random-uuid)
        primitive-ids (ids/primitive-fn-ids)
        text (:text primitive-ids)
        any-type (:any primitive-ids)]
    (context/build-context
      {:fns {owner-id {:id owner-id :input-type-fn-id local-type :parent-ids [parent]}
             parent {:id parent :input-type-fn-id parent-type}
             target-fn-id {:id target-fn-id :input-type-fn-id remote-type :parent-ids [declaring-id]}
             declaring-id {:id declaring-id :input-type-fn-id declaring-type}}
       :members {local-type [{:slot-id field-id :position 0}]
                 parent-type [{:slot-id reference-slot :position 0} {:slot-id sibling-slot :position 1}]
                 remote-type []
                 declaring-type [{:slot-id target-id :position 0}]}
       :fields {reference-slot {:id reference-slot :name "left" :required false :type-fn-id any-type}
                sibling-slot {:id sibling-slot :name "right" :required false :type-fn-id any-type}
                field-id {:id field-id :name "data" :required false :type-fn-id text}
                target-id {:id target-id :name "data" :required true :type-fn-id text}}
       :signatures {target-fn-id {:args {:data :text} :return :text :effects #{}}}
       :bindings {edge {:id edge :fn-id owner-id :scope-path [] :target-path [reference-slot]
                        :expression-kind "ref" :ref-fn-id target-fn-id}}})))


(defn- analyze
  [ctx owner]
  (analysis/analyze-effective-input ctx owner
                                    {:check-expression (fn [{:keys [substitution]}]
                                                         {:status :checked :substitution substitution})}))


(deftest exact-forwarded-origin-survives-checked-replay
  (let [[prepared entry] (fixture {:left :encode-body :data {:type :text}})
        {:keys [owner-id projection-id source-address target-address] :as proof}
        (forward/verify-forwarded-field prepared entry :data)
        snapshot (checked-snapshot proof)
        {:keys [row source-names public-path]} (scope/verify-forwarded-projection snapshot proof analyze)
        replay (-> snapshot (assoc :snapshot-id (random-uuid)) (assoc-in [:bindings projection-id] row))
        result (analyze replay owner-id)]
    (is (= [:data] source-names))
    (is (= [:left :data] public-path))
    (is (= projection-id (:id row)))
    (is (= [source-address] (:required-addresses result)))
    (is (= source-address (get-in result [:public-input :bindings target-address :reference :source])))
    (is (thrown? clojure.lang.ExceptionInfo (scope/verify-forwarded-projection replay proof analyze)))))


(deftest forwarded-path-can-be-emitted-and-read-only-with-its-exact-certificate
  (let [[prepared entry] (fixture {:left :encode-body :data {:type :text}})
        {:keys [owner-id] :as proof} (forward/verify-forwarded-field prepared entry :data)
        snapshot (checked-snapshot proof)
        {:keys [public-path row] :as certificate} (scope/verify-forwarded-projection snapshot proof analyze)
        graph (analyze snapshot owner-id)
        local (get-in graph [:public-input :bindings])
        reader-context {:scope [owner-id []] :contract (get-in graph [:scopes [] :declared-contract])
                        :declaration-records [{:kind :fn :id owner-id :parent-ids (:owner-parents certificate)}]
                        :scoped-inputs {public-path certificate}}
        bind {public-path [:project [:data]]}
        parsed (scoped-source/parse-bindings reader-context local bind)]
    (is (= row (get-in parsed [public-path :row])))
    (is (= bind (scoped-source/emit-bindings {} parsed {})))
    (is (thrown? clojure.lang.ExceptionInfo
          (scoped-source/parse-bindings reader-context local {public-path [:project [:left]]})))
    (is (thrown? clojure.lang.ExceptionInfo
          (scoped-source/parse-bindings (dissoc reader-context :scoped-inputs) local bind)))))


(deftest verified-owner-keeps-its-authored-input-and-serializes-the-forwarding
  (let [[prepared entry] (fixture {:left :encode-body :data {:type :text}})
        {:keys [field-id projection-id] :as proof} (forward/verify-forwarded-field prepared entry :data)
        baseline (:entry (source/forwarded-field-baseline prepared entry :data))
        certificate (scope/verify-forwarded-projection (checked-snapshot proof) proof analyze)
        migrated (source/rewrite-forwarded-field prepared entry :data certificate)]
    (is (= :authored-source-equivalent (:verification baseline)) (:blockers baseline))
    (is (= {:left [:ref :fixture/encode-body]} (get-in baseline [:proposed :bind])))
    (is (= :authored-source-equivalent (:verification migrated)) (:blockers migrated))
    (is (= {:data {:type :text}} (get-in migrated [:proposed :input])))
    (is (= [:project [:data]] (get-in migrated [:proposed :bind [:left :data]])))
    (is (= field-id (some #(when (= :data (:name %)) (:id %)) (get-in migrated [:v2 :contract :fields]))))
    (is (= #{projection-id} (into #{} (keep #(when (= "projection" (:expression-kind %)) (:id %)))
                                  (get-in migrated [:v2 :bindings]))))
    (is (thrown? clojure.lang.ExceptionInfo
          (source/rewrite-forwarded-field prepared entry :data (assoc-in certificate [:row :id] (random-uuid)))))))
