(ns ^:integration graphden.storage.remote.core-test
  "RemoteStorage — the read-only in-memory storage a BYO executor bootstraps
   from `GET /api/export/graph-rows`.

   The load-bearing test is `remote-storage-backs-compile-and-execute`: build a
   real graph in Postgres, export the raw rows, wrap them in a RemoteStorage
   (NO Postgres, NO HTTP), and prove the executor compiles + runs a fn through
   it. That proves the minimal read surface (query-entities / read-entity(s) +
   the ExecutionGraph satisfy-gate) is sufficient."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.context :as ectx]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.packages.export :as export]
    [graphden.packages.records :as records]
    [graphden.schema.graph.schema :as gds]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.schema.traits.schema :as vts]
    [graphden.schema.versioned.schema :as vds]
    [graphden.storage.postgres.core :as pg]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as pth]
    [graphden.storage.remote.core :as remote]
    [graphden.versioning.storage.core :as vs]
    [org.httpkit.server :as http-kit]))


(use-fixtures :once (setup/create-container-fixture) exec/with-clean-registry)


(defn- versioned-storage!
  []
  (pth/clean-database-fast! @(resolve 'graphden.executor.test-setup/*container*))
  (let [container @(resolve 'graphden.executor.test-setup/*container*)
        raw (pg/create-storage (pth/get-container-config container))]
    (sp/initialize raw (-> (mds/create-builder) (gds/extend-builder)
                           (vts/extend-builder) (vds/extend-builder) (ds/build)))
    (sp/upsert-entities raw :fn (mapv #(dissoc % :kind) (records/boot-primitive-records)))
    (vs/wrap-with-versioning raw "main")))


;; =============================================================================
;; Pure query matching
;; =============================================================================

(deftest query-matching-covers-executor-where-shapes
  (let [id1 (random-uuid) id2 (random-uuid) id3 (random-uuid)
        rs (remote/from-bundle
             {:fn [{:id id1 :name "a" :fn-id nil}
                   {:id id2 :name "b" :fn-id id1}
                   {:id id3 :name "c" :fn-id id1}]})]
    (testing "empty where → all rows"
      (is (= 3 (count (sp/query-entities rs :fn {})))))
    (testing "scalar equality {:name v}"
      (is (= [id2] (map :id (sp/query-entities rs :fn {:name "b"})))))
    (testing "vector value {:id [ids]} is a membership test"
      (is (= #{id1 id3} (set (map :id (sp/query-entities rs :fn {:id [id1 id3]}))))))
    (testing "{:fn-id [ids]} membership"
      (is (= #{id2 id3} (set (map :id (sp/query-entities rs :fn {:fn-id [id1]}))))))
    (testing "read-entity by id, read-entities by ids"
      (is (= "b" (:name (sp/read-entity rs :fn id2))))
      (is (nil? (sp/read-entity rs :fn (random-uuid))))
      (is (= #{"a" "c"} (set (map :name (sp/read-entities rs :fn [id1 id3]))))))
    (testing ":limit opt truncates"
      (is (= 2 (count (sp/query-entities rs :fn {} {:limit 2})))))))


(deftest writes-throw-read-only
  (let [rs (remote/from-bundle {:fn []})]
    (doseq [op [#(sp/create-entity rs :fn {})
                #(sp/update-entity rs :fn (random-uuid) {})
                #(sp/delete-entity rs :fn (random-uuid))
                #(sp/upsert-entities rs :fn [{}])]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"read-only" (op))))))


;; =============================================================================
;; The real thing: compile + execute through RemoteStorage
;; =============================================================================

(deftest remote-storage-backs-compile-and-execute
  (let [storage (versioned-storage!)]
    (exec/register-base-fn! :echo-x (fn [args _ctx] (get args :x)))
    (let [base (setup/create-base-fn! storage "echo-x" :any)
          slot (setup/create-slot! storage "x" :any)
          _ (setup/attach-slot! storage (:id base) (:id slot) 0)
          composed (setup/create-composed-fn! storage "echo-42" (:id base))
          _ (setup/bind-value! storage (:id composed) (:id slot) 42)
          ;; Export the raw rows exactly as GET /api/export/graph-rows would,
          ;; then hand them to a RemoteStorage — no PG, no HTTP from here on.
          bundle (export/read-graph storage)
          remote-storage (remote/from-bundle bundle)
          ctx (ectx/create-context {:storage remote-storage
                                    :base-fns (exec/get-default-registry)})]
      (try
        (testing "create-context accepts RemoteStorage (ExecutionGraph satisfy-gate)"
          (is (some? ctx)))
        (testing "the executor compiles the whole graph from RemoteStorage"
          (let [reg (cr/rebuild! ctx)]
            (is (contains? reg (:id composed)))))
        (testing "and executes a fn read entirely from memory"
          (is (= 42 (cr/execute ctx (:id composed) {}))))
        (finally (sp/close storage))))))


;; =============================================================================
;; HTTP bootstrap — create-remote-storage against a live stub server
;; =============================================================================

(deftest create-remote-storage-fetches-over-http
  (let [id (random-uuid)
        bundle {:fns [{:id id :name "over-http"}]
                :slots [] :fn-slots [] :bindings [] :list-items []}
        seen-auth (atom nil)
        seen-branch (atom :unset)
        handler (fn [req]
                  (reset! seen-auth (get-in req [:headers "authorization"]))
                  (reset! seen-branch (get-in req [:headers "x-graphden-branch"]))
                  (if (= "/api/export/graph-rows" (:uri req))
                    {:status 200
                     :headers {"Content-Type" "application/edn"}
                     :body (pr-str bundle)}
                    {:status 404 :body "no"}))
        stop (http-kit/run-server handler {:port 0})
        port (:local-port (meta stop))]
    (try
      (let [rs (remote/create-remote-storage (str "http://localhost:" port) "tok-123")]
        (testing "bootstrap pulled the rows over HTTP"
          (is (= "over-http" (:name (sp/read-entity rs :fn id)))))
        (testing "the bearer token was sent"
          (is (= "Bearer tok-123" @seen-auth)))
        (testing "no branch pin → no branch header"
          (is (nil? @seen-branch)))
        (testing "refresh! re-fetches"
          (is (true? (remote/refresh! rs)))))
      (testing "a branch pin sends X-Graphden-Branch on bootstrap AND refresh"
        (let [rs (remote/create-remote-storage (str "http://localhost:" port) "tok" "dev")]
          (is (= "dev" @seen-branch) "bootstrap carried the branch")
          (reset! seen-branch :unset)
          (remote/refresh! rs)
          (is (= "dev" @seen-branch) "refresh! re-fetches the same branch")))
      (finally (stop)))))


(deftest create-remote-storage-throws-on-non-200
  (let [handler (fn [_] {:status 403 :body "nope"})
        stop (http-kit/run-server handler {:port 0})
        port (:local-port (meta stop))]
    (try
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"returned 403"
            (remote/create-remote-storage (str "http://localhost:" port) "tok")))
      (finally (stop)))))
