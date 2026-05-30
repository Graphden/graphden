(ns graphden.crud.secret-shape
  "Single-source-of-truth predicates for the 'secret fn-def' shape:
   a `fn` row whose `parent-ids` is exactly `[<:vault-get>]`. Two
   server-side callers reuse this — `graphden.crud.secrets` (the
   admin CRUD) and `graphden.crud.entities` (the generic-DELETE
   guard that bounces secret-shaped rows back through `/api/secrets`).

   Lives in its own ns because both callers must agree on the
   shape — duplicating the literal `(= [vg-id] (vec parent-ids))` in
   each site is the kind of drift that bites later. Editor-side
   `isSecretFn` in `editor-secrets.js` mirrors this rule."
  (:require
    [graphden.storage.protocol.core :as sp]))


(defn find-vault-get-fn-id
  "Look up the `:vault-get` base-fn id by name. Returns nil when the
   `web.vault` package isn't loaded (tests that skip vault, fresh
   installs before sync). Legacy: the binding-IS-secret model uses
   `:secret-leaf` (see `find-secret-leaf-fn-id`); `:vault-get` is
   kept for back-compat reads only."
  [storage]
  (some-> (first (sp/query-entities storage :fn {:name "vault-get"})) :id))


(defn find-secret-leaf-fn-id
  "Look up the `:secret-leaf` base-fn id by name. Returns nil when
   the `web.vault` package isn't loaded. `:secret-leaf` is the
   Followup-4 base-fn used by the Secrets-panel admin path — a
   pure passthrough whose `:in` slot is `[:secret :text]` and
   whose binding carries `:override-kind :secret-path`."
  [storage]
  (some-> (first (sp/query-entities storage :fn {:name "secret-leaf"})) :id))


(def admin-only-vault-base-fn-names
  "Set of base-fn names that are admin-side only (used by
   `crud.secrets/*` and the internal vault client, never exposed
   as parent in user-facing fn-graphs). The capability gate in
   `crud.entities/vault-get-capability-rej` refuses any user-graph
   fn-def whose parent-ids reaches one of these.

   `:vault-get` is included for the secrets-are-admin-only reason;
   `:secret-leaf` because it's the Followup-4 replacement; the
   write-side trio because they mutate OpenBao state and should
   only be invoked through the audited `/api/secrets` admin path.
   `:vault-metadata-get` (read-only) is omitted — metadata isn't
   a secret value."
  #{"vault-get" "secret-leaf"
    "vault-put" "vault-delete" "vault-metadata-put"})


(defn find-admin-only-vault-base-fn-ids
  "Look up the ids of every admin-only vault base-fn currently in
   the registry. Returns a set; entries are dropped silently when
   the matching row isn't found (web.vault package not loaded)."
  [storage]
  (let [rows (sp/query-entities storage :fn {:name (vec admin-only-vault-base-fn-names)})]
    (set (map :id rows))))


(defn secret-fn?
  "True when `fn-row`'s parents are exactly `[vault-get-id]` OR
   `[secret-leaf-id]`. Both shapes count as admin-managed secrets:

     - `:vault-get` parent — legacy, before Followup-4. Binding
       on `:path` is a plain literal text value.
     - `:secret-leaf` parent — Followup-4. Binding on `:in` is
       `:override-kind :secret-path`, the executor auto-derefs.

   MI children that inherit from anything else don't count — they
   aren't admin-managed secrets, they're regular composed fn-defs."
  [fn-row vault-get-id secret-leaf-id]
  (let [ps (vec (:parent-ids fn-row))]
    (or (and vault-get-id (= [vault-get-id] ps))
        (and secret-leaf-id (= [secret-leaf-id] ps)))))
