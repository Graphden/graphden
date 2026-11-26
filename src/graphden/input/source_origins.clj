(ns graphden.input.source-origins
  "Offline provenance for parser-generated declarations, never authored names."
  (:require
    [graphden.input.migration :as migration]
    [graphden.packages.rebase :as rebase]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]))


(defn- reject!
  [code id]
  (throw (ex-info "Cannot identify the original inline owner"
                  {:type :input/source-origin :code code :fn-id id})))


(defn- definition-id
  [definition]
  (ids/fn-id (:namespace definition) (:name definition)))


(defn- prepared-definition
  [prepared {ns-id :namespace :keys [name]}]
  (get-in prepared [:defs-by-name (keyword (or ns-id "") (clojure.core/name name))]))


(defn- checked-expansion!
  [prepared definition]
  (let [expanded (parse/expand-inline-anons-in-module [definition])]
    (doseq [expected expanded]
      (let [actual (prepared-definition prepared expected)
            id (definition-id expected)]
        (when-not (and (= id (:fn-id actual))
                       (migration/same-edn? expected (dissoc actual :fn-id)))
          (reject! :inline-preparation-mismatch id))))
    expanded))


(declare declaration-origins)


(defn- value-origins
  [root owner path host value]
  (cond
    (and (map? value) (or (contains? value :parent) (contains? value :parents)))
    (let [child (parse/inline-declaration (dissoc value :type) host (:namespace owner))
          origin {:root-owner-id (definition-id root)
                  :enclosing-owner-id (definition-id owner)
                  :source-path path :declaration child}]
      (into [(assoc origin :fn-id (definition-id child))]
            (declaration-origins root child path)))

    (and (map? value) (contains? value :append))
    (value-origins root owner (conj path :append) host (:append value))

    (vector? value)
    (into [] (mapcat (fn [[position item]]
                       (value-origins root owner (conj path position) (conj host position) item)))
          (map-indexed vector value))

    :else []))


(defn- declaration-origins
  [root owner path]
  (into [] (mapcat (fn [[field value]]
                     (value-origins root owner (conj path :args field) [(:name owner) field] value)))
        (:args owner)))


(defn index
  "Index the exact normalized parser expansion of each original owner. This
   is provenance, not an authored-source or runtime proof. Invalid owners
   contribute no origins; unrelated valid owners remain usable. Literal maps
   are opaque, and sequence positions follow the legacy parser's identity.
   Private entries contain source payloads and must not be logged."
  [prepared originals]
  (let [owner-counts (frequencies (map definition-id originals))]
    (reduce (fn [origins original]
              (try
                (let [normalized (rebase/rewrite-references
                                   original #(parse/normalize-ref % (:name->id prepared) (:namespace original)))
                      _ (checked-expansion! prepared normalized)
                      ambiguous? (> (get owner-counts (definition-id original)) 1)
                      entries (cond->> (declaration-origins normalized normalized [])
                                ambiguous? (map #(assoc % :ambiguous-owner? true)))]
                  (reduce #(update %1 (:fn-id %2) (fnil conj []) %2) origins entries))
                (catch clojure.lang.ExceptionInfo failure
                  (if (#{:input/source-origin :packages/unresolved-ref :packages/ambiguous-ref}
                       (:type (ex-data failure)))
                    origins
                    (throw failure)))))
            {} originals)))


(defn declaration
  "Return one child only when its complete enclosing source expansion matches
   preparation. Duplicate originals or conflicting owners are never guessed."
  [origins id]
  (let [[origin & more] (get origins id)]
    (when (or (seq more) (:ambiguous-owner? origin)) (reject! :ambiguous-inline-owner id))
    origin))


(defn- argument-sites
  [path]
  (loop [start 0 sites []]
    (if (= start (count path))
      sites
      (when (and (= :args (get path start)) (simple-keyword? (get path (inc start))))
        (let [field-end (+ start 2)
              append? (= :append (get path field-end))
              position (if append? (inc field-end) field-end)
              item? (nat-int? (get path position))
              end (if item? (inc position) field-end)]
          (when (or (not append?) item?)
            (recur end (conj sites [field-end end]))))))))


(defn- blocked-sites
  [{:keys [code source-path]}]
  (when (and (vector? source-path) (< 2 (count source-path)))
    (case code
      :ref-scope-required (let [sites (argument-sites source-path)
                                [field-end end] (peek sites)]
                            (when (and (next sites) (= field-end end)) sites))
      :inherited-projection-required (when (= :args (peek source-path))
                                       (seq (argument-sites (pop source-path))))
      nil)))


(defn inline-binding-path
  "Locate the first exact child of a scoped binding blocker, including a
   vector or append item. The child still needs its frozen owner proof."
  [{:keys [source-path] :as blocker}]
  (when-let [[_ end] (first (blocked-sites blocker))]
    (subvec source-path 0 end)))


(defn inline-list-paths
  "Return each enclosing list field of a verified blocker path shape."
  [{:keys [source-path] :as blocker}]
  (into [] (keep (fn [[field-end end]]
                   (when (< field-end end) (subvec source-path 0 field-end))))
        (blocked-sites blocker)))


(defn child-at
  "Require an exact enclosing owner and direct argument path, relative to
   that owner's verified origin when it is itself an inline declaration."
  [origins owner path]
  (let [parent (declaration origins owner)
        root (get parent :root-owner-id owner)
        source-path (into (get parent :source-path []) path)
        [match & more] (into [] (comp cat (filter #(and (= root (:root-owner-id %))
                                                        (= owner (:enclosing-owner-id %))
                                                        (= source-path (:source-path %))))) (vals origins))]
    (when (seq more) (reject! :ambiguous-inline-owner owner))
    (when match (declaration origins (:fn-id match)))))
