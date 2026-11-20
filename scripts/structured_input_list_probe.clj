(ns structured-input-list-probe
  "Read-only CST evidence for candidate definitions and source-derived list fragments."
  (:require
    [graphden.input.list-builder :as builder]
    [graphden.input.migration :as migration]
    [graphden.input.source :as source]
    [graphden.input.source-list :as source-list]
    [structured-input-cst :as cst]
    [structured-input-inventory :as inventory]))


(defn- candidates
  [name->id {:keys [source legacy]}]
  (keep (fn [{:keys [source-path list-builder list-inline-items]}]
          (when-let [fragment (peek (:fragments list-builder))]
            [(cst/list-builder-operation source (second source-path) fragment name->id list-inline-items)
             fragment list-inline-items]))
        (:arguments legacy)))


(defn- check-field!
  [by-name module-ns name->id migrated [operation fragment inline-items]]
  (let [name (:definition operation)
        path (cond-> (:path operation) (contains? migrated name) (assoc 0 :bind))
        form (get-in (get by-name name) path)
        names (source-list/field-names name->id module-ns (:original operation))
        result (builder/parse-fragment (:binding-id fragment) form names (:owner-id fragment) inline-items)]
    (when-not (migration/same-edn? (dissoc fragment :owner-id :target-id) result)
      (throw (ex-info "List item identity changed" {:code :list-item-origin-mismatch})))))


(defn- verify-file
  [read-source file entries name->id]
  (let [ops (vec (mapcat (partial candidates name->id) entries))]
    (when (seq ops)
      (let [whole (filter #(seq (get-in % [:v2 :list-builders])) entries)
            migrated (into #{} (map #(get-in % [:source :name])) whole)
            edits (into (into [] (mapcat cst/entry-operations) whole)
                        (comp (map first) (remove #(contains? migrated (:definition %)))) ops)
            text (read-source file)
            module-ns (get-in (first entries) [:source :namespace])
            patch (cst/plan text {:namespace module-ns :operations edits})
            after (:text (cst/apply-text patch text))
            by-name (into {} (map (juxt :name identity)) (:fns (inventory/read-edn file after)))]
        (when-not (= :already-applied (:status (cst/apply-text patch after)))
          (throw (ex-info "List patch is not idempotent" {:code :list-cst-not-idempotent})))
        (doseq [op ops] (check-field! by-name module-ns name->id migrated op))
        {:fields (frequencies (map (comp :definition first) ops))
         :entries (mapv (fn [entry]
                          (let [{source-ns :namespace :keys [name source-file]} (:source entry)]
                            (assoc entry :proposed (assoc (get by-name name) :namespace source-ns :source-file source-file))))
                        whole)}))))


(defn verify-package
  "Reparse changed spans in original checked files. Submit whole eligible
   definitions from these bytes to the same legacy/v2 meaning comparison."
  [read-source entries context]
  (let [files (group-by #(get-in % [:source :source-file]) entries)
        reports (reduce-kv
                  (fn [result file ds]
                    (try
                      (if-let [report (verify-file read-source file ds (:name->id context))]
                        (-> result (assoc-in [:verified file] (:fields report)) (update :entries into (:entries report)))
                        result)
                      (catch Exception error
                        (update result :blockers conj {:code :list-cst-verification-failed :file file
                                                       :cause (or (:code (ex-data error)) (:reason (ex-data error)))}))))
                  {:verified {} :entries [] :blockers []} files)
        replay (source/verify-module {:definitions (:entries reports)} context)]
    (assoc (dissoc reports :entries)
           :migrated (into #{} (keep #(when (= :supported (:status %)) (:identity %))) (:definitions replay))
           :blockers (into (:blockers reports) (:blockers replay)))))
