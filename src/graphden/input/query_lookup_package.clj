(ns graphden.input.query-lookup-package
  "Build and replay the reviewed query expansion against an original package."
  (:require
    [graphden.input.analysis :as analysis]
    [graphden.input.migration :as migration]
    [graphden.input.query-lookup-source :as query]
    [graphden.input.source :as source]
    [graphden.input.source-legacy-readers :as readers]
    [graphden.input.source-snapshot :as snapshot]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]))


(def ^:private dependencies
  [(ids/fn-id "core.refinements" :nullable-keyword)
   (ids/fn-id "core.refinements" :nullable-text)
   (ids/fn-id "core.refinements" :text-map)
   (ids/fn-id "core.refinements" :path-segment)
   (ids/fn-id "web.ring-adapter" :ring-request-shape)])


(defn- unique-original
  [package id]
  (let [[form & others] (get-in package [:originals id])]
    (when-not (seq others) form)))


(defn prepare
  "Rebuild the trusted expanded package with the ordinary parser and
   checker. Compare its native result declaration and recover the exact
   required Ring request field. No result or callback is admitted from staged EDN."
  [{:keys [declarations legacy-graph] :as package} owner roots]
  (let [source (unique-original package owner)]
    (when (contains? #{:query-param :web.crud/query-param} (:parent source))
      (when-let [{expanded :source :keys [helpers donors proof]}
                 (query/expansion (or legacy-graph (readers/owner-index (:prepared package) owner)) owner source roots (set (keys declarations)))]
        (let [support (mapv #(unique-original package %) (into dependencies donors))]
          (when (every? some? support)
            (let [forms (into (conj support expanded) helpers)
                  prepared (parse/prepare-module forms {} {})
                  original-graph (readers/owner-index prepared owner)
                  normalized (snapshot/with-certified-owner (assoc (snapshot/package-index prepared forms) :legacy-graph original-graph) owner roots)
                  entry (get-in normalized [:closure-baselines owner])
                  {:keys [context options]} (snapshot/freeze-source-analysis normalized [owner] roots)
                  checked (analysis/analyze-effective-input context owner options)
                  input (get-in checked [:public-input :routes [:request]])
                  old-return (:return-type-id proof)]
              (when (and (= :authored-source-equivalent (:verification entry))
                         (= old-return (get-in context [:fns owner :return-type-fn-id]))
                         (= [:union :null :text] (get-in checked [:scopes [] :signature :return]))
                         (= :checked (:status checked)) (= :input-field (:kind input)) (true? (:required input))
                         (= [:request] (mapv :name (get-in checked [:public-input :root :fields])))
                         (= (:request-type-id proof) (get-in context [:fields (:origin-field-id input) :type-fn-id])))
                {:original source :expanded expanded :proposed (:proposed entry) :entry entry
                 :package normalized :roots roots :donors donors :proof proof
                 :generated-ids (mapv #(ids/fn-id (:namespace %) (:name %)) helpers)
                 :visibility (set (keys (:declarations normalized)))}))))))))


(defn cached
  "A prepared normalized program is bound to the same frozen native
   signatures, rules and capabilities as this invocation."
  [package id roots]
  (when-let [{frozen :roots :keys [donors] :as expansion} (get-in package [:query-lookups id])]
    (when-not (every? #(= (get roots %) (get frozen %)) donors)
      (throw (ex-info "Normalized source requires its original native signatures"
                      {:type :input/source-snapshot :code :native-view-source-mismatch :fn-id id})))
    expansion))


(defn replay
  "Recheck a staged single definition against the canonical proposal.
   Source spans are held by the enclosing module ledger."
  [{:keys [original proposed entry]} source draft]
  (when (and (migration/same-edn? source original)
             (migration/same-edn? draft proposed))
    entry))


(defn certify
  "Register a complete original-source proof for an eligible owner. Keep
   executable preparation outside public report entries and preserve the
   complete original binding ledger for stage replay."
  ([package id roots] (certify package id roots prepare))
  ([package id roots prepare-expansion]
   (when-let [{:keys [original entry proof wrapper-origin] :as expansion} (prepare-expansion package id roots)]
     (let [before (first (:definitions (source/plan-module [original] (:prepared package))))
           physical (select-keys proof [:owner-id :binding-id :slot-id :write-mask :reads :return-type-id :form-fingerprint :form-callback :response-reader :response-producer :normalized-reader :normalized-producer])
           normalized (assoc entry :source original :identity id
                             :kind (or (:kind expansion) (if wrapper-origin :query-lookup-inline :query-lookup))
                             :origins (:origins before) :legacy (:legacy before)
                             :physical-source (cond-> physical (seq wrapper-origin) (assoc :wrapper-origin wrapper-origin)))]
       (-> package
           (assoc-in [:closure-baselines id] normalized)
           (assoc-in [:query-lookups id] expansion)
           (assoc-in [:prepared ::source/normalized-verifiers id]
                     (fn [candidate]
                       (when (replay expansion (:source candidate) (:proposed candidate))
                         normalized))))))))
