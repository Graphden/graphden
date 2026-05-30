(ns graphden.packages.app.secrets.impls
  "Impls for `app.secrets` endpoints. Each `defbase` is a thin shim
   that parses the URL / body and delegates to
   `graphden.crud.secrets`."
  (:require
    [clojure.string :as str]
    [graphden.crud.request :as request]
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


(defbase _create-secret-data
  [request]
  (secrets/create-secret ctx (request/read-json-body request)))


(defbase _create-inline-binding-data
  [request]
  (secrets/create-inline-binding ctx (request/read-json-body request)))


(defbase _rotate-secret-data
  [request]
  (secrets/rotate-secret ctx
                         (after-segment request "secrets")
                         (request/read-json-body request)))


(defbase _delete-secret-data
  [request]
  (secrets/delete-secret ctx (after-segment request "secrets")))


(defbase _migrate-secret-data
  [request]
  (secrets/migrate-to-secret-leaf ctx (after-segment request "secrets")))


(def impls
  {:_list-secrets-data           _list-secrets-data
   :_create-secret-data          _create-secret-data
   :_create-inline-binding-data  _create-inline-binding-data
   :_rotate-secret-data          _rotate-secret-data
   :_delete-secret-data          _delete-secret-data
   :_migrate-secret-data         _migrate-secret-data})
