(ns graphden.packages.app.secrets.impls
  "Native secret write, compensation, ownership and redacted audit boundaries.
   Parsing, safe receipts and history ordering/cap live in app.secrets fn-defs.
   Secret values and raw Vault error payloads stay inside the native boundary."
  (:require
    [graphden.crud.request :as request]
    [graphden.crud.secrets :as secrets]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.storage.protocol.core :as sp]))


(defbase _create-secret-facts
  [parsed leaf-id journal]
  (cr/record-effect! :db)
  (cr/record-effect! :network)
  (secrets/create-secret-facts! parsed leaf-id journal ctx))


;; --- create-inline-binding ---

(defbase _inline-secret-facts
  [parsed journal]
  (cr/record-effect! :db)
  (cr/record-effect! :network)
  (secrets/create-inline-binding-facts! parsed journal ctx))


(defbase _rollback-secret-failure
  "Shared on-throw branch for inline-bind + create-secret. Replays the
   journal in reverse (vault-delete / storage-delete by tag) and
   returns redacted failure facts for the graph rejection envelope."
  [journal exception]
  (cr/record-effect! :db)
  (cr/record-effect! :network)
  (secrets/rollback-secret-failure! journal exception ctx))


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


(defbase _secret-rotation-rows
  "Scoped history read with a strict value-free projection. Ordering, cap and
   response envelope are graph policy; raw audit rows never cross this boundary."
  [target-kind target-id]
  (cr/record-effect! :db)
  (let [kind-str (if (keyword? target-kind) (name target-kind) (str target-kind))]
    (when-not (contains? #{"secret" "binding"} kind-str)
      (throw (ex-info "Invalid secret rotation target" {:type :secret-rotation/invalid-target})))
    (mapv #(select-keys % [:actor-id :actor-label :status :vault-version
                           :failure-type :occurred-at])
          (sp/query-entities (request/require-storage ctx) :secret-rotation
                             {:target-kind kind-str :target-id target-id}))))


;; --- delete-secret ---
;; `:_delete-secret-vault-cleanup` (:try over :vault-delete + :log-warn)
;; and `:_delete-secret-storage-cleanup` (:do over two :delete-entity)
;; are pure graph compositions in fns.edn — no impls here.


(def impls
  {:_create-secret-facts     {:impl _create-secret-facts :taint-propagate? true}
   :_inline-secret-facts       {:impl _inline-secret-facts :taint-propagate? true}
   :_rollback-secret-failure        {:impl _rollback-secret-failure :taint-propagate? true}
   :_rotate-secret-not-owned?     {:impl _rotate-secret-not-owned? :taint-propagate? true}
   :_rotate-secret-write          _rotate-secret-write
   :_secret-rotation-rows         _secret-rotation-rows})
