(ns graphden.packages.app.secrets.impls
  "Impls for `app.secrets` endpoints. Each `defbase` is a thin shim
   that parses the URL / body and delegates to
   `graphden.crud.secrets`."
  (:require
    [clojure.string :as str]
    [graphden.crud.request :as request]
    [graphden.crud.secret-shape :as shape]
    [graphden.crud.secrets :as secrets]
    [graphden.executor.defbase :refer [defbase]]))


(defn- after-segment
  "Return the segment immediately following `marker` in the URI path,
   or nil if `marker` is absent / final. Mirrors the helper in
   `app.branches.impls`."
  [request marker]
  (let [segs (->> (-> (:uri request "") (str/split #"/"))
                  (remove str/blank?)
                  vec)
        idx (.indexOf ^java.util.List segs marker)]
    (when (and (not (neg? idx)) (< (inc idx) (count segs)))
      (get segs (inc idx)))))


(defbase _list-secrets-data
  [_request]
  (secrets/list-secrets ctx))


;; --- C7 atoms: create-secret variant-2 decomposition.
;; `:_create-secret-data` is now a `:cond` graph fn-def in fns.edn
;; composing parse / leaf-lookup / four guard predicates / apply.
;; Lazy `:cond` — vault writes + graphden creates only happen when
;; every guard passes.

(defbase _create-secret-parsed
  [request]
  (secrets/parse-create-secret-request (request/read-json-body request)))


(defbase _create-secret-leaf-id
  [_request]
  (secrets/create-secret-leaf-id ctx))


(defbase _create-secret-name-blank?
  [parsed]
  (str/blank? (:nm parsed)))


(defbase _create-secret-path-blank?
  [parsed]
  (str/blank? (:path parsed)))


(defbase _create-secret-value-missing?
  [parsed]
  (not (string? (:value parsed))))


(defbase _create-secret-leaf-missing?
  [leaf-id]
  (nil? leaf-id))


(defbase _create-secret-name-taken?
  [parsed]
  (boolean (secrets/create-secret-name-taken? parsed ctx)))


(defbase _create-secret-name-taken-error
  [parsed]
  {:ok false
   :error (str "fn already exists with name: " (:nm parsed))
   :reason :name-taken})


(defbase _create-secret-apply
  [parsed leaf-id]
  (secrets/apply-create-secret parsed leaf-id ctx))


;; --- C11 atoms: create-inline-binding variant-2 decomposition.

(defbase _inline-bind-parsed
  [request]
  (secrets/parse-create-inline-binding-request
    (request/read-json-body request)))


(defbase _inline-bind-fn-id-missing?
  [parsed]
  (nil? (:fn-id parsed)))


(defbase _inline-bind-slot-id-missing?
  [parsed]
  (nil? (:slot-id parsed)))


(defbase _inline-bind-path-blank?
  [parsed]
  (str/blank? (:path parsed)))


(defbase _inline-bind-value-missing?
  [parsed]
  (not (string? (:value parsed))))


(defbase _inline-bind-target-fn-row
  [parsed]
  (secrets/inline-binding-target-fn-row parsed ctx))


(defbase _inline-bind-target-missing?
  [target-fn-row]
  (nil? target-fn-row))


(defbase _inline-bind-existing
  [parsed]
  (secrets/inline-binding-existing parsed ctx))


(defbase _inline-bind-exists?
  [existing]
  (some? existing))


(defbase _inline-bind-err-target-missing
  [parsed]
  {:ok false
   :error (str "fn not found: " (:fn-id parsed))})


(defbase _inline-bind-apply
  [parsed]
  (secrets/apply-create-inline-binding parsed ctx))


;; --- C9 atoms: rotate-secret variant-2 decomposition.
;; Reuses `_delete-secret-fn-row` / `_delete-secret-vault-get-id` /
;; `_delete-secret-leaf-id` / the `_delete-secret-not-found?` /
;; `_delete-secret-not-a-secret?` guards by re-binding the `parsed`
;; slot at the rotate cond — same shape (both parsed values have
;; `:fn-id` + `:fn-id-ref`).

(defbase _rotate-secret-parsed
  [request]
  (secrets/parse-rotate-secret-request
    (after-segment request "secrets")
    (request/read-json-body request)))


(defbase _rotate-secret-value-missing?
  [parsed]
  (not (string? (:value parsed))))


(defbase _rotate-secret-path
  [parsed fn-row]
  (secrets/rotate-secret-path parsed fn-row ctx))


(defbase _rotate-secret-path-missing?
  [path]
  (nil? path))


(defbase _rotate-secret-err-not-found
  [parsed]
  {:ok false
   :error (str "Secret not found: " (:fn-id-ref parsed))
   :reason :not-found})


(defbase _rotate-secret-err-not-a-secret
  [parsed]
  {:ok false
   :error (str "fn is not a secret: " (:fn-id-ref parsed))
   :reason :not-a-secret})


(defbase _rotate-secret-apply
  [parsed fn-row path]
  (secrets/apply-rotate-secret parsed fn-row path ctx))


;; --- C8 atoms: delete-secret variant-2 decomposition.

(defbase _delete-secret-parsed
  [request]
  (secrets/parse-delete-secret-request (after-segment request "secrets")))


(defbase _delete-secret-fn-row
  [parsed]
  (secrets/delete-secret-fn-row parsed ctx))


(defbase _delete-secret-vault-get-id
  [_request]
  (shape/find-vault-get-fn-id
    (request/require-storage ctx)))


(defbase _delete-secret-leaf-id
  [_request]
  (shape/find-secret-leaf-fn-id
    (request/require-storage ctx)))


(defbase _delete-secret-not-found?
  [fn-row]
  (nil? fn-row))


(defbase _delete-secret-not-a-secret?
  [fn-row vault-get-id secret-leaf-id]
  (and (some? fn-row)
       (not (shape/secret-fn? fn-row vault-get-id secret-leaf-id))))


(defbase _delete-secret-usages
  [parsed]
  (secrets/delete-secret-find-usages parsed ctx))


(defbase _delete-secret-in-use?
  [usages]
  (boolean (seq usages)))


(defbase _delete-secret-err-not-found
  [parsed]
  {:ok false
   :error (str "Secret not found: " (:fn-id-ref parsed))
   :reason :not-found})


(defbase _delete-secret-err-not-a-secret
  [parsed]
  {:ok false
   :error (str "fn is not a secret (parent != [:vault-get|:secret-leaf]): "
               (:fn-id-ref parsed))
   :reason :not-a-secret})


(defbase _delete-secret-err-in-use
  [usages]
  {:ok false
   :error (str "Secret is referenced by " (count usages)
               " fn(s) — remove or re-target dependents first")
   :reason :secret-in-use
   :usages (mapv (fn [u]
                   {:fn-id (str (:fn-id u))
                    :name (:name u)
                    :reason (:reason u)})
                 usages)})


(defbase _delete-secret-apply
  [parsed fn-row]
  (secrets/apply-delete-secret parsed fn-row ctx))


;; --- C10 atoms: migrate-to-secret-leaf variant-2 decomposition.

(defbase _migrate-secret-parsed
  [request]
  (secrets/parse-migrate-secret-request (after-segment request "secrets")))


(defbase _migrate-secret-base-fns-missing?
  [vault-get-id secret-leaf-id]
  (or (nil? vault-get-id) (nil? secret-leaf-id)))


(defbase _migrate-secret-not-legacy?
  [fn-row vault-get-id]
  (and (some? fn-row)
       (some? vault-get-id)
       (not= [vault-get-id] (vec (:parent-ids fn-row)))))


(defbase _migrate-secret-legacy-binding
  [parsed vault-get-id]
  (secrets/migrate-secret-legacy-binding parsed vault-get-id ctx))


(defbase _migrate-secret-legacy-binding-missing?
  [legacy-binding]
  (nil? legacy-binding))


(defbase _migrate-secret-new-slot-id
  [secret-leaf-id]
  (secrets/migrate-secret-new-slot-id secret-leaf-id ctx))


(defbase _migrate-secret-new-slot-missing?
  [new-slot-id]
  (nil? new-slot-id))


(defbase _migrate-secret-err-not-legacy
  [parsed]
  {:ok false
   :error (str "fn is not legacy :vault-get-shaped (parent-ids != [:vault-get]): "
               (:fn-id-ref parsed))
   :reason :not-legacy-shape})


(defbase _migrate-secret-apply
  [parsed legacy-binding new-slot-id secret-leaf-id]
  (secrets/apply-migrate-secret parsed legacy-binding new-slot-id secret-leaf-id ctx))


(def impls
  {:_list-secrets-data           _list-secrets-data
   :_create-secret-parsed        _create-secret-parsed
   :_create-secret-leaf-id       _create-secret-leaf-id
   :_create-secret-name-blank?   _create-secret-name-blank?
   :_create-secret-path-blank?   _create-secret-path-blank?
   :_create-secret-value-missing? _create-secret-value-missing?
   :_create-secret-leaf-missing? _create-secret-leaf-missing?
   :_create-secret-name-taken?   _create-secret-name-taken?
   :_create-secret-name-taken-error _create-secret-name-taken-error
   :_create-secret-apply         _create-secret-apply
   :_delete-secret-parsed        _delete-secret-parsed
   :_delete-secret-fn-row        _delete-secret-fn-row
   :_delete-secret-vault-get-id  _delete-secret-vault-get-id
   :_delete-secret-leaf-id       _delete-secret-leaf-id
   :_delete-secret-not-found?    _delete-secret-not-found?
   :_delete-secret-not-a-secret? _delete-secret-not-a-secret?
   :_delete-secret-usages        _delete-secret-usages
   :_delete-secret-in-use?       _delete-secret-in-use?
   :_delete-secret-err-not-found _delete-secret-err-not-found
   :_delete-secret-err-not-a-secret _delete-secret-err-not-a-secret
   :_delete-secret-err-in-use    _delete-secret-err-in-use
   :_delete-secret-apply         _delete-secret-apply
   :_rotate-secret-parsed        _rotate-secret-parsed
   :_rotate-secret-value-missing? _rotate-secret-value-missing?
   :_rotate-secret-path          _rotate-secret-path
   :_rotate-secret-path-missing? _rotate-secret-path-missing?
   :_rotate-secret-err-not-found _rotate-secret-err-not-found
   :_rotate-secret-err-not-a-secret _rotate-secret-err-not-a-secret
   :_rotate-secret-apply         _rotate-secret-apply
   :_inline-bind-parsed          _inline-bind-parsed
   :_inline-bind-fn-id-missing?  _inline-bind-fn-id-missing?
   :_inline-bind-slot-id-missing? _inline-bind-slot-id-missing?
   :_inline-bind-path-blank?     _inline-bind-path-blank?
   :_inline-bind-value-missing?  _inline-bind-value-missing?
   :_inline-bind-target-fn-row   _inline-bind-target-fn-row
   :_inline-bind-target-missing? _inline-bind-target-missing?
   :_inline-bind-existing        _inline-bind-existing
   :_inline-bind-exists?         _inline-bind-exists?
   :_inline-bind-err-target-missing _inline-bind-err-target-missing
   :_inline-bind-apply           _inline-bind-apply
   :_migrate-secret-parsed       _migrate-secret-parsed
   :_migrate-secret-base-fns-missing? _migrate-secret-base-fns-missing?
   :_migrate-secret-not-legacy?  _migrate-secret-not-legacy?
   :_migrate-secret-legacy-binding _migrate-secret-legacy-binding
   :_migrate-secret-legacy-binding-missing? _migrate-secret-legacy-binding-missing?
   :_migrate-secret-new-slot-id  _migrate-secret-new-slot-id
   :_migrate-secret-new-slot-missing? _migrate-secret-new-slot-missing?
   :_migrate-secret-err-not-legacy _migrate-secret-err-not-legacy
   :_migrate-secret-apply        _migrate-secret-apply})
