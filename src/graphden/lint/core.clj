(ns graphden.lint.core
  "Graph linters — structural rules over a set of EDN-shape fn-defs.

   The input is the same map shape the package loader produces
   (`:name` / `:namespace` / `:parent` or `:parents` / `:args` /
   `:return-type` …) and `crud/type-check/reconstruct-fn-def` rebuilds
   from DB rows, so one engine serves both authoring worlds: the
   fns.edn corpus at CI time (`graphden.lint.corpus`) and, later, the
   editor's per-branch graph.

   Everything here is pure and name-free: a fn-def's *signature* is
   its structure with every reference resolved to an identity
   (`[ns name]` for fn-defs, the bare name for base-fns) and every
   documentation field dropped. Two fn-defs with equal signatures are
   the same definition written twice.

   Rules (severity in parentheses):

   - `:duplicate-definition` — ≥ 2 named fn-defs with equal *shallow*
     signatures. (warning when the shared structure carries
     `warning-weight` or more bound values/refs, info below that — one-binding accessors and pure
     aliases are the let-rule's sanctioned \"separate child per code
     path\", not copy-paste.)
   - `:duplicate-after-expansion` — equal *deep* signatures (private
     `_`-helpers inlined at their ref sites) but different shallow
     ones: the same graph factored differently across helpers or
     namespaces. Same weighting.
   - `:unreferenced-private` — a `_`-private composed fn-def no other
     fn-def references (parents, args, list items, type-row fields).
     (warning; exemptions come from the caller as `:roots` — the
     `tools/graph-reachability.edn` registry of by-name entry points.)

   A *finding* is `{:rule :severity :fns [[ns name] …] :fn-ids [uuid …]
   :weight :message}` (`:fn-ids` when the fn-defs carry `:id` — the DB
   world); `lint` returns them sorted most severe first.

   Two things a caller layers on top:

   - **suppression** — `:suppress`, a set of `finding-key`s the author
     marked as not-an-issue. A suppressed finding is dropped, and a
     group that later gains a member has a new key and comes back.
   - **platform fn-defs** — `:platform-fn?`, a predicate over fn-defs
     (package-synced rows in a live graph). They are never subjects of
     `:unreferenced-private` (their by-name entry points live in the
     CI registry, not in the graph), and a duplicate group made only
     of them is the corpus gate's business, not the editor's."
  (:require
    [clojure.string :as str]))


;; -----------------------------------------------------------------------------
;; Index
;; -----------------------------------------------------------------------------

(defn fn-key
  "Identity of a fn-def in the lint index — `[namespace name]`."
  [fd]
  [(:namespace fd) (:name fd)])


(defn composed?
  "True for a composed fn-def (has `:parent` / `:parents`); false for
   base-fn declarations and type-rows."
  [fd]
  (boolean (or (:parent fd) (seq (:parents fd)))))


(defn private-name?
  "`_`-prefixed name — the fn-design skill's `defn-` marker."
  [n]
  (boolean (and n (str/starts-with? (name n) "_"))))


(defn anon-name?
  "Generated identity (`_anon-<hash>` or no name at all) — never a
   lint subject: anonymous rows are per-use-site by design."
  [n]
  (or (nil? n) (str/starts-with? (name n) "_anon-")))


(defn build-index
  "Index a fn-def set for reference resolution. `base-fn-names` is the
   set of base-fn names in scope (refs to them resolve to `[:base kw]`)."
  [fn-defs base-fn-names]
  {:fn-defs (vec fn-defs)
   :by-key (into {} (map (juxt fn-key identity)) fn-defs)
   :by-name (group-by :name fn-defs)
   :base-fn-names (set base-fn-names)})


(defn resolve-ref
  "Resolve a keyword the way package sync does: `:ns/name` → that
   fn-def; a bare name → the unique fn-def of that name, else a
   base-fn. Returns `[:fn-def fd]`, `[:base kw]`, `[:ambiguous kw]`
   (several namespaces declare the bare name) or nil (not a ref —
   a keyword literal)."
  [{:keys [by-key by-name base-fn-names]} kw]
  (if-let [nsp (namespace kw)]
    (some->> (get by-key [nsp (keyword (name kw))]) (vector :fn-def))
    (let [cands (get by-name kw)]
      (cond
        (= 1 (count cands)) [:fn-def (first cands)]
        (seq cands) [:ambiguous kw]
        (contains? base-fn-names kw) [:base kw]
        :else nil))))


;; -----------------------------------------------------------------------------
;; Signatures
;; -----------------------------------------------------------------------------

(def ^:private spec-keys
  "Keys that make an arg-value map a binding SPEC rather than a literal
   map (mirrors `records/parse` `arg-value->binding-fields`)."
  #{:value :ref :as :type :required :literal? :description :append
    :closed :terminal :secret-path :resolver})


(defn- inline-fn-def?
  [v]
  (and (map? v) (or (contains? v :parent) (contains? v :parents))))


(defn- spec-map?
  [v]
  (and (map? v) (some spec-keys (keys v))))


(declare signature)


