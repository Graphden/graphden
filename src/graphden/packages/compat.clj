(ns graphden.packages.compat
  "Breaking-change detection between two versions of a package — the
   check `:publish-package` runs before it trusts the author's semver.

   Input is the bundle surface both sides already have: the exported
   fn-def maps (`export/records->fn-defs` for the version being
   published, the stored `:package-version` row's `:fns` for the
   previous one). No graph read, no registry — pure over EDN, so the
   comparison is the same on a self-host, in the cloud and in a unit
   test.

   What counts as breaking is what would make a CONSUMER of the old
   version fail against the new one without touching their own graph —
   a caller's binding no longer fits, a name they reference is gone,
   a value they read has another shape:

   | kind                   | old → new                                              |
   |------------------------|--------------------------------------------------------|
   | `:fn-removed`          | a public fn-def disappeared                            |
   | `:role-changed`        | base-fn ↔ composed ↔ type-row                          |
   | `:parents-changed`     | a composed fn-def's parent set differs                 |
   | `:arg-removed`         | a declared slot / record field is gone                 |
   | `:arg-required-added`  | a new declared slot / field without `:required false`  |
   | `:arg-narrowed`        | a slot's type no longer accepts the old type           |
   | `:arg-unbound`         | a composed fn-def's binding is gone → a new free arg   |
   | `:arg-renamed`         | an `{:as …}` public name changed                       |
   | `:return-widened`      | the return type is not a subtype of the old one        |
   | `:effect-added`        | a base-fn declares an effect it did not before         |
   | `:type-changed`        | a refine / list / union / … type-row's shape differs   |
   | `:dependency-incompatible` | a package dependency left its previous caret range (`incompatible-dependency-bumps`) |

   Private fn-defs (`_`-prefixed) are implementation and never count.
   Additions — a new fn-def, a new OPTIONAL slot, a binding that fills a
   formerly free arg, a WIDER arg type, a NARROWER return type — are
   compatible. Subtyping is `types/subtype?`; a type the checker cannot
   compare (an alias the registry does not know) falls back to
   structural equality, which errs on the side of reporting.

   What this cannot see: a change in a fn-def OUTSIDE the bundle (another
   package's, the platform's) that flows into a composed fn-def's free
   args. The bundle records which PACKAGE versions those come from, and a
   dependency that jumped outside its previous caret range is reported
   (`incompatible-dependency-bumps`); a platform upgrade is not."
  (:require
    [clojure.string :as str]
    [graphden.packages.semver :as semver]
    [graphden.types.core :as types]))


(def ^:private binding-marker-keys
  "Map keys that make a composed fn-def's arg-value a BINDING (as
   opposed to a PB' own-slot declaration, which carries only `:type` /
   `:required` / `:description`)."
  #{:ref :value :as :append :closed :secret-path :resolver :terminal})


(defn- public-name?
  [d]
  (let [n (some-> (:name d) name)]
    (and n (not (str/starts-with? n "_")))))


(defn- slot-spec
  "`{:type T :required? B}` for a declared slot's surface form — a bare
   type or the explicit `{:type T :required B}` map."
  [v]
  (if (and (map? v) (contains? v :type))
    {:type (:type v) :required? (not (false? (:required v)))}
    {:type v :required? true}))


(defn- declared-slots
  [args]
  (into {} (map (fn [[k v]] [k (slot-spec v)])) args))


(def ^:private type-row-keys
  [:refine :list :union :map :tuple :variant :fn-type :marker])


