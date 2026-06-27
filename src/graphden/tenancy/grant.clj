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
