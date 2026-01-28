(ns graphden.cache-datomic.interface-test
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [datomic.client.api :as d]
    [graphden.cache-datomic.interface :as cache-datomic]
    [graphden.cache-protocol.interface :as cache]
    [graphden.storage-protocol.interface :as sp]))


;; === Test fixtures ===

(def ^:dynamic *conn* nil)


(defn- create-test-client
  []
  (d/client {:server-type :datomic-local
             :storage-dir :mem
             :system "cache-datomic-test"}))


(defn- with-datomic-conn
  [f]
  (let [client (create-test-client)
        db-name (str "test-" (random-uuid))]
    (d/create-database client {:db-name db-name})
    (let [conn (d/connect client {:db-name db-name})]
      (cache-datomic/ensure-cache-schema! conn)
      (binding [*conn* conn]
        (try
          (f)
          (finally
            (d/delete-database client {:db-name db-name})))))))


(use-fixtures :each with-datomic-conn)


;; === Unit tests ===

(deftest create-cache-test
  (testing "creates DatomicCache instance"
    (let [cache (cache-datomic/create-cache *conn*)]
      (is (some? cache))
      (is (cache/cached-storage? cache)))))


(deftest cache-exists-test
  (testing "returns false for non-existent cache"
    (let [cache (cache-datomic/create-cache *conn*)
          fn-id (random-uuid)]
      (is (false? (cache/cache-exists? cache fn-id))))))


(deftest save-and-get-cache-test
  (testing "saves and retrieves execution graph"
    (let [cache (cache-datomic/create-cache *conn*)
          fn-id (random-uuid)
          fn-schema-id (random-uuid)
          arg-schema-id (random-uuid)
          graph {:fns {fn-id {:id fn-id
                              :name "test-fn"
                              :fn-schema-id fn-schema-id}}
                 :fn-schemas {fn-schema-id {:id fn-schema-id
                                            :name "test-schema"
                                            :base-fn-name "base-fn"
                                            :returned-type :text}}
                 :arg-schemas {arg-schema-id {:id arg-schema-id
                                              :fn-schema-id fn-schema-id
                                              :name "arg1"
                                              :type :text
                                              :required true}}
                 :resolved-args {fn-id {arg-schema-id "test-value"}}
                 :call-sites {}}
          dependencies {:fn-ids {fn-id 1}
                        :fn-schema-ids {fn-schema-id 1}
                        :arg-schema-ids {arg-schema-id 1}}]
      ;; Save cache
      (cache/save-cache! cache fn-id graph dependencies)
      ;; Verify it exists
      (is (true? (cache/cache-exists? cache fn-id)))
      ;; Get cached graph
      (let [cached (cache/get-cached-graph cache fn-id)]
        (is (some? cached))
        (is (sp/execution-graph? cached))
        ;; Verify fns
        (is (= 1 (count (:fns cached))))
        (is (= "test-fn" (get-in cached [:fns fn-id :name])))
        ;; Verify fn-schemas
        (is (= 1 (count (:fn-schemas cached))))
        (is (= "test-schema" (get-in cached [:fn-schemas fn-schema-id :name])))
        (is (= :text (get-in cached [:fn-schemas fn-schema-id :returned-type])))
        ;; Verify arg-schemas
        (is (= 1 (count (:arg-schemas cached))))
        (is (= "arg1" (get-in cached [:arg-schemas arg-schema-id :name])))
        (is (= :text (get-in cached [:arg-schemas arg-schema-id :type])))
        ;; Verify resolved-args
        (is (= "test-value" (get-in cached [:resolved-args fn-id arg-schema-id])))))))


(deftest delete-cache-test
  (testing "deletes cache data"
    (let [cache (cache-datomic/create-cache *conn*)
          fn-id (random-uuid)
          fn-schema-id (random-uuid)
          graph {:fns {fn-id {:id fn-id
                              :name "test-fn"
                              :fn-schema-id fn-schema-id}}
                 :fn-schemas {fn-schema-id {:id fn-schema-id
                                            :name "test-schema"
                                            :base-fn-name "base-fn"
                                            :returned-type :text}}
                 :arg-schemas {}
                 :resolved-args {}
                 :call-sites {}}
          dependencies {:fn-ids {} :fn-schema-ids {} :arg-schema-ids {}}]
      ;; Save and verify
      (cache/save-cache! cache fn-id graph dependencies)
      (is (true? (cache/cache-exists? cache fn-id)))
      ;; Delete
      (cache/delete-cache! cache fn-id)
      ;; Verify deleted
      (is (false? (cache/cache-exists? cache fn-id)))
      (is (nil? (cache/get-cached-graph cache fn-id))))))