(defn- composed-signature
  [d]
  (let [args (:args d)
        own-slot? (fn [v]
                    (and (map? v) (contains? v :type)
                         (not-any? #(contains? v %) binding-marker-keys)))
        rename? (fn [k v] (and (map? v) (keyword? (:as v)) (not= (:as v) k)))]
    {:role :composed
     :parents (set (or (:parents d) (some-> (:parent d) vector)))
     :slots (declared-slots (into {} (filter (fn [[_ v]] (own-slot? v))) args))
     :bound (set (keep (fn [[k v]] (when-not (or (own-slot? v) (rename? k v)) k)) args))
     :renames (into {} (keep (fn [[k v]] (when (rename? k v) [k (:as v)]))) args)}))


(defn signature
  "The consumer-facing contract of one exported fn-def map."
  [d]
  (cond
    (seq (or (:parents d) (some-> (:parent d) vector)))
    (composed-signature d)

    (contains? d :return-type)
    {:role :base-fn
     :slots (declared-slots (:args d))
     :return (:return-type d)
     :effects (set (:effects d))}

    (contains? d :type)
    {:role :record
     :slots (declared-slots (:type d))}

    :else
    {:role :type
     :shape (select-keys d type-row-keys)}))


(defn- subtype?*
  "`types/subtype?` that never throws — an incomparable pair (unknown
   alias, malformed form) is compatible only when structurally equal."
  [sub sup]
  (try (types/subtype? sub sup)
       (catch Exception _ (= sub sup))))


(defn- change
  [kind fn-name & {:as more}]
  (merge {:kind kind :fn fn-name} more))


(defn- slot-changes
  [fn-name old-slots new-slots]
  (concat
    (for [[k o] (sort-by (comp str key) old-slots)
          :let [n (get new-slots k)]
          :when (or (nil? n) (not (subtype?* (:type o) (:type n))))]
      (if (nil? n)
        (change :arg-removed fn-name :arg k :old (:type o))
        (change :arg-narrowed fn-name :arg k :old (:type o) :new (:type n))))
    (for [[k n] (sort-by (comp str key) new-slots)
          :when (and (not (contains? old-slots k)) (:required? n))]
      (change :arg-required-added fn-name :arg k :new (:type n)))))


(defn- fn-changes
  [fn-name old new]
  (if (not= (:role old) (:role new))
    [(change :role-changed fn-name :old (:role old) :new (:role new))]
    (concat
      (when (and (= :composed (:role old)) (not= (:parents old) (:parents new)))
        [(change :parents-changed fn-name :old (vec (sort (:parents old)))
                 :new (vec (sort (:parents new))))])
      (slot-changes fn-name (:slots old) (:slots new))
      (when (= :composed (:role old))
        (concat
          (for [k (sort-by str (:bound old))
                :when (not (or (contains? (:bound new) k)
                               (contains? (:slots new) k)))]
            (change :arg-unbound fn-name :arg k))
          (for [[k as] (sort-by (comp str key) (:renames old))
                :when (not= as (get (:renames new) k))]
            (change :arg-renamed fn-name :arg k :old as :new (get (:renames new) k)))))
      (when (and (= :base-fn (:role old))
                 (not (subtype?* (:return new) (:return old))))
        [(change :return-widened fn-name :old (:return old) :new (:return new))])
      (when (and (= :base-fn (:role old))
                 (seq (remove (:effects old) (:effects new))))
        [(change :effect-added fn-name
                 :old (vec (sort (:effects old))) :new (vec (sort (:effects new))))])
      (when (and (= :type (:role old)) (not= (:shape old) (:shape new)))
        [(change :type-changed fn-name :old (:shape old) :new (:shape new))]))))


(defn breaking-changes
  "Every consumer-visible incompatibility going from `old-fns` to
   `new-fns` (two seqs of exported fn-def maps), as
   `[{:kind … :fn … (:arg …) (:old …) (:new …)} …]` — empty when the
   new version is a compatible successor. Fn-defs are matched by
   `[namespace name]`; `:fn` in a change is the bare name. Order is
   deterministic (fn-defs by namespace+name, args by name) so the list
   is stable across runs — the publisher's response and a test can be
   compared verbatim."
  [old-fns new-fns]
  (let [key-of (juxt #(some-> (:namespace %) str) :name)
        index (fn [fns] (into {} (map (juxt key-of identity)) (filter public-name? fns)))
        old (index old-fns)
        new (index new-fns)]
    (vec
      (mapcat (fn [[k d]]
                (if-let [n (get new k)]
                  (fn-changes (:name d) (signature d) (signature n))
                  [(change :fn-removed (:name d))]))
              (sort-by (comp str first) old)))))


(defn incompatible-dependency-bumps
  "Package dependencies (`[{:name … :version …} …]`, a bundle's
   `:package-dependencies`) whose new version is outside the old one's
   caret range — the consumer's own pin on the upstream may no longer
   resolve, and the upstream may have broken what this bundle re-exports.
   A dependency added or dropped is not a break by itself."
  [old-deps new-deps]
  (let [version-of (fn [deps] (into {} (map (juxt #(str (:name %)) #(str (:version %)))) deps))
        old (version-of old-deps)
        new (version-of new-deps)]
    (vec
      (for [[n old-v] (sort-by key old)
            :let [new-v (get new n)]
            :when (and new-v (not= old-v new-v)
                       (not (semver/satisfies-constraint? new-v (str "^" old-v))))]
        {:kind :dependency-incompatible :name n :old old-v :new new-v}))))
