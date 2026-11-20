(ns structured-input-list-probe
  "Read-only, field-level CST evidence for source-derived list fragments."
  (:require
    [graphden.input.list-builder :as builder]
    [graphden.input.migration :as migration]
    [graphden.input.source-list :as source-list]
    [structured-input-cst :as cst]
    [structured-input-inventory :as inventory]))


(defn- candidates
  [name->id {:keys [source legacy]}]
  (keep (fn [{:keys [source-path list-builder]}]
          (when-let [fragment (peek (:fragments list-builder))]
            [(cst/list-builder-operation source (second source-path) fragment name->id) fragment]))
        (:arguments legacy)))


(defn- verify-file
  [read-source file entries name->id]
  (let [ops (vec (mapcat (partial candidates name->id) entries))]
    (when (seq ops)
      (let [text (read-source file)
            module-ns (get-in (first entries) [:source :namespace])
            patch (cst/plan text {:namespace module-ns :operations (mapv first ops)})
            after (:text (cst/apply-text patch text))
            by-name (into {} (map (juxt :name identity)) (:fns (inventory/read-edn file after)))]
        (when-not (= :already-applied (:status (cst/apply-text patch after)))
          (throw (ex-info "List patch is not idempotent" {:code :list-cst-not-idempotent})))
        (doseq [[operation fragment] ops]
          (let [form (get-in (get by-name (:definition operation)) (:path operation))
                names (source-list/field-names name->id module-ns (:original operation))
                result (builder/parse-fragment (:binding-id fragment) form names)]
            (when-not (migration/same-edn? (dissoc fragment :owner-id :target-id) result)
              (throw (ex-info "List item identity changed" {:code :list-item-origin-mismatch})))))
        (frequencies (map (comp :definition first) ops))))))


(defn verify-package
  "Reparse changed spans in original checked files. Return only counts
   by definition and diagnostics; never emit source, expressions, or values."
  [read-source entries name->id]
  (reduce-kv (fn [result file ds]
               (try
                 (if-let [counts (verify-file read-source file ds name->id)]
                   (assoc-in result [:verified file] counts)
                   result)
                 (catch Exception error
                   (update result :blockers conj {:code :list-cst-verification-failed :file file
                                                  :cause (or (:code (ex-data error)) (:reason (ex-data error)))}))))
             {:verified {} :blockers []} (group-by #(get-in % [:source :source-file]) entries)))