(deftest find-caches-by-deps-test
  (testing "finds caches by dependency"
    (let [cache (cache-datomic/create-cache *conn*)
          fn-id-1 (random-uuid)
          fn-id-2 (random-uuid)
          shared-dep-fn-id (random-uuid)
          fn-schema-id (random-uuid)
          ;; First cache depends on shared-dep-fn-id
          graph-1 {:fns {fn-id-1 {:id fn-id-1
                                  :name "fn-1"
                                  :fn-schema-id fn-schema-id}}
                   :fn-schemas {fn-schema-id {:id fn-schema-id
                                              :name "schema"
                                              :base-fn-name "base"
                                              :returned-type :text}}
                   :arg-schemas {}
                   :resolved-args {}
                   :call-sites {}}
          deps-1 {:fn-ids {shared-dep-fn-id 1}
                  :fn-schema-ids {fn-schema-id 1}
                  :arg-schema-ids {}}
          ;; Second cache also depends on shared-dep-fn-id
          graph-2 {:fns {fn-id-2 {:id fn-id-2
                                  :name "fn-2"
                                  :fn-schema-id fn-schema-id}}
                   :fn-schemas {fn-schema-id {:id fn-schema-id
                                              :name "schema"
                                              :base-fn-name "base"
                                              :returned-type :text}}
                   :arg-schemas {}
                   :resolved-args {}
                   :call-sites {}}
          deps-2 {:fn-ids {shared-dep-fn-id 2}
                  :fn-schema-ids {fn-schema-id 1}
                  :arg-schema-ids {}}]
      ;; Save both caches
      (cache/save-cache! cache fn-id-1 graph-1 deps-1)
      (cache/save-cache! cache fn-id-2 graph-2 deps-2)
      ;; Find caches by fn dependency
      (let [affected (cache/find-caches-by-fn-dep cache shared-dep-fn-id)]
        (is (set? affected))
        (is (= 2 (count affected)))
        (is (contains? affected fn-id-1))
        (is (contains? affected fn-id-2)))
      ;; Find caches by fn-schema dependency
      (let [affected (cache/find-caches-by-fn-schema-dep cache fn-schema-id)]
        (is (= 2 (count affected)))))))


(deftest cache-overwrites-existing-test
  (testing "save-cache! overwrites existing cache"
    (let [cache (cache-datomic/create-cache *conn*)
          fn-id (random-uuid)
          fn-schema-id (random-uuid)
          graph-v1 {:fns {fn-id {:id fn-id
                                 :name "v1"
                                 :fn-schema-id fn-schema-id}}
                    :fn-schemas {fn-schema-id {:id fn-schema-id
                                               :name "schema"
                                               :base-fn-name "base"
                                               :returned-type :text}}
                    :arg-schemas {}
                    :resolved-args {}
                    :call-sites {}}
          graph-v2 {:fns {fn-id {:id fn-id
                                 :name "v2"
                                 :fn-schema-id fn-schema-id}}
                    :fn-schemas {fn-schema-id {:id fn-schema-id
                                               :name "schema"
                                               :base-fn-name "base"
                                               :returned-type :text}}
                    :arg-schemas {}
                    :resolved-args {}
                    :call-sites {}}
          deps {:fn-ids {} :fn-schema-ids {} :arg-schema-ids {}}]
      ;; Save v1
      (cache/save-cache! cache fn-id graph-v1 deps)
      (is (= "v1" (get-in (cache/get-cached-graph cache fn-id) [:fns fn-id :name])))
      ;; Save v2 (should overwrite)
      (cache/save-cache! cache fn-id graph-v2 deps)
      (is (= "v2" (get-in (cache/get-cached-graph cache fn-id) [:fns fn-id :name]))))))
