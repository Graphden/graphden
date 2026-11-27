(ns graphden.input.query-lookup-package
  "Build and replay the reviewed query expansion against an original package."
  (:require
    [graphden.input.analysis :as analysis]
    [graphden.input.migration :as migration]
    [graphden.input.query-lookup-arguments :as arguments]
    [graphden.input.query-lookup-source :as query]
    [graphden.input.source :as source]
    [graphden.input.source-snapshot :as snapshot]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]))


(def ^:private dependencies
  [(ids/fn-id "core.refinements" :nullable-keyword)
   (ids/fn-id "core.refinements" :nullable-text)
   (ids/fn-id "core.logic" :coalesce)])


(defn- original-source
  [package id]
  (let [[form & others] (get-in package [:originals id])]
    (when-not (seq others) form)))


(defn prepare
  "Rebuild the trusted expanded package with the ordinary parser and
   checker. Compare its native result declaration and recover the exact
   five input paths. No result or callback is admitted from staged EDN."
  [{:keys [declarations legacy-graph] :as package} owner roots]
  (let [source (original-source package owner)]
    (when (contains? #{:query-param :web.crud/query-param} (:parent source))
      (when-let [{expanded :source :keys [chooser views proof]}
                 (query/expansion legacy-graph owner source roots (set (keys declarations)))]
        (let [support (mapv #(original-source package %) dependencies)]
          (when (every? some? support)
            (let [forms (into (conj support chooser expanded) (map :form) views)
                  native (into roots (map (juxt :id :signature)) views)
                  prepared (parse/prepare-module forms {} {})
                  normalized (snapshot/with-certified-owner (snapshot/package-index prepared forms) owner native)
                  entry (get-in normalized [:closure-baselines owner])
                  {:keys [context options]} (snapshot/freeze-source-analysis normalized [owner] native)
                  checked (analysis/analyze-effective-input context owner options)
                  adapter (arguments/adapter proof checked)
                  old-return (:return-type-id proof)]
              (when (and (= :authored-source-equivalent (:verification entry))
                         (= old-return (get-in context [:fns owner :return-type-fn-id]))
                         (= [:union :null :text] (get-in checked [:scopes [] :signature :return]))
                         adapter)
                {:original source :expanded expanded :proposed (:proposed entry) :entry entry
                 :package normalized :roots native :views views :proof proof :adapt adapter
                 :visibility (set (keys (:declarations normalized)))}))))))))


(defn replay
  "Recheck a staged single definition against the canonical proposal.
   Source spans are held by the enclosing module ledger."
  [{:keys [original proposed entry]} source draft]
  (when (and (migration/same-edn? (dissoc source :namespace :source-file :source-line)
                                  (dissoc original :namespace :source-file :source-line))
             (migration/same-edn? (dissoc draft :namespace :source-file :source-line)
                                  (dissoc proposed :namespace :source-file :source-line)))
    entry))


(defn certify
  "Register a complete original-source proof for an eligible owner. Keep
   executable preparation outside public report entries and preserve the
   complete original binding ledger for stage replay."
  [package id roots]
  (when-let [{:keys [original entry proof] :as expansion} (prepare package id roots)]
    (let [before (first (:definitions (source/plan-module [original] (:prepared package))))
          normalized (assoc entry :source original :identity id :kind :query-lookup
                            :origins (:origins before) :legacy (:legacy before)
                            :physical-source (select-keys proof [:binding-id :slot-id :write-mask :reads :return-type-id]))]
      (-> package
          (assoc-in [:closure-baselines id] normalized)
          (assoc-in [:query-lookups id] expansion)
          (assoc-in [:prepared ::source/normalized-verifiers id]
                    (fn [candidate]
                      (when (and (migration/same-edn? (:source normalized) (:source candidate))
                                 (migration/same-edn? (:proposed normalized) (:proposed candidate)))
                        normalized)))))))
