(ns graphden.input.form-query
  "Preserve a verified inline query and its form-body fallback at their original reference addresses."
  (:require
    [graphden.input.analysis :as analysis]
    [graphden.input.form-callback :as callback]
    [graphden.input.migration :as migration]
    [graphden.input.query-lookup-package :as query]
    [graphden.input.query-lookup-source :as query-source]
    [graphden.input.rows :as rows]
    [graphden.input.source :as source]
    [graphden.input.source-legacy-readers :as readers]
    [graphden.input.source-origins :as origins]
    [graphden.input.source-snapshot :as snapshot]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]))


(def ^:private reviewed
  (into {} (for [[parameter fingerprint]
                 [["name" "919b54b5e46c58c3ae164abc9b0a897cbb06e7c4cd9b17821186f9ed93e4adc8"]
                  ["version" "03ba2e771ebed034c5fc0c341968f83731a00a314e620d972e1d8d2bdc537ae0"]
                  ["source" "76e385aa38b3f82815fa0bae5a462579223eac93ead3e46a56a2cf73389afa44"]]]
             [(ids/fn-id "app.registry" (keyword (str "_pkg-install-" parameter "-raw")))
              {:parameter parameter :fingerprint fingerprint}])))


(defn- original
  [package id]
  (let [[form & others] (get-in package [:originals id])]
    (when (empty? others) form)))


(defn- original-body?
  [source parameter]
  (= {:parent :coalesce
      :args {:value {:parent :query-param :args {:request {:as :request} :param-name {:value parameter}}}
             :default {:parent :get :args {:coll :_pkg-install-form :key {:value (keyword parameter)}
                                           :default (when-not (= "source" parameter) {:value ""})}}}}
     (select-keys source [:parent :args])))


(defn- owner-rows
  [package owner]
  (parse/parse-prepared-module (assoc (:prepared package) :fn-defs [(get-in package [:declarations owner])])))


(def ^:private request-path [:value :request])


(def ^:private fallback-requests
  #{[:default :coll :m :test :values :item-0 :value :request]
    [:default :coll :m :test :values :item-1 :string :request]
    [:default :coll :m :then :string :value :request]})


(def ^:private fallback-list-ends
  #{[:default :coll :m :test :values :tail]
    [:default :coll :m :test :values :item-1 :string :path :tail]})


(defn- require-form!
  [ok code]
  (when-not ok (throw (ex-info "Cannot establish the structured form boundary" {:type :input/form-proof :code code}))))


(defn- request-prefix?
  [path]
  (and (<= (count request-path) (count path)) (= request-path (subvec path 0 (count request-path)))))


