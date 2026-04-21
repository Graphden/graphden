(ns graphden.executor.composition.validation
  "Pre-sync validation of fn-def shape: each def needs :name, :parent or
   :parents, well-formed :args, and no duplicate names across the set.")


(defn- validate-fn-def!
  "Validates a single fn definition."
  [{parent :parent parent-list :parents args :args :as fn-def}]
  (let [fn-name (:name fn-def)]
    (when-not fn-name
      (throw (ex-info "fn-def must have :name"
                      {:type :fn-composition/invalid-def
                       :fn-def fn-def})))
    (when-not (keyword? fn-name)
      (throw (ex-info "fn-def :name must be a keyword"
                      {:type :fn-composition/invalid-def
                       :name fn-name})))
    (when-not (or parent (seq parent-list))
      (throw (ex-info (str "fn-def " fn-name " must have :parent (single) or :parents (vector)")
                      {:type :fn-composition/invalid-def
                       :fn-def fn-def})))
    (when (and parent-list (not (sequential? parent-list)))
      (throw (ex-info (str "fn-def " fn-name " :parents must be a vector/list of keywords")
                      {:type :fn-composition/invalid-def
                       :fn-def fn-def})))
    (when (and args (not (map? args)))
      (throw (ex-info (str "fn-def " fn-name " :args must be a map")
                      {:type :fn-composition/invalid-def
                       :fn-def fn-def})))))


(defn validate-all-defs!
  "Validates all fn definitions before sync."
  [fn-defs]
  (when-not (sequential? fn-defs)
    (throw (ex-info "fn-defs must be a vector/list"
                    {:type :fn-composition/invalid-defs
                     :fn-defs-type (type fn-defs)})))
  ;; Single-pass duplicate detection with early termination capability
  (let [duplicates (loop [remaining (map :name fn-defs)
                          seen #{}
                          dups #{}]
                     (if-let [n (first remaining)]
                       (if (contains? seen n)
                         (recur (rest remaining) seen (conj dups n))
                         (recur (rest remaining) (conj seen n) dups))
                       dups))]
    (when (seq duplicates)
      (throw (ex-info "Duplicate fn names in definitions"
                      {:type :fn-composition/duplicate-names
                       :duplicates (vec duplicates)}))))
  (doseq [fn-def fn-defs]
    (validate-fn-def! fn-def)))
