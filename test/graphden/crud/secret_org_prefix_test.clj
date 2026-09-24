(ns ^:integration graphden.crud.secret-org-prefix-test
  "The boot migration that moves tenant secrets stored at a bare vault
   path under `org/<org-id>/` — against a real OpenBao and a real PG
   (versioned storage, like production; the migration reads the raw base
   under it)."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.clients.vault :as vault]
    [graphden.crud.secret-org-prefix :as sut]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]
    [graphden.test-infra.openbao :as openbao]
    [graphden.versioning.storage.core :as vcore]))


(use-fixtures :once (setup/create-container-fixture) openbao/container-fixture)


(defn- gone?
  [path]
  (try (vault/get-metadata openbao/*client* path) false
       (catch clojure.lang.ExceptionInfo e (= 404 (:status (ex-data e))))))


(defn- seed!
  "A versioned storage with the `:vault-get` base-fn and one secret
   binding per `[fn-name org path]`. Returns `{:storage :bindings}` with
   `:bindings` fn-name → binding id."
  [specs]
  (let [storage (setup/create-versioned-test-storage)
        vg (setup/create-base-fn! storage "vault-get" :text)
        slot (setup/create-slot! storage "cred" :text)]
    {:storage storage
     :bindings
     (into {}
           (for [[fn-name org path] specs
                 :let [owner (setup/create-base-fn! storage fn-name :int)]]
             (do (setup/attach-slot! storage (:id owner) (:id slot) 0)
                 [fn-name
                  (:id (sp/create-entity storage :binding
                                         (cond-> {:fn-id (:id owner)
                                                  :slot-id (:id slot)
                                                  :value path
                                                  :value-present true
                                                  :resolver-fn-id (:id vg)}
                                           org (assoc :org-id org))))])))}))


(defn- stored-paths
  "Every raw row's `:value` for `binding-id` — the identity row and its
   version rows — plus the resolved (versioned) view."
  [storage binding-id]
  (let [raw (vcore/unwrap storage)]
    {:resolved (:value (sp/read-entity storage :binding binding-id))
     :raw (into #{} (keep :value)
                (concat [(sp/read-entity raw :binding binding-id)]
                        (sp/query-entities raw :binding-version {:binding-id binding-id})))}))


(deftest bare-tenant-secret-moves-under-the-org-prefix
  (let [bare (str "db-" (random-uuid) "/password")
        target (str "org/acme/" bare)
        platform (str "platform-" (random-uuid) "/token")
        {:keys [storage bindings]} (seed! [["acme-fn" "acme" bare]
                                           ["platform-fn" nil platform]])
        raw (vcore/unwrap storage)
        client openbao/*client*]
    (vault/put-secret client bare "v1")
    (vault/put-secret client bare "v2")
    (vault/put-metadata client bare {"created-by" "alice"})
    (vault/put-secret client platform "platform-value")
    (try
      (testing "the first run moves the one tenant path"
        (is (= {:rows 2 :paths 1 :conflicts 0 :failed 0}
               (sut/migrate! raw client))
            "identity row + its main version row"))
      (testing "the value — every version, and the metadata — is at the new path"
        (is (= "v2" (vault/get-secret client target)))
        (is (= "v1" (vault/get-secret client target 1)))
        (is (= "alice" (get-in (vault/get-metadata client target)
                               [:custom_metadata :created-by]))))
      (testing "the binding reads the new path, raw rows and resolved view alike"
        (is (= {:resolved target :raw #{target}}
               (stored-paths storage (bindings "acme-fn")))))
      (testing "the bare path is gone"
        (is (gone? bare)))
      (testing "the platform secret and binding are untouched"
        (is (= "platform-value" (vault/get-secret client platform)))
        (is (= {:resolved platform :raw #{platform}}
               (stored-paths storage (bindings "platform-fn")))))
      (testing "a second run is a no-op"
        (is (= {:rows 0 :paths 0 :conflicts 0 :failed 0}
               (sut/migrate! raw client))))
      (finally (sp/close storage)))))


(deftest a-rerun-finishes-a-migration-that-crashed
  (let [done (str "done-" (random-uuid))
        half (str "half-" (random-uuid))
        {:keys [storage bindings]} (seed! [["done-fn" "acme" done]
                                           ["half-fn" "acme" half]])
        raw (vcore/unwrap storage)
        client openbao/*client*]
    ;; Crash after copy + delete, before the rows were re-pointed: the
    ;; marked copy is the only place the value lives.
    (vault/put-secret client (str "org/acme/" done) "kept")
    (vault/put-metadata client (str "org/acme/" done) {"graphden-migrated-from" done})
    ;; Crash mid-copy: an unmarked, unreferenced target beside the source.
    (vault/put-secret client half "real")
    (vault/put-secret client (str "org/acme/" half) "partial")
    (try
      (is (= {:rows 4 :paths 2 :conflicts 0 :failed 0} (sut/migrate! raw client)))
      (testing "the crashed-after-delete path is re-pointed at its marked copy"
        (is (= "kept" (vault/get-secret client (str "org/acme/" done))))
        (is (= (str "org/acme/" done) (:resolved (stored-paths storage (bindings "done-fn"))))))
      (testing "the half-copied path is copied again from the source"
        (is (= "real" (vault/get-secret client (str "org/acme/" half))))
        (is (= 1 (:current_version (vault/get-metadata client (str "org/acme/" half))))
            "the partial copy was wiped, not appended to")
        (is (gone? half)))
      (finally (sp/close storage)))))


(deftest a-target-bound-by-another-secret-is-a-conflict
  (let [bare (str "clash-" (random-uuid))
        target (str "org/acme/" bare)
        {:keys [storage bindings]} (seed! [["legacy-fn" "acme" bare]
                                           ["recreated-fn" "acme" target]])
        raw (vcore/unwrap storage)
        client openbao/*client*]
    (vault/put-secret client bare "old")
    (vault/put-secret client target "new")
    (try
      (is (= {:rows 0 :paths 1 :conflicts 1 :failed 0} (sut/migrate! raw client)))
      (testing "neither secret is touched"
        (is (= "old" (vault/get-secret client bare)))
        (is (= "new" (vault/get-secret client target)))
        (is (= bare (:resolved (stored-paths storage (bindings "legacy-fn"))))))
      (finally (sp/close storage)))))
