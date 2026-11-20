(ns graphden.input.list-builder-test
  (:require
    [clojure.set :as set]
    [clojure.test :refer [deftest is]]
    [graphden.input.list-builder :as builder]
    [graphden.input.list-records :as list-records]
    [graphden.input.source :as source]
    [graphden.input.source-list :as source-list]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]))


(defn- fixture
  [start end]
  (let [base {:namespace "demo" :name :consume :args {:xs {:type [:list :any]}} :return-type :any}
        source [{:namespace "demo" :name :source :args {} :return-type :int}
                base {:namespace "demo" :name :first-step :parent :consume :args {:xs start}}
                {:namespace "demo" :name :last-step :parent :first-step :args {:xs end}}]
        context (parse/prepare-module source {} {})
        rows (parse/parse-prepared-module context)
        fragments (for [[n form] [[:first-step start] [:last-step end]]
                        :let [binding (some #(when (and (= :binding (:kind %)) (= (ids/fn-id "demo" n) (:fn-id %))) %) rows)
                              items (filterv #(= (:id binding) (:binding-id %)) rows)]]
                    (builder/source-fragment binding items form (:name->id context)))]
    (vec fragments)))


(defn- reason
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error (:code (ex-data error)))))


(deftest ordered-fragments-use-separate-continuations
  (let [[head tail :as fragments] (fixture [:source {:value :source} nil false] {:append [:source] :closed true})
        source-id (ids/fn-id "demo" :source)
        first-id (:binding-id head)
        {:keys [bindings open-continuation]} (builder/link-fragments fragments)
        [initial next-fragment seal] bindings
        form (builder/emit-fragment head {source-id :demo/source})]
    (is (= [:list-builder {:tail :open :items [[:ref :demo/source] [:value :source] [:value nil] [:value false]]}] form))
    (is (= (dissoc head :owner-id :target-id) (builder/parse-fragment first-id form {:demo/source source-id})))
    (is (= (mapv #(ids/binding-list-item-id first-id %) (range 4)) (mapv :id (:items head))))
    (is (= [(:target-id head)] (:target-path initial)))
    (is (= [first-id] (:scope-path next-fragment)))
    (is (= [(:tail-id head)] (:target-path next-fragment)))
    (is (= (:binding-id tail) (:id next-fragment)))
    (is (= [(:tail-id tail)] (:target-path seal)))
    (is (= [] (:value seal)))
    (is (nil? open-continuation))
    (is (= [[first-id] [(:tail-id head)]] (:open-continuation (builder/link-fragments [head]))))))


(deftest explicit-blockers-for-unmapped-or-closed-routes
  (is (= :positional-list-input-required (reason #(fixture [{:value {:as :input}}] []))))
  (is (= :unsupported-list-binding (reason #(fixture {:append [] :closed? true} []))))
  (is (= :closed-list-continuation (reason #(builder/link-fragments (fixture {:append [] :closed true} [])))))
  (is (= :invalid-list-chain (reason #(let [[head] (fixture [] [])] (builder/link-fragments [head head]))))))


(deftest source-planner-records-the-authored-chain-only
  (let [base {:namespace "demo" :name :consume :args {:xs {:type [:list :any]}} :return-type :any}
        head {:namespace "demo" :name :head :parent :consume :args {:xs [1]}}
        tail {:namespace "demo" :name :tail :parent :head :args {:xs [2]}}
        originals [base head tail]
        ctx (parse/prepare-module originals {} {})
        trace (fn [forms]
                (-> (source/plan-module forms ctx) :definitions last :legacy :arguments first :list-builder))
        linked (trace originals)]
    (is (= [[1] [2]] (mapv #(mapv :value (:items %)) (:fragments linked))))
    (is (= (mapv :binding-id (:fragments linked)) (mapv :id (:bindings linked))))
    (is (= :list-source-origin-required (:blocker (trace [tail]))))))


(deftest compiler-receives-ordered-refs-and-opaque-literals
  (let [owner (ids/fn-id "demo" :consumer)
        target (ids/fn-id "demo" :target)
        origin (ids/binding-id owner (ids/slot-id owner :items))
        fragment (builder/parse-fragment origin [:list-builder {:items [[:ref :target] [:value {:as :data}] [:value nil]]
                                                                :tail :open}] {:target target})
        [ref-row literal nil-row] (builder/compiler-items fragment)]
    (is (= [target 0 origin] ((juxt :ref-fn-id :position :binding-id) ref-row)))
    (is (= [{:as :data} true] ((juxt :value :literal) literal)))
    (is (= [nil nil 2] ((juxt :value :ref-fn-id :position) nil-row)))
    (is (= :list-item-origin-mismatch
           (reason #(builder/compiler-items (assoc-in fragment [:items 1 :id] (:id ref-row))))))))


(deftest ambiguous-bare-refs-use-their-authoring-namespace
  (let [alpha {:namespace "alpha" :name :read :args {} :return-type :int}
        beta (assoc alpha :namespace "beta")
        names (:name->id (parse/prepare-module [alpha beta] {} {}))
        original [:read {:value :read} {:ref :read} {:value {:ref :read}}]
        scoped (source-list/field-names names "alpha" original)
        form (builder/source-form original scoped)
        fragment (builder/parse-fragment (ids/fn-id "demo" :test) form scoped)]
    (is (= [(ids/fn-id "alpha" :read) :read (ids/fn-id "alpha" :read) {:ref :read}]
           (mapv #(or (:fn-id %) (:value %)) (:items fragment))))))


(deftest source-evidence-refuses-unmapped-columns-and-relabelled-item-rows
  (let [[fragment] (fixture [1N nil] [])
        rows (builder/compiler-items fragment)
        binding {:kind :binding :id (:binding-id fragment) :fn-id (:owner-id fragment)
                 :slot-id (:target-id fragment) :list-append true :list-closed nil :value-present false}
        check (fn [binding rows] (builder/source-fragment binding rows [1N nil] {}))]
    (is (= fragment (check binding rows)))
    (is (= :unsupported-list-binding (reason #(check (assoc binding :new-policy nil) rows))))
    (is (= :unsupported-list-binding (reason #(check (assoc binding :list-closed false) rows))))
    (is (= :list-item-origin-mismatch (reason #(check binding (assoc-in rows [1 :new-policy] false)))))
    (is (= :list-item-origin-mismatch (reason #(check binding (assoc-in rows [0 :value] 1)))))
    (is (= :list-item-origin-mismatch (reason #(check binding (assoc-in rows [0 :position] 1)))))))


(deftest generated-callable-records-recover-every-item-and-its-order
  (let [[fragment] (fixture [:source nil false 1N] [])
        field {:type [:list :any] :type-id (ids/primitive-fn-id :sequence)}
        records (list-records/materialize fragment field {})
        origin (dissoc fragment :items)
        rows (filterv #(= :input-binding (:kind %)) records)
        idx (java.util.List/.indexOf records (first rows))]
    (is (= (mapv :id (:items fragment)) (mapv :id rows)))
    (is (= fragment (list-records/recover origin field {} (reverse records))))
    (is (= :list-declaration-mismatch
           (reason #(list-records/recover origin field {} (assoc-in records [idx :terminal] true)))))
    (is (= :missing-list-operand
           (reason #(list-records/recover origin field {} (remove #{(first rows)} records)))))
    (is (= :list-element-declarations-required
           (reason #(list-records/materialize fragment (assoc field :type :text) {}))))))


(deftest full-row-inverse-checks-the-tail-use-site-and-seal
  (let [fragments (fixture [1] {:append [:source] :closed true})
        field {:type [:list :any] :type-id (ids/primitive-fn-id :sequence)}
        records (list-records/materialize-chain fragments field {})
        recover #(list-records/recover-chain (mapv :owner-id fragments) (:target-id (first fragments)) field {} %)
        [base append seal] (:bindings (builder/link-fragments fragments))]
    (is (= fragments (:fragments (recover (reverse records)))))
    (is (= :list-declaration-mismatch (reason #(recover (remove #{base} records)))))
    (is (= :list-declaration-mismatch (reason #(recover (replace {append (assoc append :scope-path [])} records)))))
    (is (= :list-declaration-mismatch (reason #(recover (replace {seal (assoc seal :value [0])} records)))))))


(deftest direct-source-replay-compares-the-authored-chain-with-the-v2-rows
  (let [base {:namespace "demo" :name :consume :args {:xs :sequence} :return-type :any}
        read-fn {:namespace "demo" :name :read :args {} :return-type :int}
        owner {:namespace "demo" :name :batch :parent :consume :args {:xs {:append [:read nil 1N] :closed true}}}
        sources [base read-fn owner]
        ctx (parse/prepare-module sources {} {})
        plan (source/plan-module [owner] ctx)
        check #(first (:definitions (source/verify-module % ctx)))
        entry (check plan)
        slot (ids/slot-id (ids/fn-id "demo" :consume) :xs)
        binding (ids/binding-id (ids/fn-id "demo" :batch) slot)
        expected (get-in entry [:legacy :arguments 0 :list-builder])
        actual (get-in entry [:v2 :list-builders [slot]])
        path [:definitions 0 :proposed :bind :xs]]
    (is (= :authored-source-equivalent (:verification entry)))
    (is (= expected actual))
    (is (= (mapv #(ids/binding-list-item-id binding %) (range 3))
           (mapv :id (get-in actual [:fragments 0 :items]))))
    (is (= (first (:bindings actual)) (get-in entry [:v2 :bindings 0])))
    (is (= :list-fragment-mismatch
           (reason #(builder/reference-expression actual [:list-builder {:items [] :tail :closed}] {}))))
    (doseq [change-plan [(fn [d] (assoc-in d (into path [1 :items 2]) [:value 1]))
                         (fn [d] (assoc-in d (into path [1 :tail]) :open))
                         (fn [d] (assoc-in d (into path [1 :items 0]) [:value :read]))
                         (fn [d] (update-in d (into path [1 :items]) #(vec (reverse %))))
                         (fn [d] (update-in d [:definitions 0 :proposed :bind] dissoc :xs))]]
      (is (= [:legacy-v2-meaning-mismatch] (mapv :code (:blockers (check (change-plan plan)))))))
    (is (= [:unresolved-list-item-ref]
           (mapv :code (:blockers (check (assoc-in plan (into path [1 :items 0]) [:ref :consume]))))))))


(deftest descendants-author-only-their-own-tail-contributions
  (let [base {:name :collect :namespace "demo" :args {:xs :sequence} :return-type :any}
        step (fn [name parent body] {:name name :namespace "demo" :parent parent :args {:xs body}})
        head (step :head :collect [1])
        middle (step :middle :head [2])
        tail (step :tail :middle {:append [3] :closed true})
        sibling (step :sibling :head [4])
        sealed (step :sealed :tail [5])
        sources [base head middle tail sibling sealed]
        ctx (parse/prepare-module sources {} {})
        checked (source/verify-module (source/plan-module sources ctx) ctx)
        [_ first-entry second-entry third-entry sibling-entry sealed-entry] (:definitions checked)
        field (ids/slot-id (ids/fn-id "demo" :collect) :xs)
        trace #(get-in % [:v2 :list-builders [field]])
        [a b c] (:fragments (trace third-entry))
        bind #(get-in % [:v2 :bindings 0])
        second-edge (bind second-entry)
        third-edge (bind third-entry)
        sibling-edge (bind sibling-entry)
        third-state (get-in third-entry [:v2 :binding-state])
        own (list-records/authored-records (trace third-entry))]
    (is (= {:supported 5 :blocked 1} (:counts checked)))
    (is (= [:list-builder-required :closed-list-continuation] (mapv :code (:blockers sealed-entry))))
    (is (= [[1] [2] [3]] (mapv #(mapv :value (:items %)) [a b c])))
    (is (= [(:binding-id a)] (:scope-path second-edge) (:scope-path sibling-edge)))
    (is (= [(:tail-id a)] (:target-path second-edge) (:target-path sibling-edge)))
    (is (not= (:fn-id second-edge) (:fn-id sibling-edge)))
    (is (= [(:binding-id a) (:binding-id b)] (:scope-path third-edge)))
    (is (= [(:tail-id b)] (:target-path third-edge)))
    (is (= #{[[(:owner-id c) (:scope-path third-edge)] [(:tail-id b)]]} (set (keys third-state))))
    (is (= #{(:builder-id c) (:owner-id c)}
           (set (map :fn-id (filter #(= :input-binding (:kind %)) own)))))
    (is (empty? (set/intersection (set own) (set (list-records/authored-records (trace first-entry))))))
    (is (= (:records (trace third-entry))
           (list-records/materialize-chain [a b c] (:field (trace third-entry)) (:name->id ctx))))))


(deftest missing-or-malformed-frozen-list-history-cannot-reopen-a-field
  (let [[head] (fixture {:append [1] :closed true} [])
        id (:owner-id head)
        slot (:target-id head)
        context {:fn-id id :scope [id []] :binding-id (fn [_ _] (:binding-id head)) :name->id {}}
        field {:id slot :type :sequence :type-id (ids/primitive-fn-id :sequence)}
        form [:list-builder {:items [] :tail :open}]
        read-list #(list-records/parse-contribution % field form)]
    (is (= :list-history-required (reason #(read-list context))))
    (is (= :list-history-required (reason #(read-list (assoc-in context [:list-history [slot]] nil)))))
    (is (= :invalid-list-chain (reason #(read-list (assoc-in context [:list-history [slot]] [head])))))))


(deftest positional-projections-retain-source-slots-and-occurrence-uuids
  (let [base {:namespace "fixture" :name :consumer :args {:xs :sequence} :return-type :any}
        head {:namespace "fixture" :name :head :parent :consumer :args {:xs [{:as :x} {:as :x}]}}
        tail {:namespace "fixture" :name :tail :parent :head :args {:xs [{:as :y :type :text}]}}
        defs [base head tail]
        prepared (parse/prepare-module defs {} {})
        planned (source/plan-module defs prepared)
        replay (source/verify-module planned prepared)
        [_ head-entry tail-entry] (:definitions replay)
        x (ids/slot-id (:identity head-entry) :x)
        y (ids/slot-id (:identity tail-entry) :y)
        field (ids/slot-id (ids/fn-id "fixture" :consumer) :xs)
        trace (get-in tail-entry [:v2 :list-builders [field]])
        [a b] (mapv :binding-id (:fragments trace))
        rows (filterv #(= "projection" (:expression-kind %)) (:records trace))
        all (distinct (mapcat (fn [{:keys [v2]}]
                                (concat (:declaration-records v2) (:bindings v2)
                                        (mapcat list-records/authored-records (vals (:list-builders v2)))))
                              (:definitions replay)))
        recover #(list-records/recover-use [(:identity head-entry) (:identity tail-entry)] field (:field trace)
                                           (:name->id prepared) %)
        bad (source/verify-module (update-in planned [:definitions 2 :proposed :input] dissoc :y) prepared)]
    (is (= {:supported 3 :blocked 0} (:counts replay)))
    (is (= {:x {:type :any :required false}} (get-in head-entry [:proposed :input])))
    (is (= {:y {:type :text :required false}} (get-in tail-entry [:proposed :input])))
    (is (= [[x] [x] [y]] (mapv :projection-path rows)))
    (is (= [[] [] []] (mapv :projection-scope-path rows)))
    (is (= [[a] [a] [a b]] (mapv :scope-path rows)))
    (is (= [(ids/binding-list-item-id a 0) (ids/binding-list-item-id a 1) (ids/binding-list-item-id b 0)] (mapv :id rows)))
    (is (= (:fragments trace) (:fragments (recover all))))
    (is (= :positional-list-field-mismatch
           (reason #(recover (remove (fn [r] (and (= :slot (:kind r)) (= x (:id r)))) all)))))
    (is (= :positional-list-field-mismatch (get-in bad [:definitions 2 :blockers 0 :code])))))


(deftest positional-marker-annotations-are-not-interpreted-as-input-requirements
  (let [base {:namespace "fixture" :name :consumer :args {:xs :sequence} :return-type :any}
        child {:namespace "fixture" :name :child :parent :consumer
               :args {:xs [{:as :name :required true :description "marker documentation"}]}}
        defs [base child]
        entry (peek (:definitions (source/plan-module defs (parse/prepare-module defs {} {}))))]
    (is (= #{:positional-list-annotation-required :list-builder-required} (set (map :code (:blockers entry)))))
    (is (nil? (:proposed entry)))))
