(ns graphden.tenancy.grant
  "Minimal authorization primitive (PLATFORM_PLAN §4.2). A grant is
   `(subject, capability, namespace)` — 'subject may <capability> within
   <namespace> and its descendants'. `can?` is the pure decision; a
   `GrantStore` supplies the grants (static-map here; storage-backed in a
   real deployment). This is the seam the editor's edit / execute gating
   will read — deliberately NOT a full RBAC system (no roles, no deny rules,
   no inheritance graph), per §4.2's 'minimal primitives, not a big system'.

   Two scope axes:
   - capability — `:admin` implies the others within its scope;
   - namespace — a grant on a parent ns (dot-path) covers descendants; a
     blank / nil ns is the root grant (covers everything)."
  (:require
    [clojure.string :as str]))


(def capabilities
  "The capability vocabulary. `:admin` subsumes the rest within its scope;
   `:write` subsumes the narrower §4.3 edit caps (`:bind-args`, `:append-list`)
   — a full writer can do everything they can.

   - `:bind-args` — change a binding's `:value`, NOT its ref / type-override /
     structure (restricted editing — §4.3).
   - `:append-list` — add / remove one's own `:binding-list-item` rows, not
     parent / inherited ones."
  #{:read :write :execute :admin :bind-args :append-list})


(defn- cap-implies?
  "Does holding `held` satisfy a check for `needed`? `:admin` implies all;
   `:write` implies the narrower edit caps; otherwise exact match."
  [held needed]
  (or (= held :admin)
      (= held needed)
      (and (= held :write) (contains? #{:bind-args :append-list} needed))))


(defn- ns-covers?
  "True when a grant on `granted-ns` reaches `target-ns` — same namespace,
   a descendant (dot-path prefix), or the root grant (blank/nil)."
  [granted-ns target-ns]
  (let [g (some-> granted-ns str)
        t (str target-ns)]
    (or (str/blank? g)
        (= g t)
        (str/starts-with? t (str g ".")))))


(defn grant-allows?
  "Does one grant authorize `(subject-id, cap, ns-path)`? Matches the grant's
   `:subject-id` — the user's STABLE id, NOT the mutable username — capability
   by `cap-implies?` (`:admin`/`:write` subsumption), and namespace by scope
   coverage."
  [{:keys [subject-id capability namespace]} subj-id cap ns-path]
  (and (= subject-id subj-id)
       (cap-implies? capability cap)
       (ns-covers? namespace ns-path)))


(defprotocol GrantStore

  (grants-for
    [store subj]
    "The grants held by `subj` — a `{:id user-id :name username}` identity
     pair — as a coll of {:subject-id :capability :namespace}; empty when
     none. Stored-grant matching keys on the stable `:id`; the `:name`
     (username) is used ONLY to build the personal-namespace path."))


(defrecord StaticGrantStore
  [by-subject-id]

  GrantStore

  (grants-for [_ subj] (get by-subject-id (:id subj) [])))


(defn static-grant-store
  "A `GrantStore` over a static collection of grants, keyed by `:subject-id`."
  [grants]
  (->StaticGrantStore (group-by :subject-id grants)))


(defn personal-namespace
  "The namespace a user owns outright — `<prefix>.<user>` (e.g.
   `users.alice`). `prefix` is the home segment for personal namespaces."
  [prefix user]
  (str prefix "." user))


(defrecord PersonalNamespaceGrantStore
  [base prefix]

  GrantStore

  (grants-for
    [_ subj]
    ;; Every user implicitly holds :admin on their own namespace, in
    ;; addition to whatever the base store grants. The personal-namespace
    ;; PATH is built from the human `:name` (users.alice); the implicit
    ;; grant still carries the stable `:subject-id` so `grant-allows?`
    ;; matches it by id like every other grant.
    (conj (vec (grants-for base subj))
          {:subject-id (:id subj)
           :capability :admin
           :namespace (personal-namespace prefix (:name subj))})))


(defn with-personal-namespaces
  "Wrap a `GrantStore` so every user implicitly holds `:admin` on their
   personal namespace (`<prefix>.<user>`) — no explicit grant row needed. A
   user can therefore read / write / execute in their own namespace and its
   descendants once that `:ns` exists, while still being governed elsewhere
   by the base store's grants."
  [base prefix]
  (->PersonalNamespaceGrantStore base prefix))


(defrecord MemoGrantStore
  [base cache]

  GrantStore

  (grants-for
    [_ subj]
    (if-let [hit (find @cache (:id subj))]
      (val hit)
      (let [g (grants-for base subj)]
        (swap! cache assoc (:id subj) g)
        g))))


(defn memoizing-grant-store
  "Wrap a `GrantStore` so `grants-for` is cached per subject. Create ONE
   per request (fresh atom) and use it only within a read-only window —
   a single tenant request calls `grants-for` for the SAME subject 3-4×
   (`request-capabilities` runs `can?` for each action capability,
   `request-workspace` runs once), each an identical `:grant` query.
   MUST NOT outlive a request or span a grant mutation, or it serves
   stale grants."
  [base]
  (->MemoGrantStore base (atom {})))


(def ^:dynamic *request-grant-store*
  "Per-request memoizing grant store, bound by the request-scope for the
   duration of one tenant request. The storage-layer write guard and the
   coarse `request-permitted?` gate read it via `request-store` so all
   consumers of `grants-for` for the same subject share ONE `:grant` query —
   including a batch write, where the guard fires per row. nil outside a
   request (system / test callers pass their store explicitly)."
  nil)


(defn request-store
  "The per-request grant store when a request-scope has bound one, else
   `fallback` (the process singleton). Lets init-time closures pick up the
   per-request memo without threading it through their signatures."
  [fallback]
  (or *request-grant-store* fallback))


(defn can?
  "Does `subj` (`{:id user-id :name username}`) hold `capability` in
   `ns-path`, per `store`? Default-deny. Stored-grant matching keys on
   `(:id subj)`; `:name` only feeds the personal-namespace path."
  [store subj capability ns-path]
  (boolean (some #(grant-allows? % (:id subj) capability ns-path)
                 (grants-for store subj))))


(defn subject
  "The grant-subject identity for an auth principal: `{:id user-id :name
   username}` — the STABLE id keys stored-grant matching, the human name only
   builds the personal-namespace path. Returns nil for an UNAUTHENTICATED
   principal (no `:user-id`); callers must treat nil as default-deny and never
   pass it to `grants-for` (a nil id would spuriously match a nil subject-id)."
  [principal]
  (when-let [uid (:user-id principal)]
    {:id uid :name (:user principal)}))


(defn authorized?
  "Bridge from an auth principal (the `AuthProvider` result) to `can?` via
   `subject`. An unauthenticated principal (no `:user-id`) is denied. This is
   the call an enforced handler makes: `(authorized? store principal :write
   ns-path)`."
  [store principal capability ns-path]
  (boolean (when-let [subj (subject principal)]
             (can? store subj capability ns-path))))


(defn has-capability?
  "Does `subject` hold `capability` (or `:admin`) in ANY namespace? The
   coarse 'is this subject a writer / executor at all' gate — the precise
   per-target-namespace check runs later (writes: at the storage layer)."
  [store subj capability]
  (boolean (some #(or (= (:capability %) :admin) (= (:capability %) capability))
                 (grants-for store subj))))


(defn can-mutate?
  "Coarse 'can this subject perform SOME write' gate — holds any write-family
   capability (`:write` / `:bind-args` / `:append-list`) or `:admin` in ANY
   namespace. Used by `request-permitted?` so a `:bind-args`-only user isn't
   rejected at the coarse gate before the precise per-field check at storage."
  [store subj]
  (boolean (some (fn [{:keys [capability]}]
                   (contains? #{:admin :write :bind-args :append-list} capability))
                 (grants-for store subj))))


(defn workspace
  "A user's workspace (§4.4) — the union of the (named) namespaces their
   grants cover. Each is a subtree the user can work in; with personal
   namespaces wired, the user's own namespace is included. Root/blank grants
   (whole-org admin) and the shared public namespace aren't listed — they're
   not a bounded 'set of namespaces'. Returns a sorted set of paths."
  [store subj]
  (into (sorted-set)
        (comp (map :namespace) (remove str/blank?))
        (grants-for store subj)))


(defn request->capability
  "The capability a Ring request needs: an `/execute` call → `:execute`, a
   mutating method (POST/PUT/PATCH/DELETE) → `:write`, anything else →
   `:read`."
  [request]
  (cond
    (some-> (:uri request) (str/includes? "/execute")) :execute
    (contains? #{:post :put :patch :delete} (:request-method request)) :write
    :else :read))


(defn request-permitted?
  "Coarse request-scope gate: reads are open (OrgScopedStorage governs read
   visibility); a write / execute requires the subject to hold that
   capability SOMEWHERE (`has-capability?`). It deliberately does NOT pin the
   org as the namespace — that would block a user granted only on a
   sub-namespace. The PRECISE per-target-namespace check runs at the storage
   layer (`tenancy.authz`), which can see the entity's `:namespace-id`."
  [store principal request _org]
  (let [cap (request->capability request)]
    (if (= cap :read)
      true
      ;; A mutation may need only a narrow §4.3 edit cap — let the write-family
      ;; through; the precise per-field check runs at the storage layer. An
      ;; unauthenticated principal (nil subject) is denied any write.
      (if-let [subj (subject principal)]
        (case cap
          :write (can-mutate? store subj)
          (has-capability? store subj cap))
        false))))
