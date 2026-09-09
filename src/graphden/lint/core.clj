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
   - `:unreachable-private` — a `_`-private fn-def that IS referenced,
     but only from fn-defs no live root reaches: the rest of a dead
     cluster whose head is the `:unreferenced-private` finding.
     (warning)
   - `:shadowed-override` — a fn-def re-binds an arg to exactly the
     value its closest ancestors already bind. (warning)
   - `:fan-in-extract-parent` — ≥ 2 named fn-defs with the same parents
     bind ≥ 1 identical value (PACKAGES.md § 1's extraction rule).
     Weighted like the duplicate rules.
   - `:deep-hierarchy` — a chain `deep-hierarchy-depth` or more
     composed levels above its base-fn, reported at its tip
     (PACKAGES.md § 4). (info; warning from
     `deep-hierarchy-warning-depth`)

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


;; -----------------------------------------------------------------------------
;; Reachability — a private only dead code keeps alive
;; -----------------------------------------------------------------------------

(defn- parent-fn-defs
  "The composed fn-defs `fd` names as parents (base-fn parents resolve
   to nothing)."
  [idx fd]
  (let [parents (if (:parent fd) [(:parent fd)] (:parents fd))]
    (into [] (keep (fn [p]
                     (let [[kind x] (resolve-ref idx p)]
                       (when (= kind :fn-def) x))))
          parents)))


(defn- live-root?
  "Where liveness starts: anything that is not a `_`-private composed
   fn-def (public fn-defs, type-rows, base-fn declarations), the by-name
   entry points, and the platform's own rows (their entry points live in
   the CI registry, not in this graph)."
  [roots platform-fn? fd]
  (or (not (composed? fd))
      (not (private-name? (:name fd)))
      (contains? roots (:name fd))
      (boolean (and platform-fn? (platform-fn? fd)))))