(defn- receiver-rows
  [ctx graph owner]
  (let [routes (get-in graph [:public-input :routes])
        request (get routes request-path)
        [[source-owner source-scope] source-path] (:address request)
        inputs (into #{} (comp (filter #(= :input-field (:kind (val %)))) (map key)) routes)
        residual (set (remove request-prefix? inputs))]
    (require-form! (and (= :checked (:status graph)) (= owner source-owner) (= 1 (count source-scope))
                        (= :input-field (:kind request)) (:whole-bindable? request) (:required request)
                        (= (ids/fn-id "web.ring-adapter" :ring-request-shape) (get-in ctx [:fields (first source-path) :type-fn-id]))
                        (= residual (into fallback-requests fallback-list-ends))) :unproved-form-request)
    (mapv (fn [path]
            (let [site (routes path)
                  [[_ uses] field] (:address site)
                  id (migration/derived-id :form-request-operand owner uses field)
                  row {:kind :input-binding :id id :fn-id owner :scope-path uses :target-path field}
                  target (get-in graph [:scopes uses :fn-id])]
              (require-form! (and (:whole-bindable? site) (= 1 (count field)) (not (contains? (:bindings ctx) id))) :unexpected-form-reader)
              (rows/normalize
                (if (fallback-requests path)
                  (do (require-form! (and (= :request (:name site))
                                          (= field [(ids/slot-id target :request)])) :unexpected-form-reader)
                      (assoc row :expression-kind "projection" :projection-scope-path source-scope :projection-path source-path :value-present false))
                  (let [fragment (get-in ctx [:source-list-fragments target])]
                    (require-form! (and (= :tail (:name site)) (false? (:required site)) (= :open (:tail fragment))
                                        (= field [(:tail-id fragment)]) (= target (:builder-id fragment))) :unproved-form-list-end)
                    (assoc row :expression-kind "value" :value [] :value-present true))))))
          (sort residual))))


(defn checked-form
  "Recheck the original callback and bind every form reader to the query's typed request."
  [package frozen owner]
  (let [{{:keys [context options] :as frozen} :frozen :keys [proof rows]} (callback/install frozen (get-in package [:closure-baselines callback/callback-id]))
        initial (analysis/analyze-effective-input context owner options)
        additions (receiver-rows context initial owner)
        ctx (reduce (fn [ctx row] (assoc-in ctx [:bindings (:id row)] row)) (assoc context :snapshot-id (random-uuid)) additions)
        checked (analysis/analyze-effective-input ctx owner options)]
    (require-form! (and (= :checked (:status checked))
                        (every? (fn [[path route]] (or (not= :input-field (:kind route)) (request-prefix? path)))
                                (get-in checked [:public-input :routes]))) :unproved-form-request)
    {:frozen (assoc frozen :context ctx) :proof (assoc proof :rows rows :request-rows additions)}))


(defn- form-decoder-source
  [package]
  (let [id (ids/fn-id "web.crud" :parse-form-body)
        source (original package id)
        {:keys [declaration fn-id]} (origins/child-at @(:inline-origins package) id [:args :then])
        guard-name (keyword (str "_form-text-" (migration/derived-id :form-body-text fn-id)))
        guard-id (ids/fn-id "web.crud" guard-name)
        guard {:namespace "web.crud" :name guard-name :parent :core.logic/ensure-type
               :args {:value :web.crud/_request-body :type {:value [:union :null :text]}}}]
    (require-form! (and (= {:parent :form-decode :args {:string :_request-body}} (get-in source [:args :then]))
                        (= fn-id (ids/fn-id (:namespace declaration) (:name declaration)))
                        (not (contains? (:declarations package) guard-id))) :unproved-form-decoder)
    {:forms [(assoc-in source [:args :then] (keyword "web.crud" (name (:name declaration))))
             (assoc-in declaration [:args :string] (keyword "web.crud" (name guard-name)))
             guard]
     :owner id :child fn-id :guard guard-id}))


(defn- query-and-form
  [package graph owner source child declaration expanded evidence]
  (let [outer (assoc-in source [:args :value] (keyword (:namespace declaration) (name (:name declaration))))
        child-forms (mapcat val (get-in expanded [:package :originals]))
        child-ids (set (map #(ids/fn-id (:namespace %) (:name %)) child-forms))
        decoder (form-decoder-source package)
        originals (set (keys (:fn-map graph)))
        retained (remove child-ids (disj originals owner (:owner decoder) (:child decoder)))
        support (keep (partial original package) retained)
        forms (vec (distinct (concat [outer] support child-forms (:forms decoder))))
        prepared (parse/prepare-module forms (get-in package [:prepared :name->id])
                                       (get-in package [:prepared :defs-by-name]))
        candidate (update (snapshot/package-index prepared forms) :originals #(merge (:originals package) %))]
    (when (and (= child (ids/fn-id (:namespace declaration) (:name declaration)))
               (every? #(migration/same-edn? (owner-rows package %) (owner-rows candidate %)) [owner (:owner decoder)]))
      (let [roots (:roots expanded)
            forwarded (for [id retained
                            :let [certificate (get-in package [:closure-baselines id])]
                            :when (and (some :source-names (vals (get-in certificate [:v2 :scoped-inputs])))
                                       (migration/same-edn? (owner-rows package id) (owner-rows candidate id)))]
                        certificate)
            normalized (-> candidate
                           (update :prepared #(reduce source/with-forwarded-parent % forwarded))
                           (assoc :closure-baselines
                                  (merge (select-keys (:closure-baselines package)
                                                      retained)
                                         (get-in expanded [:package :closure-baselines])))
                           (assoc :legacy-graph (readers/owner-index prepared child))
                           (snapshot/with-certified-owner child roots)
                           (assoc :legacy-graph (readers/owner-index prepared owner))
                           (snapshot/with-certified-owner (:guard decoder) roots)
                           (snapshot/with-certified-owner (:child decoder) roots)
                           (snapshot/with-certified-owner (:owner decoder) roots)
                           (snapshot/with-certified-owner callback/callback-id roots)
                           (snapshot/with-certified-owner owner roots))
            entry (get-in normalized [:closure-baselines owner])
            {:keys [frozen proof]} (checked-form normalized (snapshot/freeze-source-analysis normalized [owner] roots) owner)
            checked (analysis/analyze-effective-input (:context frozen) owner (:options frozen))]
        (when (and (= :authored-source-equivalent (:verification entry)) (= :checked (:status checked)))
          (-> expanded
              (assoc :original source :expanded outer :proposed (:proposed entry) :entry entry
                     :package normalized :visibility (set (keys (:declarations normalized))) :wrapper-origin {:query-child child})
              (assoc-in [:proof :form-callback] proof)
              (assoc-in [:proof :form-fingerprint] (:fingerprint evidence))
              (update :donors into (filter roots (:targets evidence)))
              (update-in [:proof :original-targets] into (:targets evidence))))))))


(defn prepare
  [package owner roots]
  (when-let [{:keys [parameter fingerprint]} (get reviewed owner)]
    (let [source (original package owner)]
      (when (original-body? source parameter)
        (let [graph (readers/owner-index (:prepared package) owner)
              {child :fn-id declaration :declaration} (origins/child-at @(:inline-origins package) owner [:args :value])
              package (assoc package :legacy-graph graph)
              evidence (query-source/closure-evidence graph owner)]
          (when (= fingerprint (:fingerprint evidence))
            (when-let [expanded (query/prepare (assoc-in package [:originals child] [declaration]) child roots)]
              (query-and-form package graph owner source child declaration expanded evidence))))))))
