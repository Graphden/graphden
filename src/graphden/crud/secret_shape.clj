(ns graphden.crud.secret-shape
  "Single-source-of-truth predicates for the 'secret fn-def' shape:
   a `fn` row whose `parent-ids` is exactly `[<:secret-leaf>]`. Two
   server-side callers reuse this — `graphden.crud.secrets` (the
   admin CRUD) and `graphden.crud.entities` (the generic-DELETE
   guard that bounces secret-shaped rows back through `/api/secrets`).

   Lives in its own ns because both callers must agree on the
   shape — duplicating the literal `(= [sl-id] (vec parent-ids))` in
   each site is the kind of drift that bites later. Editor-side
   `isSecretFn` in `editor-secrets.js` mirrors this rule.

   The admin-only-vault set + secret-leaf identity are NOT hardcoded
   here — they're declared via `:tags` on the matching base-fn rows
   in `web/vault/fns.edn` (`:admin-only-vault` and `:secret-shape`
   tags). Adding a new admin-gated vault primitive is a one-line
   annotation on its `fns.edn` entry; no Clojure edit needed."
  (:require
    [graphden.executor.registry.core :as registry]
    [graphden.storage.protocol.core :as sp]))


(defn- fn-ids-with-tag
  "Resolve `tag` to the set of storage fn-ids for every base-fn
   declared with that tag in `:tags`. Returns nil when the registry
   has no entries (web.vault not loaded yet) so callers can branch
   on the empty result without a separate package-loaded check."
  [storage tag]
  (let [names (registry/fn-names-with-tag tag)]
    (when (seq names)
      (set (map :id
                (sp/query-entities storage :fn
                                   {:name (mapv name names)}))))))


(defn find-secret-leaf-fn-id
  "Look up the `:secret-leaf` base-fn id via its `:secret-shape` tag
   (declared in `web/vault/fns.edn`). Returns nil when the
   `web.vault` package isn't loaded. `:secret-leaf` is the
   passthrough base-fn used by the Secrets-panel admin path — its
   `:in` slot is `[:secret :text]` and the binding carries
   `:override-kind :secret-path` (executor auto-derefs the OpenBao
   path at arg-resolution time; the secret value never appears in
   graphden storage)."
  [storage]
  (first (fn-ids-with-tag storage :secret-shape)))


(defn find-admin-only-vault-base-fn-ids
  "Look up the ids of every admin-only vault base-fn via the
   `:admin-only-vault` tag declared in `web/vault/fns.edn`. Returns
   a set; empty when the `web.vault` package isn't loaded."
  [storage]
  (or (fn-ids-with-tag storage :admin-only-vault) #{}))


(defn secret-fn?
  "True when `fn-row`'s parents are exactly `[secret-leaf-id]`. MI
   children that inherit from anything else don't count — they aren't
   admin-managed secrets, they're regular composed fn-defs."
  [fn-row secret-leaf-id]
  (and secret-leaf-id
       (= [secret-leaf-id] (vec (:parent-ids fn-row)))))
