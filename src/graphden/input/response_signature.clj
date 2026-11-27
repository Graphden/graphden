(ns graphden.input.response-signature
  "Replay closed assertions on a Ring response using one structured response input."
  (:require
    [clojure.set :as set]
    [graphden.executor.compile.surface :as surface]
    [graphden.input.analysis :as analysis]
    [graphden.input.migration :as migration]
    [graphden.input.query-lookup-source :as query-source]
    [graphden.input.signatures :as signatures]
    [graphden.input.source-legacy-readers :as readers]
    [graphden.input.source-origins :as origins]
    [graphden.input.source-snapshot :as snapshot]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]))


(def ^:private signature-id (ids/fn-id "web.tests" :_wt-resp-sig))
(def ^:private assert-id (ids/fn-id "core.logic" :assert-eq))
(def ^:private fingerprint "06d246c7ca4deae30656606854dc8a9f52e0189fea2bac132e910af2b8598c09")


(defn- product-name
  [role]
  (keyword (str "_" (name role) "-" (migration/derived-id :response-signature signature-id role))))


(defn- local-reference
  [function-name]
  (keyword "web.tests" (name function-name)))


(defn- products
  []
  (let [holder (product-name :response)
        chooser (product-name :coalesce)
        response (local-reference holder)
        lookup (fn [key] {:parent :core.collections/get :args {:coll response :key {:value key} :default nil}})
        header (fn [key]
                 {:parent :core.collections/get-in
                  :args {:map response :path {:append [{:value :headers} {:value key}] :closed true} :default nil}})
        choose-header (fn [key]
                        {:parent (local-reference chooser)
                         :args {:first-value (header key) :next-value (header (name key))}})]
    [{:name holder :namespace "web.tests" :parent :core.logic/const
      :args {:value {:as :resp :type [:map :any :any]}}}
     {:name chooser :namespace "web.tests" :parent :core.logic/coalesce
      :args {:value {:as :first-value :type :any} :default {:as :next-value :type :any}}}
     {:name (product-name :response-signature) :namespace "web.tests" :parent :core.collections/vec
      :args {:resp {:type [:map :any :any]}
             :coll {:append [(lookup :status) (choose-header :Content-Type) (choose-header :Cache-Control) (lookup :body)]
                    :closed true}}}]))


