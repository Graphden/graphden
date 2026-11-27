(ns graphden.input.conditional-reads
  "Typed reads of the same scalar under a lazy native conditional."
  (:require
    [graphden.packages.records.ids :as ids]
    [graphden.types.core :as types]))


(def ^:private native-ids
  (into {} (map (fn [[name module]] [name (ids/fn-id module name)]))
        {:if "core.logic" :some? "core.logic" :is-a? "core.logic" :const "core.logic" :parse-uuid "core.system"}))


(defn- native-binding
  [graph path native field]
  (let [node (get-in graph [:scopes path])
        base (get native-ids native)
        implementation (get-in graph [::implementations (:fn-id node)])
        field-path [(ids/slot-id base field)]
        address [[(:fn-id graph) path] field-path]]
    (when (and (= base (first (:declaration-order node)) (:base-fn-id implementation) (get-in implementation [:root-entry :fn-id]))
               (= field-path (get-in implementation [:argument-paths field]))
               (= #{} (get-in implementation [:root-entry :effects])))
      (get-in node [:bindings address]))))


(defn- value-use
  [path {:keys [binding-id expression reference]}]
  (when (and (uuid? binding-id) (uuid? (:fn-id expression)) (= :ref (:kind expression)) (= :value (:mode reference)))
    (conj path binding-id)))


(defn- const-source
  [graph path]
  (let [{:keys [expression]} (native-binding graph path :const :value)]
    (when (= :projection (:kind expression))
      (loop [address ((juxt :scope :path) expression) seen #{}]
        (when-not (seen address)
          (let [[[owner path] fields] address
                node (get-in graph [:scopes path])
                bound (get-in node [:bindings address])]
            (when (and (= owner (:fn-id graph)) (uuid? (:fn-id node)) (= 1 (count fields)) (uuid? (first fields)))
              (cond
                (= :projection (get-in bound [:expression :kind]))
                (recur ((juxt :scope :path) (:expression bound)) (conj seen address))
                bound [:bound address]
                :else [:field (:fn-id node) fields]))))))))


(defn- scalar-source
  [graph path expected]
  (when path
    (case expected
      :text (const-source graph path)
      :uuid (when-let [input (value-use path (native-binding graph path :parse-uuid :string))]
              (const-source graph input))
      nil)))


(defn- guard-source
  [graph path expected]
  (when path
    (cond
      (= :uuid expected)
      (scalar-source graph (value-use path (native-binding graph path :some? :value)) expected)

      (= {:kind :value :value :text} (:expression (native-binding graph path :is-a? :type)))
      (scalar-source graph (value-use path (native-binding graph path :is-a? :value)) expected)

      ;; A nested conditional with a literal false alternative can only
      ;; produce a truthy result when its own test succeeded.
      (= {:kind :value :value false} (:expression (native-binding graph path :if :else)))
      (guard-source graph (value-use path (native-binding graph path :if :test)) expected))))


(defn- guarded-type
  [graph path node test {:keys [address expected-type binding-id reference] :as binding}]
  (let [actual (:actual-type reference)
        proof (get-in node [:metadata-proofs binding-id])]
    (when (and (#{:text :uuid} expected-type)
               (= #{:null expected-type} (set (types/union-members actual)))
               (= :checked (:status proof)) (= address (:address proof)) (= binding-id (:binding-id proof)))
      (let [source (scalar-source graph (value-use path binding) expected-type)]
        (when (and source (= source (guard-source graph test expected-type)))
          expected-type)))))


(defn install
  "Keep the assertion on the selected field. Each use is checked at runtime
   even when the producer and the guard reach the same authored field."
  [graph path implementations]
  (let [node (get-in graph [:scopes path])]
    (if (and (seq path) (seq (:metadata-proofs node)))
      (let [evidence (assoc graph ::implementations implementations)
            parent (pop path)
            then (value-use parent (native-binding evidence parent :if :then))]
        (if (= path then)
          (let [test (value-use parent (native-binding evidence parent :if :test))]
            (reduce-kv (fn [g address binding]
                         (if-let [type (guarded-type evidence path node test binding)]
                           (assoc-in g [:scopes path :bindings address :guarded-read-type] type)
                           g))
                       graph (:bindings node)))
          graph))
      graph)))
