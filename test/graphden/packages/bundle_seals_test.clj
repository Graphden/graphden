(ns ^:integration graphden.packages.bundle-seals-test
  "`sync-bundle!` refuses a bundle that breaks an ancestor's seal —
   over storage AND over the bundle's own rows — before writing a row.
   An MCP `upsert-fn-defs` and a registry install / fork / import all
   come through here; until 2026-09-20 they bypassed the API's
   value-override / terminal-seal / list-closed refusals entirely."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.packages.sync :as pkg-sync]
    [graphden.storage.protocol.core :as sp]
    [graphden.test-infra.golden-app :as ga]))


(use-fixtures :once (ga/fixture (ns-name *ns*)))


(defn- storage
  []
  (:storage ga/*bootstrap*))


(defn- fn-named
  [n]
  (first (sp/query-entities (storage) :fn {:name n})))


(defn- sync!
  [fn-defs]
  (pkg-sync/sync-bundle! (storage) (mapv #(assoc % :namespace "seals.t") fn-defs)))


(defn- refusal
  [fn-defs]
  (try (sync! fn-defs) nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))


(deftest a-bundle-cannot-override-a-value-an-ancestor-set
  (sync! [{:name :bs-base :parent :const :args {:value {:status 200}}}])
  (testing "a child re-binding the parent's valued slot is refused, and nothing lands"
    (let [rej (refusal [{:name :bs-child :parent :bs-base :args {:value {:status 500}}}])]
      (is (= :constraint-violation/value-override (:type rej)) (pr-str rej))
      (is (nil? (fn-named "bs-child")) "no fn row was written")))
  (testing "a platform value is final for a tenant bundle too — :ok-response pins :status"
    (let [rej (refusal [{:name :bs-status :parent :json-ok-response :args {:status 500}}])]
      (is (= :constraint-violation/value-override (:type rej)) (pr-str rej))))
  (testing "a seal two levels up in storage is found through the batched ancestor walk"
    (sync! [{:name :bs-mid :parent :bs-base}])
    (let [rej (refusal [{:name :bs-grand :parent :bs-mid :args {:value {:status 404}}}])]
      (is (= :constraint-violation/value-override (:type rej)) (pr-str rej))
      (is (nil? (fn-named "bs-grand")))))
  (testing "the sealer and the offender in ONE bundle are caught through the overlay"
    (let [rej (refusal [{:name :bs-p :parent :add :args {:nums {:append [1] :closed true}}}
                        {:name :bs-c :parent :bs-p :args {:nums [2]}}])]
      (is (= :constraint-violation/list-closed (:type rej)) (pr-str rej))
      (is (nil? (fn-named "bs-p")) "the whole bundle is refused, sealer included"))))


(deftest a-bundle-may-extend-an-open-list-and-bind-a-free-slot
  (sync! [{:name :bs-seed :parent :add :args {:nums [1 2]}}])
  (is (seq (sync! [{:name :bs-more :parent :bs-seed :args {:nums [3]}}]))
      "appending to an inherited open list is how lists compose")
  (testing "a slot sealed with :terminal refuses the child; re-syncing the sealer itself is fine"
    (sync! [{:name :bs-tpl :parent :add :args {:nums {:terminal true}}}])
    (is (seq (sync! [{:name :bs-tpl :parent :add :args {:nums {:terminal true}}}]))
        "the sealer's own binding is not its ancestor's")
    (is (= :constraint-violation/terminal-seal
           (:type (refusal [{:name :bs-tpl-child :parent :bs-tpl :args {:nums [9]}}])))))
  (testing "a route's items list is closed — appending a third element is refused"
    (is (= :constraint-violation/list-closed
           (:type (refusal [{:name :bs-route3 :parent :get-route :args {:items ["extra"]}}]))))))
