(ns graphden.packages.app.secrets.impls
  "Impls for `app.secrets` endpoints. Each `defbase` is a thin shim
   that parses the JSON body and delegates to `graphden.crud.secrets`.
   URL-based parsers live in fns.edn as graph fn-defs composing
   `:uri-segment-after` + `:parse-uuid` + `:parse-json-body` +
   `:zipmap`, so they need no defbase shim here."
  (:require
    [graphden.crud.secrets :as secrets]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]))


(defbase _apply-create-secret-body
  [parsed leaf-id journal]
  (cr/record-effect! :db)
  (cr/record-effect! :network)
  (secrets/apply-create-secret-body parsed leaf-id journal ctx))


;; --- create-inline-binding ---

(defbase _apply-inline-bind-body
  [parsed journal]
  (cr/record-effect! :db)
  (cr/record-effect! :network)
  (secrets/apply-create-inline-binding-body parsed journal ctx))


(defbase _apply-secret-rollback
  "Shared on-throw branch for inline-bind + create-secret. Replays the
   journal in reverse (vault-delete / storage-delete by tag) and
   builds the rejection envelope."
  [journal exception]
  (cr/record-effect! :db)
  (cr/record-effect! :network)
  (secrets/replay-secret-rollback! journal exception ctx))


;; --- rotate-secret ---
;; Reuses `_delete-secret-fn-row` / `_delete-secret-leaf-id` /
;; the `_delete-secret-not-found?` / `_delete-secret-not-a-secret?`
;; guards by re-binding the `parsed` slot at the rotate cond — same
;; shape (both parsed values have `:fn-id` + `:fn-id-ref`).

(defbase _rotate-secret-not-owned?
  "Ownership guard for the rotate cond. Rotate writes vault DIRECTLY
   (no storage write), so it bypasses the tenant write-guard + RLS that
   `:delete` goes through — a tenant could otherwise rewrite a PUBLIC /
   shared secret's value (read-visible, own+public). Delegates to the
   single-sourced `crud.secrets/rotate-secret-not-owned?` predicate."
  [fn-row]
  (secrets/rotate-secret-not-owned? fn-row))


;; --- delete-secret ---
;; `:_delete-secret-vault-cleanup` (:try over :vault-delete + :log-warn)
;; and `:_delete-secret-storage-cleanup` (:do over two :delete-entity)
;; are pure graph compositions in fns.edn — no impls here.


(def impls
  {:_apply-create-secret-body     _apply-create-secret-body
   :_apply-inline-bind-body       _apply-inline-bind-body
   :_apply-secret-rollback        _apply-secret-rollback
   :_rotate-secret-not-owned?     _rotate-secret-not-owned?})
