(ns graphden.system.demo-branches
  "Idempotent seeder for demo branches — used by the dev system config
   to populate a couple of pre-baked branches so the versioning UI
   has something to demo (diff, merge, switching) without manual
   set-up after `bb deploy` / `bb rebuild`.

   Each branch declaration is a map:

     {:name        \"demo-feature-edits\"
      :description \"What this branch is showing off\"
      :base        \"main\"            ;; optional, default main
      :mutations
      [{:type :update-fn-description
        :fn-name \"add\"
        :description \"Sum a vector of numbers (edit on demo)\"}

       {:type :create-fn
        :name \"demo-double\"
        :parent \"add\"
        :description \"add-style child created only on this branch\"}]}

   Idempotency: a branch with a matching `:name` is left untouched
   (we assume the prior sync handled it). To re-seed, delete the
   branch via the UI or run `bb deploy` (clean DB).

   Currently shipped mutation kinds — keep this list narrow; add more
   only when a concrete demo needs them, and document each here so
   the dev-config EDN stays readable:

   `:update-fn-description`  set the fn's :description.
   `:create-fn`              create a composed fn (`:parent`-based,
                             optional :description, no bindings yet).

   `:fn-name`/`:parent` reference fns by their globally-unique
   `:name`. Resolution happens on the branch's storage so inherited
   names are visible."
  (:require
    [clojure.tools.logging :as log]
    [graphden.crud.fn-execution.lookup :as fn-lookup]
    [graphden.storage.protocol.core :as sp]
    [graphden.versioning.storage.core :as vs]))


(defn- resolve-fn-by-name
  "Look up a fn by its globally-unique name. Returns the resolved
   entity row (or nil). Seeder path — swallow all ExceptionInfo
   (not just `:validation-error/type-mismatch`) since this runs
   during early startup where some storage states may not exist
   yet."
  [storage fn-name]
  (fn-lookup/query-fn-by-name storage fn-name true))


(defmulti apply-mutation!
  "Dispatch on `:type` of the mutation map. Each handler receives a
   versioned storage already pointing at the demo branch — its job
   is to call CRUD ops to land the mutation there."
  {:arglists '([storage mutation])}
  (fn [_storage mutation] (:type mutation)))


(defmethod apply-mutation! :default
  [_ mutation]
  (log/warn "Unknown demo-branch mutation type — skipping" mutation))


(defmethod apply-mutation! :update-fn-description
  [storage {:keys [fn-name description]}]
  (if-let [target (resolve-fn-by-name storage fn-name)]
    (do (sp/update-entity storage :fn (:id target) {:description description})
        (log/info "[demo-branches] updated description of" fn-name))
    (log/warn "[demo-branches] :update-fn-description — fn not found:" fn-name)))


(defmethod apply-mutation! :create-fn
  [storage {fn-name :name :keys [parent description]}]
  (if-let [parent-fn (resolve-fn-by-name storage parent)]
    (let [created (sp/create-entity storage :fn
                                    (cond-> {:name fn-name
                                             :parent-ids [(:id parent-fn)]}
                                      description (assoc :description description)))]
      (log/info "[demo-branches] created fn" fn-name "with parent" parent
                "→" (:id created)))
    (log/warn "[demo-branches] :create-fn — parent not found:" parent)))


(defn- seed-one!
  [main-storage {branch-name :name :keys [base description mutations] :as decl}]
  (let [base-storage (vs/unwrap main-storage)
        existing (first (sp/query-entities base-storage :branch {:name branch-name}))]
    (cond
      (nil? branch-name)
      (log/warn "[demo-branches] branch declaration missing :name — skipping" decl)

      (some? existing)
      (log/info "[demo-branches] branch already exists, leaving as-is:" branch-name)

      :else
      (let [parent-name (or base "main")
            parent (first (sp/query-entities base-storage :branch {:name parent-name}))]
        (if-not parent
          (log/warn "[demo-branches] base branch not found, skipping:" parent-name)
          (let [branch (vs/create-branch! main-storage branch-name
                                          {:base-branch-id (:id parent)})
                branch-storage (vs/switch-branch main-storage (:id branch))]
            (log/info "[demo-branches] created branch" branch-name
                      (when description (str "— " description)))
            (doseq [m mutations]
              (try (apply-mutation! branch-storage m)
                   (catch Exception e
                     (log/error e "[demo-branches] mutation failed on branch"
                                branch-name "mutation:" m))))))))))


(defn seed!
  "Ensure every declared demo branch exists. Idempotent — skips
   branches whose name is already present in storage.

   `:main-storage` is the wrapper bound to the default branch (the
   one `:db/versioned` constructs). `:branches` is the declaration
   vector — see the ns docstring for the shape."
  [main-storage branches]
  (when (seq branches)
    (log/info "[demo-branches] seeding" (count branches) "demo branch(es)")
    (doseq [decl branches]
      (seed-one! main-storage decl))))