(defn- reachable-from-live
  "Keys reachable from every live root through `references`."
  [idx roots platform-fn?]
  (loop [seen #{}
         frontier (into [] (comp (filter #(live-root? roots platform-fn? %)) (map fn-key))
                        (:fn-defs idx))]
    (if-let [k (peek frontier)]
      (if (contains? seen k)
        (recur seen (pop frontier))
        (let [fd (get (:by-key idx) k)]
          (recur (conj seen k)
                 (into (pop frontier) (remove seen) (when fd (references idx fd))))))
      seen)))


(defn- unreachable-private-findings
  "A `_`-private fn-def SOMETHING references, but only from fn-defs
   that are themselves dead — the transitive closure of
   `:unreferenced-private`, which stops at one hop. The unreferenced
   head of the chain is that rule's finding; this one names the rest
   of the cluster so deleting the head does not leave a trail of new
   `:unreferenced-private` findings, one per round."
  [idx roots platform-fn?]
  (let [refs (referrers idx)
        alive (reachable-from-live idx roots platform-fn?)]
    (for [fd (:fn-defs idx)
          :let [k (fn-key fd)]
          :when (and (lintable? fd)
                     (private-name? (:name fd))
                     (seq (get refs k))
                     (not (contains? alive k))
                     (not (live-root? roots platform-fn? fd)))]
      {:rule :unreachable-private
       :severity :warning
       :fns [k]
       :weight 0
       :message (str (label k) " is private and only dead code references it: "
                     (str/join ", " (map label (sort (get refs k)))))})))


;; -----------------------------------------------------------------------------
;; Shadowed override — a binding that restates what the fn already inherits
;; -----------------------------------------------------------------------------

(defn- inherited-arg
  "The canonical value slot `k` carries at `fd` BEFORE `fd`'s own
   binding — closest-fn-wins over the parent closure, level by level.
   `::none` when nothing up the chain binds it; `::ambiguous` when two
   ancestors at the same distance disagree (no single winner to
   restate)."
  [idx memo fd k]
  (loop [level (parent-fn-defs idx fd)
         seen #{}]
    (if (empty? level)
      ::none
      (let [own (into #{}
                      (comp (filter #(contains? (:args %) k))
                            (map #(canon-value idx :shallow memo (get (:args %) k))))
                      level)]
        (cond
          (= 1 (count own)) (first own)
          (seq own) ::ambiguous
          :else (let [seen (into seen (map fn-key) level)]
                  (recur (into [] (comp (mapcat #(parent-fn-defs idx %))
                                        (remove #(contains? seen (fn-key %)))
                                        (distinct))
                               level)
                         seen)))))))


(defn- restated-binding?
  "A binding that only re-states a value — a bare ref, a `{:value …}` /
   `{:ref …}` spec (docs aside) or a scalar literal. A spec that also
   pins a type, renames, or marks the slot terminal / required SAYS
   something the parent's binding did not, and a list binding appends
   to the inherited chain rather than replacing it — neither is a
   restatement."
  [v]
  (cond
    (keyword? v) true
    (or (vector? v) (inline-fn-def? v)) false
    (spec-map? v) (contains? #{#{:value} #{:ref}} (set (keys (dissoc v :description))))
    (map? v) true
    :else (some? v)))


(defn- shadowed-override-findings
  "Every arg `fd` binds to exactly what its closest ancestors already
   bind — the binding changes nothing and hides where the value really
   comes from. One finding per fn-def, naming the args."
  [idx memo]
  (for [fd (:fn-defs idx)
        :when (and (lintable? fd) (seq (parent-fn-defs idx fd)))
        :let [k (fn-key fd)
              shadowed (into []
                             (keep (fn [[arg v]]
                                     (when (restated-binding? v)
                                       (let [own (canon-value idx :shallow memo v)]
                                         (when (and (pos? (canon-weight own))
                                                    (= own (inherited-arg idx memo fd arg)))
                                           arg)))))
                             (sort-by key (:args fd)))]
        :when (seq shadowed)]
    {:rule :shadowed-override
     :severity :warning
     :fns [k]
     :weight (count shadowed)
     :message (str (label k) " re-binds " (str/join ", " (map name shadowed))
                   " to exactly what it already inherits — drop the binding"
                   (when (> (count shadowed) 1) "s"))}))


;; -----------------------------------------------------------------------------
;; Fan-in — siblings that bind the same values on the same parent
;; -----------------------------------------------------------------------------

(defn- bound-pairs
  "`fd`'s own args as `[arg canonical-value]` pairs, bound values only
   (a rename, a type pin or `:default nil` weighs nothing)."
  [idx memo fd]
  (into #{}
        (keep (fn [[arg v]]
                (let [c (canon-value idx :shallow memo v)]
                  (when (pos? (canon-weight c)) [arg c]))))
        (:args fd)))


(defn- fan-in-findings
  "PACKAGES.md § 1's extraction rule as a finding: ≥ 2 named fn-defs
   with the same parents that bind ≥ 1 identical value. Each fn-def's
   SHARED bindings (those at least one sibling repeats) are a candidate
   parent; the group for a candidate is every sibling that binds all of
   it, and the same member set is reported once, under its heaviest
   candidate. A group whose members are the same definition outright is
   `:duplicate-definition` and is not repeated here."
  [idx memo]
  (let [subjects (filter lintable? (:fn-defs idx))
        parents-of (fn [fd]
                     (let [ps (if (:parent fd) [(:parent fd)] (vec (:parents fd)))]
                       (mapv #(canon-ref idx :shallow memo %) ps)))
        set-weight (fn [S] (reduce + (map (fn [[_ c]] (canon-weight c)) S)))]
    (for [[parents fds] (group-by parents-of subjects)
          :when (> (count fds) 1)
          :let [pairs (into {} (map (fn [fd] [(fn-key fd) (bound-pairs idx memo fd)])) fds)
                shared (into #{}
                             (keep (fn [[pair n]] (when (> n 1) pair)))
                             (frequencies (mapcat val pairs)))
                candidates (into #{} (comp (map #(into #{} (filter shared) %)) (filter seq)) (vals pairs))
                groups (for [S candidates
                             :let [members (filterv #(every? (get pairs (fn-key %)) S) fds)]
                             :when (> (count members) 1)]
                         [S members])
                ;; one finding per member set — the heaviest candidate names it
                by-members (reduce (fn [m [S members]]
                                     (let [ks (into #{} (map fn-key) members)]
                                       (if (> (set-weight S) (set-weight (get m ks #{})))
                                         (assoc m ks S)
                                         m)))
                                   {}
                                   groups)]
          [ks S] by-members
          :let [members (filter #(contains? ks (fn-key %)) fds)
                sigs (into #{} (map #(signature idx :shallow memo %)) members)]
          :when (> (count sigs) 1)
          :let [weight (set-weight S)
                fns (vec (sort ks))
                args (map (comp name first) (sort-by (comp str first) S))]]
      {:rule :fan-in-extract-parent
       :severity (duplicate-severity weight)
       :fns fns
       :weight weight
       :message (str (count fns) " fn-defs inherit the same parent"
                     (when (> (count parents) 1) "s")
                     " and bind " (str/join ", " args) " identically"
                     " (" weight " bound value" (when (not= 1 weight) "s") "): "
                     (str/join ", " (map label fns))
                     " — extract a parent that binds "
                     (if (> (count args) 1) "them" "it") " and inherit it")})))


;; -----------------------------------------------------------------------------
;; Deep hierarchy — PACKAGES.md § 4
;; -----------------------------------------------------------------------------

(def deep-hierarchy-depth
  "Composed levels above a base-fn from which a chain needs a
   justification (PACKAGES.md § 4: 2–3 is normal, 4–5 acceptable for
   route/response composition, 6+ needs one). Filed as info from here —
   the MCP surface's tool envelopes sit at 6–7 with a concept per level."
  6)


(def deep-hierarchy-warning-depth
  "Depth at which a chain is a warning: two levels past the
   justification line, beyond anything the first-party corpus needs."
  8)


(defn- depth-chain
  "`[depth chain]` for `fd` — the number of composed fn-defs on its
   longest parent path (base-fn parent = 0) and that path's keys,
   `fd` first."
  [idx cache fd]
  (let [k (fn-key fd)]
    (or (when (:name fd) (get @cache k))
        (let [[d chain] (reduce (fn [[best best-chain] p]
                                  (let [[pd pchain] (depth-chain idx cache p)]
                                    (if (> pd best) [pd pchain] [best best-chain])))
                                [0 []]
                                (parent-fn-defs idx fd))
              r [(inc d) (into [k] chain)]]
          (when (:name fd) (swap! cache assoc k r))
          r))))


(defn- deep-hierarchy-findings
  "A composed fn-def `deep-hierarchy-depth` or more levels above its
   base-fn, reported at the TIP of the chain only — every fn-def that
   inherits it is deeper still and would repeat the same chain."
  [idx]
  (let [cache (atom {})
        subjects (filter lintable? (:fn-defs idx))
        is-parent (into #{} (comp (mapcat #(parent-fn-defs idx %)) (map fn-key)) subjects)]
    (for [fd subjects
          :let [k (fn-key fd)
                [d chain] (depth-chain idx cache fd)]
          :when (and (>= d deep-hierarchy-depth) (not (contains? is-parent k)))]
      {:rule :deep-hierarchy
       :severity (if (>= d deep-hierarchy-warning-depth) :warning :info)
       :fns [k]
       :weight d
       :message (str (label k) " is " d " levels of inheritance above its base-fn: "
                     (str/join " → " (map label (reverse chain)))
                     " — name the concept each level adds, or flatten the ones that add none")})))


(def ^:private severity-rank
  {:warning 0 :info 1})


(defn finding-key
  "What a suppression names: the rule plus the sorted fn identities —
   ids when the fn-defs carry them (the live graph), `[ns name]` keys
   otherwise. Renaming a member keeps the key; adding one changes it."
  [{:keys [rule fn-ids fns]}]
  [rule (vec (sort (map str (or (seq fn-ids) fns))))])


(defn- with-fn-ids
  "Stamp `:fn-ids` on a finding when every member fn-def has an `:id` —
   in the ORDER of `:fns`, so a consumer may zip the two (the editor's
   rows pair each name with its id and link `#id`). `finding-key` sorts
   for itself; a sorted `:fn-ids` here used to pair the names with the
   wrong ids."
  [idx finding]
  (let [ids (map #(:id (get (:by-key idx) %)) (:fns finding))]
    (cond-> finding
      (every? some? ids) (assoc :fn-ids (vec ids)))))


(defn- all-platform?
  [idx platform-fn? finding]
  (and platform-fn?
       (every? #(platform-fn? (get (:by-key idx) %)) (:fns finding))))


(defn lint
  "Run every rule over `fn-defs`. Options:

   - `:base-fn-names` — names refs may resolve to as base-fns;
   - `:roots` — set of fn NAMES entered from outside the graph (the
     by-name entry-point registry + vocabulary), exempt from
     `:unreferenced-private` and live roots for `:unreachable-private`;
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
                  (unreferenced-private-findings idx (set roots) platform-fn?)
                  (unreachable-private-findings idx (set roots) platform-fn?)
                  (shadowed-override-findings idx memo)
                  (fan-in-findings idx memo)
                  (deep-hierarchy-findings idx))
          (map #(with-fn-ids idx %))
          (remove #(all-platform? idx platform-fn? %))
          (remove #(contains? suppress (finding-key %)))
          (sort-by (juxt (comp severity-rank :severity) :rule :fns))
          vec))))


(defn warnings
  "The findings that fail a gate."
  [findings]
  (filterv #(= :warning (:severity %)) findings))
