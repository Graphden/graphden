(ns ^:integration graphden.executor.composition.multiple-inheritance-test
  "End-to-end tests for fn-def multiple inheritance (`:parents [a b]` syntax).

   These tests verify that:
   - sync writes parent-ids correctly to storage
   - args from multiple parents are merged
   - diamond inheritance (two parents sharing a common ancestor) resolves
     correctly: bound args win over free args, free args propagate
   - end-to-end execution produces the expected result"
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.composition.interface :as fn-composition]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.interface :as registry]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]))


(use-fixtures :once (setup/create-container-fixture))


(use-fixtures :each
  (setup/create-clean-db-fixture)
  exec/with-clean-registry)


;; ============================================================================
;; Storage-level: parents vector → parent-ids in storage
;; ============================================================================

(deftest sync-multiple-parents-test
  (testing ":parents [a b] is stored as parent-ids vector"
    (let [storage (setup/create-test-storage)
          _ (registry/initialize-all!
              storage
              [{:base-a {:args {} :return-type :int :impl (fn [_ _] 1)}
                :base-b {:args {} :return-type :int :impl (fn [_ _] 2)}}])
          ;; child has BOTH parents
          result (fn-composition/sync-fns-to-storage!
                   storage
                   [{:name :child :parents [:base-a :base-b]}])
          child-id (:child result)
          child (sp/read-entity storage :fn child-id)]
      (is (vector? (:parent-ids child)))
      (is (= 2 (count (:parent-ids child))))
      (is (= [(registry/fn-uuid :base-a) (registry/fn-uuid :base-b)]
             (:parent-ids child)))
      (sp/close storage))))


;; ============================================================================
;; Diamond inheritance: two parents binding orthogonal args of a shared base
;; ============================================================================

(deftest diamond-inheritance-end-to-end-test
  (testing "two parents binding different args, child inherits both bindings"
    (let [storage (setup/create-test-storage)
          ;; base-fn with three args
          _ (registry/initialize-all!
              storage
              [{:make-record {:args {:status :int
                                     :headers :jsonb
                                     :body :any}
                              :return-type :jsonb
                              :impl (setup/fn-impl [status headers body]
                                                   {:status status
                                                    :headers headers
                                                    :body body})}}])
          ;; Two single-parent fns binding orthogonal args
          ;; - ok-record:    binds status=200          (free: headers, body)
          ;; - json-record:  binds headers={CT json}   (free: status, body)
          ;; - json-ok-record parents=[ok-record json-record]
          ;;   expected: status=200 (from ok), headers={CT json} (from json), body free
          ;; NOTE: JSONB roundtrip converts string map keys to keywords, so we
          ;; use a keyword key here for stable equality checks.
          _ (fn-composition/sync-fns-to-storage!
              storage
              [{:name :ok-record   :parent :make-record :args {:status 200}}
               {:name :json-record :parent :make-record :args {:headers {:Content-Type "application/json"}}}
               {:name :json-ok-record :parents [:ok-record :json-record]}])
          ;; instance fn that binds the remaining free arg (:body)
          result (fn-composition/sync-fns-to-storage!
                   storage
                   [{:name :hello-response :parent :json-ok-record :args {:body "hello"}}])
          ctx (exec/create-context {:storage storage})
          out (exec/execute ctx (:hello-response result) {})]
      (is (= {:status 200
              :headers {:Content-Type "application/json"}
              :body "hello"}
             out))
      (sp/close storage))))


(deftest diamond-inheritance-arg-storage-test
  (testing "diamond child has args propagated from both parents"
    (let [storage (setup/create-test-storage)
          _ (registry/initialize-all!
              storage
              [{:make-record {:args {:status :int
                                     :headers :jsonb
                                     :body :any}
                              :return-type :jsonb
                              :impl (fn [_ _] nil)}}])
          result (fn-composition/sync-fns-to-storage!
                   storage
                   [{:name :ok-record   :parent :make-record :args {:status 200}}
                    {:name :json-record :parent :make-record :args {:headers {"Content-Type" "application/json"}}}
                    {:name :json-ok-record :parents [:ok-record :json-record]}])
          child-id (:json-ok-record result)
          child-args (sp/query-entities storage :arg {:fn-id child-id})]
      ;; Diamond child should expose ONE arg per name from the base.
      ;; If executor double-counts (one per parent), this will be > 3.
      (testing "no duplicate args by resolved name"
        (let [;; Resolve names through source-id chain
              by-id (into {} (map (juxt :id identity))
                          (sp/query-entities storage :arg {}))
              resolve-name (fn resolve-name
                             [arg]
                             (or (:name arg)
                                 (when-let [src (:source-id arg)]
                                   (resolve-name (get by-id src)))))
              names (frequencies (map resolve-name child-args))]
          (is (= {"status" 1 "headers" 1 "body" 1} names)
              (str "expected one arg per name, got " names))))
      (sp/close storage))))