(defn- template?
  [{module :namespace :keys [parent args]}]
  (let [{:keys [actual expected]} args
        producer (get-in actual [:args :resp])
        {:keys [headers status body] :as fields} (:args producer)]
    (and (= "web.tests" module) (= :assert-eq parent) (map? args) (= #{:actual :expected} (set (keys args)))
         (map? actual) (map? expected)
         (= #{:value} (set (keys expected))) (vector? (:value expected)) (= 4 (count (:value expected)))
         (= #{:parent :args} (set (keys actual))) (= :_wt-resp-sig (:parent actual))
         (= #{:resp} (set (keys (:args actual))))
         (map? producer) (= #{:parent :args} (set (keys producer))) (keyword? (:parent producer))
         (map? fields) (string? body) (set/subset? (set (keys fields)) #{:body :status :headers})
         (or (not (contains? fields :status)) (int? status))
         (or (not (contains? fields :headers)) (and (map? headers) (every? string? (concat (keys headers) (vals headers))))))))


(defn- native-donors
  [roots]
  (let [natives [["core.logic" :const '{:value a} 'a]
                 ["core.logic" :coalesce '{:value [:union :null a] :default [:union :null a]} 'a]
                 ["core.logic" :assert-eq '{:actual a :expected a} 'a]
                 ["core.collections" :vec '{:coll [:list a]} '[:list a]]
                 ["core.collections" :get '{:coll [:union :null [:list :any] [:map a :any]] :key :any :default :any} :any]
                 ["core.collections" :get-in '{:map [:union :null [:map a :any]] :path [:list [:union :int :keyword :text]] :default :any} :any]]
        ids (mapv (fn [[module name args result]]
                    (let [id (ids/fn-id module name)
                          entry (get roots id)]
                      (when (and (= [id module name args result #{}] ((juxt :fn-id :namespace :name :args :return :effects) entry))
                                 (contains? (:execution entry) :jvm-server) (signatures/local-type-rules? entry))
                        id))) natives)]
    (when (every? uuid? ids) ids)))


(defn- unique-source
  [package id]
  (let [[form & others] (get-in package [:originals id])]
    (when-not (seq others) form)))


(defn- original-evidence
  [package graph owner]
  (let [origins @(:inline-origins package)
        child (:fn-id (origins/child-at origins owner [:args :actual]))
        producer (when child (:fn-id (origins/child-at origins child [:args :resp])))
        [writer & more] (get-in graph [:bindings-by-fn child])
        row (get-in graph [:binding-by-fn-slot [owner (ids/slot-id assert-id :actual)]])]
    (when (and (uuid? producer) (= [assert-id] (get-in graph [:fn-map owner :parent-ids]))
               (= [signature-id] (get-in graph [:fn-map child :parent-ids]))
               (= child (:ref-fn-id row)) (= producer (:ref-fn-id writer))
               (empty? more) (every? #(empty? (:accepted (surface/surface-names % graph))) [owner child producer])
               (= #{:resp} (:accepted (surface/surface-names signature-id graph)))
               (= fingerprint (:fingerprint (query-source/closure-evidence graph signature-id))))
      {:owner-id owner :response-reader child :response-producer producer
       :binding-id (:id writer) :slot-id (:slot-id writer)
       :original-targets (:targets (query-source/closure-evidence graph owner))})))


(defn- producer-origin
  [graph normalized prepared {:keys [owner-id response-reader response-producer]}]
  (let [after (readers/owner-index prepared owner-id)
        origins @(:inline-origins normalized)
        reader (:fn-id (origins/child-at origins owner-id [:args :actual]))
        producer (when reader (:fn-id (origins/child-at origins reader [:args :resp])))
        bindings (map (fn [row] (cond-> row (= response-reader (:ref-fn-id row)) (assoc :ref-fn-id reader)))
                      (get-in graph [:bindings-by-fn owner-id]))
        writes (map (fn [{:keys [slot-id] :as row}]
                      (assoc row :fn-id producer :id (ids/binding-id producer slot-id)))
                    (get-in graph [:bindings-by-fn response-producer]))]
    (when (and (uuid? reader) (uuid? producer)
               (= (set bindings) (set (get-in after [:bindings-by-fn owner-id])))
               (= reader (get-in after [:binding-by-fn-slot [owner-id (ids/slot-id assert-id :actual)] :ref-fn-id]))
               (= [producer] (mapv :ref-fn-id (get-in after [:bindings-by-fn reader])))
               (= (get-in graph [:fn-map response-producer :parent-ids]) (get-in after [:fn-map producer :parent-ids]))
               (= (set writes) (set (get-in after [:bindings-by-fn producer]))))
      {:normalized-reader reader :normalized-producer producer})))


(defn- checked
  [package owner source graph proof roots donors]
  (let [helpers (products)
        ids (mapv #(ids/fn-id (:namespace %) (:name %)) helpers)
        expanded (assoc-in source [:args :actual :parent] (local-reference (:name (peek helpers))))
        forms (conj helpers expanded)
        original (:prepared package)]
    (when (and (not-any? (:declarations package) ids) (not-any? roots ids)
               (every? (:declarations package) (into (:original-targets proof) donors)))
      (let [prepared (parse/prepare-module forms (:name->id original) (:defs-by-name original))
            originals (dissoc (:originals package) owner)
            normalized (-> (snapshot/package-index prepared (into (vec (mapcat val originals)) forms))
                           (assoc :closure-baselines (dissoc (:closure-baselines package) owner))
                           (snapshot/with-certified-owner owner roots))
            entry (get-in normalized [:closure-baselines owner])
            {:keys [context options]} (snapshot/freeze-source-analysis normalized [owner] roots)
            result (analysis/analyze-effective-input context owner options)
            implementations (:implementations options)
            reached (set (keys implementations))
            origin (producer-origin graph normalized prepared proof)]
        (when (and origin (= :authored-source-equivalent (:verification entry))
                   (= :checked (:status result)) (= #{[]} (set (keys (get-in result [:public-input :routes]))))
                   (every? #(empty? (get-in % [:root-entry :effects])) (vals implementations)))
          {:kind :response-signature-test :terminal-proof? true
           :original source :expanded expanded :proposed (:proposed entry) :entry entry
           :package normalized :roots roots :donors (into (set donors) (comp (map :base-fn-id) (filter roots)) (vals implementations))
           :proof (merge proof origin) :generated-ids ids :visibility (into reached ids)})))))


(defn prepare
  "Translate a closed assert-eq of a literal response producer. The reviewed
   six original reads consume the one lexical response at this invocation.
   The original shared declaration and unrelated inheritors keep their own proofs."
  [package owner roots]
  (let [source (unique-source package owner)]
    (when (template? source)
      (let [graph (or (:legacy-graph package) (readers/owner-index (:prepared package) owner))]
        (when-let [proof (original-evidence package graph owner)]
          (when-let [donors (native-donors roots)]
            (checked package owner source graph proof roots donors)))))))