(defn- canon-ref
  "Canonical form of a keyword in ref position. In `:deep` mode a ref
   to a private composed fn-def is replaced by that fn-def's own
   signature — the expansion that makes differently-factored graphs
   comparable."
  [idx mode memo kw]
  (let [[kind x] (resolve-ref idx kw)]
    (case kind
      :fn-def (if (and (= mode :deep) (private-name? (:name x)) (composed? x))
                [:expand (signature idx mode memo x)]
                [:ref (fn-key x)])
      :base [:base x]
      :ambiguous [:ambiguous x]
      [:value kw])))


(defn- canon-value
  "Canonical form of one `:args` value."
  [idx mode memo v]
  (cond
    (keyword? v) (canon-ref idx mode memo v)
    (inline-fn-def? v) [:inline (signature idx mode memo v)]
    (spec-map? v) (let [spec (dissoc v :description)]
                    (condp = (set (keys spec))
                      ;; `{:ref :x}` is the long form of a bare `:x`,
                      ;; `{:value 5}` of a bare literal — same binding.
                      #{:ref} (canon-ref idx mode memo (:ref spec))
                      #{:value} [:value (:value spec)]
                      [:spec (into (sorted-map)
                                   (map (fn [[k x]]
                                          (if (= k :ref)
                                            [k (canon-ref idx mode memo x)]
                                            [k x])))
                                   spec)]))
    (vector? v) [:list (mapv #(canon-value idx mode memo %) v)]
    :else [:value v]))


(defn signature
  "Name-free structure of a composed fn-def: resolved parents, canonical
   args (sorted by slot), and the declaration fields that change what
   the fn IS (`:return-type`, `:lambda-params`, `:effects`,
   `:expects-effects`, `:branch-local?`). `mode` is `:shallow` (refs
   stay refs) or `:deep` (private helpers expanded in place). `memo`
   is an atom caching named fn-defs' signatures per mode — every
   expansion of a shared helper is one lookup."
  [idx mode memo fd]
  (let [k (when (:name fd) [mode (fn-key fd)])]
    (or (when k (get @memo k))
        (let [parents (if (:parent fd) [(:parent fd)] (vec (:parents fd)))
              sig [:fn
                   [:parents (mapv #(canon-ref idx mode memo %) parents)]
                   [:args (into (sorted-map)
                                (map (fn [[s v]] [s (canon-value idx mode memo v)]))
                                (:args fd))]
                   [:return-type (:return-type fd)]
                   [:lambda-params (:lambda-params fd)]
                   [:effects (:effects fd)]
                   [:expects-effects (:expects-effects fd)]
                   [:branch-local? (:branch-local? fd)]]]
          (when k (swap! memo assoc k sig))
          sig))))


(defn- canon-weight
  "How much bound structure a canonical value carries — the number of
   values / refs the author had to write. A rename or a type pin
   alone weighs nothing: it re-labels a free arg rather than binding
   it."
  [[tag x]]
  (case tag
    (:ref :base :ambiguous) 1
    ;; `:default nil` binds nothing — it spells out the absence of a
    ;; default.
    :value (if (nil? x) 0 1)
    :spec (cond
            (contains? x :ref) 1
            (contains? x :value) (if (nil? (:value x)) 0 1)
            :else 0)
    :list (reduce + (map canon-weight x))
    (:inline :expand) (inc (canon-weight [:sig x]))
    :sig (let [[_ _ [_ args]] x]
           (reduce + (map canon-weight (vals args))))
    0))


(defn signature-weight
  "Bound values/refs a signature carries (see `canon-weight`) — the
   duplicate rules' threshold between info and warning."
  [sig]
  (canon-weight [:sig sig]))


;; -----------------------------------------------------------------------------
;; References between fn-defs
;; -----------------------------------------------------------------------------

(def ^:private type-row-fields
  [:type :refine :list :map :tuple :union :variant :fn-type])


(defn- walk-refs
  "Every fn-def key referenced anywhere inside `v`: keywords in ref
   position, plus STRINGS naming a fn-def — the data-driven registries
   (`:_value-form-registry`, `:_value-repr-registry`) hand fn names
   out as strings. Inside literal maps/vectors too —
   over-collection is the safe direction for a dead-code rule."
  [idx v]
  (cond
    (keyword? v) (let [[kind x] (resolve-ref idx v)]
                   (if (= kind :fn-def) #{(fn-key x)} #{}))
    (string? v) (let [cands (get (:by-name idx) (keyword v))]
                  (into #{} (map fn-key) cands))
    (map? v) (into #{} (mapcat #(walk-refs idx %)) (vals v))
    (sequential? v) (into #{} (mapcat #(walk-refs idx %)) v)
    :else #{}))


(defn references
  "Set of fn-def keys `fd` references — parents, args, return-type and
   the type-row fields."
  [idx fd]
  (let [parents (if (:parent fd) [(:parent fd)] (:parents fd))]
    (-> #{}
        (into (mapcat #(walk-refs idx %)) parents)
        (into (walk-refs idx (:args fd)))
        (into (walk-refs idx (:return-type fd)))
        (into (mapcat #(walk-refs idx (get fd %))) type-row-fields))))


(defn referrers
  "Map fn-def key → set of fn-def keys that reference it."
  [idx]
  (reduce (fn [acc fd]
            (let [from (fn-key fd)]
              (reduce (fn [m to] (update m to (fnil conj #{}) from))
                      acc
                      (disj (references idx fd) from))))
          {}
          (:fn-defs idx)))


;; -----------------------------------------------------------------------------
;; Rules
;; -----------------------------------------------------------------------------

(defn- lintable?
  "Named, non-generated composed fn-defs are the duplicate rules'
   subjects."
  [fd]
  (and (composed? fd) (not (anon-name? (:name fd)))))


(defn- label
  [[nsp n]]
  (str (or nsp "<root>") "/" (some-> n name)))


(def warning-weight
  "Bound values a shared structure must carry before duplicating it is
   a warning. Below this an extraction buys nothing: `{:parent :get
   :args {:coll {:as :row} :key {:value :id} :default nil}}` written
   twice is two accessors, not a copied graph."
  3)


(defn- duplicate-severity
  [weight]
  (if (>= weight warning-weight) :warning :info))


(defn- duplicate-findings
  "Group lintable fn-defs by signature under `mode`; every group of
   ≥ 2 is a finding. In `:deep` mode a group whose members already
   share one shallow signature is the `:duplicate-definition` finding
   again, so it is skipped here."
  [idx memo mode]
  (let [subjects (filter lintable? (:fn-defs idx))
        groups (vals (group-by #(signature idx mode memo %) subjects))]
    (for [g groups
          :when (> (count g) 1)
          :let [sig (signature idx mode memo (first g))
                shallow-sigs (into #{} (map #(signature idx :shallow memo %)) g)]
          :when (or (= mode :shallow) (> (count shallow-sigs) 1))
          :let [weight (signature-weight sig)
                fns (vec (sort (map fn-key g)))]]
      {:rule (if (= mode :shallow) :duplicate-definition :duplicate-after-expansion)
       :severity (duplicate-severity weight)
       :fns fns
       :weight weight
       :message (str (count g) " fn-defs "
                     (if (= mode :shallow)
                       "are the same definition"
                       "are the same graph once their private helpers are expanded")
                     " (" weight " bound value" (when (not= 1 weight) "s") "): "
                     (str/join ", " (map label fns))
                     (when (>= weight 2)
                       " — extract a shared parent and inherit it"))})))


(defn- unreferenced-private-findings
  [idx roots platform-fn?]
  (let [refs (referrers idx)]
    (for [fd (:fn-defs idx)
          :let [k (fn-key fd)]
          :when (and (lintable? fd)
                     (private-name? (:name fd))
                     (empty? (get refs k))
                     (not (contains? roots (:name fd)))
                     (not (and platform-fn? (platform-fn? fd))))]
      {:rule :unreferenced-private
       :severity :warning
       :fns [k]
       :weight 0
       :message (str (label k) " is private and nothing references it")})))


(def ^:private severity-rank
  {:warning 0 :info 1})


(defn finding-key
  "What a suppression names: the rule plus the sorted fn identities —
   ids when the fn-defs carry them (the live graph), `[ns name]` keys
   otherwise. Renaming a member keeps the key; adding one changes it."
  [{:keys [rule fn-ids fns]}]
  [rule (vec (sort (map str (or (seq fn-ids) fns))))])


(defn- with-fn-ids
  "Stamp `:fn-ids` on a finding when every member fn-def has an `:id`."
  [idx finding]
  (let [ids (map #(:id (get (:by-key idx) %)) (:fns finding))]
    (cond-> finding
      (every? some? ids) (assoc :fn-ids (vec (sort ids))))))


(defn- all-platform?
  [idx platform-fn? finding]
  (and platform-fn?
       (every? #(platform-fn? (get (:by-key idx) %)) (:fns finding))))


(defn lint
  "Run every rule over `fn-defs`. Options:

   - `:base-fn-names` — names refs may resolve to as base-fns;
   - `:roots` — set of fn NAMES entered from outside the graph (the
     by-name entry-point registry + vocabulary), exempt from
     `:unreferenced-private`;
   - `:platform-fn?` — predicate over fn-defs: package-synced rows are
     never `:unreferenced-private` subjects, and an all-platform
     duplicate group is dropped;
   - `:suppress` — set of `finding-key`s to drop.

   Returns findings sorted warnings first, then by rule and fns."
  ([fn-defs] (lint fn-defs {}))
  ([fn-defs {:keys [base-fn-names roots platform-fn? suppress]}]
   (let [idx (build-index fn-defs base-fn-names)
         memo (atom {})
         suppress (set suppress)]
     (->> (concat (duplicate-findings idx memo :shallow)
                  (duplicate-findings idx memo :deep)
                  (unreferenced-private-findings idx (set roots) platform-fn?))
          (map #(with-fn-ids idx %))
          (remove #(all-platform? idx platform-fn? %))
          (remove #(contains? suppress (finding-key %)))
          (sort-by (juxt (comp severity-rank :severity) :rule :fns))
          vec))))


(defn warnings
  "The findings that fail a gate."
  [findings]
  (filterv #(= :warning (:severity %)) findings))
