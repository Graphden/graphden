(ns graphden.editor.root-compatibility
  "Read-only adapter for pre-root personal component selections. All selected
   source is checked in the root request snapshot before mechanical projection."
  (:require
    [clojure.set :as set]
    [graphden.editor.component-config :as config]
    [graphden.editor.components :as components]
    [graphden.editor.config-resolution :as resolution]
    [graphden.editor.root-legacy :as legacy]
    [graphden.executor.browser-plan :as browser]
    [graphden.executor.browser-snapshot :as snapshot]
    [graphden.executor.browser-source :as source]
    [graphden.packages.records.ids :as ids]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tenancy]))


(def ^:private default-root (ids/fn-id "app.ui-config" :editor-root))
(def ^:private get-id (str (ids/fn-id "core.collections" :get)))
(def ^:private const-id (str (ids/fn-id "core.logic" :const)))


(defn- reject!
  [reason]
  (throw (ex-info "Personal root adapter is unavailable"
                  {:type :browser-plan/unsupported :reason reason})))


(defn capture-selection
  "Capture the authenticated ordinary or legacy component selection, its
   source, and the owner preference state for an explicit migration."
  [storage authorize! policy]
  (let [current (resolution/capture storage authorize! policy)]
    (if (not= resolution/defaults-id (:id current))
      current
      (let [rows (sp/query-entities storage :ui-pref
                                    {:owner-id (tenancy/current-user-id) :key "components"} {:limit 2})]
        (when (> (count rows) 1) (reject! :invalid-selection))
        (if (some? (:value (first rows)))
          (let [id (components/selected-id-from-rows storage rows)
                row (sp/read-entity storage :fn id)]
            (when-not row (reject! :source-missing))
            (authorize! row {:execute? true})
            (let [graph (source/collect-manifest storage id authorize!)
                  captured {:graph graph :namespaces (source/collect-namespaces storage graph)}]
              (snapshot/validate-source! captured id policy)
              (-> current
                  (assoc :id id :configuration (config/configuration graph id)
                         :snapshot captured
                         :legacy-preference {:present true :value (:value (first rows))})
                  (dissoc :revision))))
          (assoc current :legacy-preference {:present (boolean (first rows))
                                             :value (:value (first rows))}))))))


(defn- literal
  [value]
  {:kind "literal" :value (browser/encode-value value)})


(defn- call
  [id]
  {:kind "call" :fn id :renames []})


(defn- argument
  [name expr]
  {:name name :slot name :expr expr})


(defn- wrapper
  "Project a unary envelope into the old checked entry's public arguments.
   These nodes use only generic get/const/call; they add no source authority."
  [target role input allowed]
  (let [accepted (set (:accepted input))
        required (set (:required input))
        ;; A HOF capture can be required without being a public lambda parameter.
        names (sort (set/intersection allowed (set/union accepted required)))
        id (str (ids/fn-id "graphden.editor.root-compatibility" (str target "/" (name role))))
        getter-id #(str (ids/fn-id id %))]
    (when-not (set/subset? required allowed)
      (reject! :legacy-callback-inputs))
    {:id id
     :functions
     (conj (mapv (fn [n]
                   {:id (getter-id n) :primitive get-id :aliases [] :env []
                    :args [(argument "coll" {:kind "read" :slot (str id "/input") :name "input"})
                           (argument "key" (literal (keyword n)))
                           (argument "default" (literal nil))]}) names)
           {:id id :primitive const-id :aliases []
            :env (mapv (fn [n] {:name n :slot (str id "/" n) :expr (call (getter-id n))}) names)
            :args [(argument "value" (call target))]})}))


(defn- merge-rows
  [left right]
  (let [by-id (into {} (map (juxt :id identity)) left)]
    (doseq [row right :let [prior (get by-id (:id row))] :when prior]
      (when-not (= row prior) (reject! :legacy-plan-conflict)))
    (into (vec left) (remove #(contains? by-id (:id %))) right)))


(defn- replace-callback
  [plan old-target target role personal allowed]
  (let [input (get-in personal [:inputs target])
        _ (when-not input (reject! :legacy-entry-missing))
        {:keys [id functions]} (wrapper target role input allowed)
        old-ids (set (map :id (:functions plan)))
        _ (when (some #(contains? old-ids (:id %)) functions) (reject! :legacy-plan-conflict))
        replace-expr (fn [expr]
                       (if (and (= "closure" (:kind expr)) (= old-target (:fn expr)))
                         (assoc expr :fn id :lambdaParams ["input"] :translation [])
                         expr))]
    (-> plan
        (update :functions #(mapv (fn [f]
                                    (update f :args
                                            (fn [args] (mapv (fn [arg] (update arg :expr replace-expr)) args)))) %))
        (update :functions merge-rows (:functions personal))
        (update :functions into functions)
        (update :primitives merge-rows (:primitives personal))
        (update :primitives merge-rows [{:id get-id :op "get"} {:id const-id :op "const"}]))))


(defn adapt-root
  "Preserve existing personal selections only for the shipped default root.
   Custom root bindings remain authoritative. Caller owns snapshot/epoch checks."
  [ctx storage authorize! policy root plan]
  (if (or (not= default-root root)
          (nil? (tenancy/current-user-id)))
    {:plan plan}
    (let [{:keys [id configuration]} (capture-selection storage authorize! policy)
          result
          (reduce-kv
            (fn [plan component {path :namespace :keys [roles inputs]}]
              (let [entries (get configuration component)
                    defaults (into {} (for [role (or roles [:initial :update :view])]
                                        [role (legacy/default-role-id component role)]))
                    selected (into {} (remove (fn [[role id]] (= id (get defaults role)))) entries)]
                (if (empty? selected)
                  plan
                  (let [graph (source/collect-closure storage (vals selected) authorize!)
                        captured {:graph graph :namespaces (source/collect-namespaces storage graph)}
                        personal (snapshot/export-snapshot captured (:base-fns ctx) selected policy)]
                    (reduce-kv
                      (fn [plan role target]
                        (let [old (ids/fn-id path (str "root-" (name role)))]
                          (replace-callback plan (str old) (str target) role personal (get inputs role))))
                      plan selected)))))
            plan legacy/components)]
      (when (> (count (:functions result)) browser/max-functions)
        (reject! :function-limit))
      {:plan result :component-selection-id id})))


(defn adapt-plan
  "Compatibility entry for callers requiring only the adapted plan."
  [ctx storage authorize! policy root plan]
  (:plan (adapt-root ctx storage authorize! policy root plan)))
