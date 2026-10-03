(ns graphden.packages.app.secrets.impls
  "Impls for `app.secrets` endpoints. Each `defbase` is a thin shim
   that parses the JSON body and delegates to `graphden.crud.secrets`.
   URL-based parsers live in fns.edn as graph fn-defs composing
   `:uri-segment-after` + `:parse-uuid` + `:parse-json-body` +
   `:zipmap`, so they need no defbase shim here."
  (:require
    [graphden.crud.request :as request]
    [graphden.crud.secrets :as secrets]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.storage.protocol.core :as sp]))


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


(defbase _rotate-secret-write
  "Vault rotation paired with a value-free audit event."
  [path value target-kind target-id]
  (cr/record-effect! :db)
  (cr/record-effect! :network)
  (secrets/rotate-secret-value! ctx {:path path :value value
                                     :target-kind target-kind :target-id target-id}))


(defbase _secret-rotation-history
  "Read at most 50 value-free rotation events for one secret or inline binding."
  [target-kind target-id]
  (let [kind-str (if (keyword? target-kind) (name target-kind) (str target-kind))
        _ (when-not (contains? #{"secret" "binding"} kind-str)
            (throw (ex-info "Invalid secret rotation target" {:type :secret-rotation/invalid-target})))
        rows (sp/query-entities (request/require-storage ctx) :secret-rotation
                                {:target-kind kind-str
                                 :target-id target-id})]
    {:ok true
     :events (->> rows
                  (sort-by :occurred-at #(compare %2 %1))
                  (take 50)
                  (mapv #(select-keys % [:actor-id :actor-label :status
                                         :vault-version :failure-type :occurred-at])))}))


;; --- delete-secret ---
;; `:_delete-secret-vault-cleanup` (:try over :vault-delete + :log-warn)
;; and `:_delete-secret-storage-cleanup` (:do over two :delete-entity)
;; are pure graph compositions in fns.edn — no impls here.


(def impls
  {:_apply-create-secret-body     _apply-create-secret-body
   :_apply-inline-bind-body       {:impl _apply-inline-bind-body :taint-propagate? true}
   :_apply-secret-rollback        {:impl _apply-secret-rollback :taint-propagate? true}
   :_rotate-secret-not-owned?     {:impl _rotate-secret-not-owned? :taint-propagate? true}
   :_rotate-secret-write          _rotate-secret-write
   :_secret-rotation-history      _secret-rotation-history})
