(ns graphden.input.pinned-list
  "Prepare concrete list fragments from a pinned, parent-ordered v2 module."
  (:require
    [graphden.input.list-records :as list-records]
    [graphden.input.list-signature :as signature]
    [graphden.input.migration :as migration]
    [graphden.input.module :as module]
    [graphden.input.source-context :as source-context]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]))


(defn- reject!
  []
  (throw (ex-info "Unsupported pinned list declaration" {:type :package-error/primitive-admission})))


(defn- fragment-source?
  [field definition]
  (let [form (get-in definition [:bind field])]
    (and (every? #{:name :parent :bind :description} (keys definition))
         (simple-keyword? (:parent definition)) (= #{field} (set (keys (:bind definition))))
         (vector? form) (= :list-builder (first form))
         (every? #(and (vector? %) (= :value (first %))) (get-in form [1 :items])))))


(defn- parse-one
  [source root field slot entries definition]
  (let [authored (assoc definition :namespace (:namespace source))
        id (ids/fn-id (:namespace source) (:name authored))
        parent (get entries (:parent authored))
        _ (when-not (or (= id root) (and parent (fragment-source? field definition))) (reject!))
        rows (if (= id root)
               (module/unique-records (parse/base-declaration-records authored (:input authored) {}))
               [(parse/composed-declaration-record authored [(get-in parent [:parsed :fn-id])] nil)])
        environment (assoc (source-context/environment {:name->id {}})
                           :definition-by-id (into {} (map (fn [{:keys [authored parsed]}] [(:fn-id parsed) authored])) (vals entries))
                           :records-of #(get-in entries [(:name %) :rows]))
        binding-id (ids/binding-id id slot)
        context (assoc (source-context/declaration-context environment authored rows
                                                           (if parent [{:fn-id id :slot-id slot :id binding-id}] []))
                       :list-history {[slot] (get-in parent [:trace :fragments] [])})
        parsed (first (:definitions (module/parse-module-v2 (assoc source :fns [definition]) {:context-for (constantly context)})))
        trace (get-in parsed [:list-builders [slot]])]
    (when (and parent (nil? trace)) (reject!))
    {:authored authored :parsed parsed :rows rows :trace trace}))


(defn- retain-list-type
  [slot list-id builders row]
  ;; Legacy list FKs erase the element to :sequence. Only these witnessed
  ;; return/tail/receiver fields receive the pinned concrete descriptor.
  (let [sequence-id (ids/primitive-fn-id :sequence)]
    (cond
      (and (= :fn (:kind row)) (contains? builders (:id row)))
      (if (= sequence-id (:return-type-fn-id row)) (assoc row :return-type-fn-id list-id) (reject!))
      (and (= :slot (:kind row))
           (or (= slot (:id row)) (some #(= (:id row) (ids/slot-id % :tail)) builders)))
      (if (= sequence-id (:type-fn-id row)) (assoc row :type-fn-id list-id) (reject!))
      :else row)))


(defn prepare
  "The first declaration supplies one concrete list argument to a native ABI.
   Descendants can add literal fragments to a previously declared parent."
  [{:keys [fns] :as source} root contract]
  (let [[base & children] fns
        [field type] (first contract)
        element (when (vector? type) (second type))]
    (when-not (and (map? contract) (= 1 (count contract)) (simple-keyword? field)
                   (= [:list element] type) (contains? #{:numeric :int :bool :text :keyword} element)
                   (= (:input base) {field {:type type :required true}})
                   (contains? #{:numeric :int :bool :text :keyword} (:return-type base))
                   (every? #{:name :description :input :return-type} (keys base))
                   (= root (ids/fn-id (:namespace source) (:name base))) (seq children)
                   (every? (comp simple-keyword? :name) fns) (= (count fns) (count (set (map :name fns)))))
      (reject!))
    (let [slot (ids/slot-id root field)
          entries (reduce (fn [result d] (assoc result (:name d) (parse-one source root field slot result d))) {} fns)
          definitions (mapv (comp :parsed entries :name) fns)
          traces (mapv (comp :trace entries :name) children)
          generated (into {} (map (fn [trace]
                                    (let [owner (:owner-id (peek (:fragments trace)))
                                          entry (signature/entry owner {} trace)]
                                      [(get-in entry [:owner :id]) entry]))) traces)
          list-id (migration/derived-id :list-type element)
          records (module/unique-records
                    (concat (mapcat :declaration-records definitions) (mapcat :bindings definitions)
                            (mapcat list-records/authored-records traces)))
          rows (into [{:kind :fn :id list-id :parent-ids [] :element-fn-id (ids/primitive-fn-id element)}]
                     (map (partial retain-list-type slot list-id (set (keys generated)))) records)]
      {:definitions definitions :records rows :generated generated})))
