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
  "The capability vocabulary. `:admin` subsumes the rest within its scope."
  #{:read :write :execute :admin})


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
  "Does one grant authorize `(subj, cap, ns-path)`? Matches subject exactly,
   capability exactly OR via `:admin`, and namespace by scope coverage."
  [{:keys [subject capability namespace]} subj cap ns-path]
  (and (= subject subj)
       (or (= capability :admin) (= capability cap))
       (ns-covers? namespace ns-path)))


(defprotocol GrantStore

  (grants-for
    [store subject]
    "The grants held by `subject` (a coll of {:subject :capability
     :namespace}); empty when none."))


(defrecord StaticGrantStore
  [by-subject]

  GrantStore

  (grants-for [_ subject] (get by-subject subject [])))


(defn static-grant-store
  "A `GrantStore` over a static collection of grants."
  [grants]
  (->StaticGrantStore (group-by :subject grants)))


(defn personal-namespace
  "The namespace a user owns outright — `<prefix>.<user>` (e.g.
   `users.alice`). `prefix` is the home segment for personal namespaces."
  [prefix user]
  (str prefix "." user))


(defrecord PersonalNamespaceGrantStore
  [base prefix]

  GrantStore

  (grants-for
    [_ subject]
    ;; Every user implicitly holds :admin on their own namespace, in
    ;; addition to whatever the base store grants.
    (conj (vec (grants-for base subject))
          {:subject subject
           :capability :admin
           :namespace (personal-namespace prefix subject)})))


(defn with-personal-namespaces
  "Wrap a `GrantStore` so every user implicitly holds `:admin` on their
   personal namespace (`<prefix>.<user>`) — no explicit grant row needed. A
   user can therefore read / write / execute in their own namespace and its
   descendants once that `:ns` exists, while still being governed elsewhere
   by the base store's grants."
  [base prefix]
  (->PersonalNamespaceGrantStore base prefix))


(defn can?
  "Does `subject` hold `capability` in `ns-path`, per `store`? A subject
   with no matching grant is denied (default-deny)."
  [store subject capability ns-path]
  (boolean (some #(grant-allows? % subject capability ns-path)
                 (grants-for store subject))))


(defn authorized?
  "Bridge from an auth principal (the `AuthProvider` result) to `can?` — the
   grant subject is the principal's `:user`. An unauthenticated principal
   (no `:user`) is denied. This is the call an enforced handler makes:
   `(authorized? store principal :write ns-path)`."
  [store principal capability ns-path]
  (boolean (when-let [user (:user principal)]
             (can? store user capability ns-path))))


(defn has-capability?
  "Does `subject` hold `capability` (or `:admin`) in ANY namespace? The
   coarse 'is this subject a writer / executor at all' gate — the precise
   per-target-namespace check runs later (writes: at the storage layer)."
  [store subject capability]
  (boolean (some #(or (= (:capability %) :admin) (= (:capability %) capability))
                 (grants-for store subject))))


(defn workspace
  "A user's workspace (§4.4) — the union of the (named) namespaces their
   grants cover. Each is a subtree the user can work in; with personal
   namespaces wired, the user's own namespace is included. Root/blank grants
   (whole-org admin) and the shared public namespace aren't listed — they're
   not a bounded 'set of namespaces'. Returns a sorted set of paths."
  [store user]
  (into (sorted-set)
        (comp (map :namespace) (remove str/blank?))
        (grants-for store user)))


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
    (or (= cap :read)
        (has-capability? store (:user principal) cap))))
