(ns graphden.executor.composition.validation
  "Pre-sync validation of fn-def shape (post-rewrite).

   Each fn-def is one of:
   - base-fn       (`:args` declaration, impl in impls.clj)
   - record-type   (`:type {…}`)
   - refinement    (`:refine {:base T :constraint C}`)
   - list-type     (`:list T`)
   - composed      (`:parent` set, optional `:args` for bindings)

   Validation checks: name is a keyword, exactly one role-marker is
   present (or none → pure base-fn / args-only), names are unique
   within the input set.")


(defn- role-markers
  [{:keys [parent refine union variant args]
    type-marker :type
    list-marker :list}]
  (cond-> #{}
    parent      (conj :parent)
    type-marker (conj :type)
    refine      (conj :refine)
    list-marker (conj :list)
    union       (conj :union)
    variant     (conj :variant)
    ;; :args alone (no other markers) → base-fn / inline composite
    (and args (not (or parent type-marker refine list-marker union variant)))
    (conj :args)))


(defn- validate-fn-def!
  "Validates a single fn-def."
  [{fn-name :name :as fn-def}]
  (when-not fn-name
    (throw (ex-info "fn-def must have :name"
                    {:type :fn-composition/invalid-def
                     :fn-def fn-def})))
  (when-not (keyword? fn-name)
    (throw (ex-info "fn-def :name must be a keyword"
                    {:type :fn-composition/invalid-def
                     :name fn-name})))
  (let [markers (role-markers fn-def)]
    (when (> (count markers) 1)
      (throw (ex-info (str "fn-def " fn-name
                           " has conflicting role markers — at most one of "
                           ":parent :type :refine :list (plus :args for base-fns)")
                      {:type :fn-composition/conflicting-roles
                       :fn-name fn-name
                       :markers markers})))
    (when (and (:type fn-def) (not (map? (:type fn-def))))
      (throw (ex-info (str "fn-def " fn-name " :type must be a map of {field-name field-type}")
                      {:type :fn-composition/invalid-def :fn-def fn-def})))
    (when (and (:refine fn-def) (not (map? (:refine fn-def))))
      (throw (ex-info (str "fn-def " fn-name " :refine must be {:base T :constraint C}")
                      {:type :fn-composition/invalid-def :fn-def fn-def})))
    (when (and (:args fn-def) (not (map? (:args fn-def))))
      (throw (ex-info (str "fn-def " fn-name " :args must be a map")
                      {:type :fn-composition/invalid-def :fn-def fn-def})))))


(defn validate-all-defs!
  "Validates all fn-defs before sync."
  [fn-defs]
  (when-not (sequential? fn-defs)
    (throw (ex-info "fn-defs must be a vector/list"
                    {:type :fn-composition/invalid-defs
                     :fn-defs-type (type fn-defs)})))
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
