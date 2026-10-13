(ns graphden.graph.metadata-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is]]
    [graphden.graph.metadata :as metadata]
    [graphden.storage.remote.core :as remote]))


(defn- fixture
  []
  (let [root (random-uuid) home (random-uuid) other (random-uuid) function (random-uuid)]
    {:root root :home home :function function
     :rows {:ns [{:id root :name "users" :parent-id nil :org-id "acme"}
                 {:id home :name "stable-id" :parent-id root :org-id "acme"}
                 {:id other :name "users" :parent-id nil :org-id "other"}]
            :fn [{:id function :name "editor" :namespace-id home :org-id "acme"}]}}))


(defn- failure-type
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error (:type (ex-data error)))))


(deftest exact-home-lookup-does-not-adopt-other-organizations-or-missing-paths
  (let [{:keys [home rows]} (fixture)
        storage (remote/from-bundle rows)]
    (is (= home (:id (metadata/namespace-row storage "users.stable-id" "acme"))))
    (is (nil? (metadata/namespace-row storage "users.stable-id" "other")))
    (is (nil? (metadata/namespace-row storage "users.missing" "acme")))))


(deftest authorized-function-discovery-distinguishes-absence-from-denial
  (let [{:keys [home function rows]} (fixture)
        storage (remote/from-bundle rows)
        authorized (atom [])]
    (is (= function (:id (metadata/function-row storage home "editor" "acme"
                                                #(swap! authorized conj [(:id %1) %2])))))
    (is (= [[function {:execute? true}]] @authorized))
    (is (nil? (metadata/function-row storage home "missing" "acme"
                                     (fn [& _] (throw (AssertionError. "Missing function must not be authorized"))))))
    (is (= :authz/forbidden
           (failure-type #(metadata/function-row storage home "editor" "acme"
                                                 (fn [& _] (throw (ex-info "Denied" {:type :authz/forbidden})))))))))


(deftest ambiguous-or-invalid-metadata-never-selects-a-function
  (let [{:keys [home rows]} (fixture)
        duplicate (update rows :fn conj (assoc (first (:fn rows)) :id (random-uuid)))
        public (remote/from-bundle {:ns [{:id (random-uuid) :name "personal" :parent-id nil}
                                         {:id (random-uuid) :name "personal" :parent-id nil :org-id "public"}]})]
    (is (= :graph-metadata/ambiguous
           (failure-type #(metadata/function-row (remote/from-bundle duplicate) home "editor" "acme" (fn [& _])))))
    (is (= :graph-metadata/ambiguous
           (failure-type #(metadata/namespace-row public "personal" "public"))))
    (doseq [org [nil "" " "]]
      (is (= :graph-metadata/invalid-org
             (failure-type #(metadata/namespace-row public "personal" org)))))
    (doseq [path ["users." ".users" "users..id" (str/join (repeat 17 "a."))]]
      (is (= :graph-metadata/invalid-path
             (failure-type #(metadata/namespace-row (remote/from-bundle {}) path "acme")))))))


(deftest lookup-capability-is-exact-scope-and-never-converts-denial-to-absence
  (let [{:keys [rows function]} (fixture)
        storage (remote/from-bundle rows)
        scope {:namespace-path "users.stable-id" :name "editor" :principal-id "stable-id"
               :org "acme" :branch-id (random-uuid)}
        authorized (atom [])
        capability (metadata/function-lookup storage #(swap! authorized conj [%1 %2]) scope)]
    (is (= {:fn-id function} (metadata/lookup-function capability scope)))
    (is (= 1 (count @authorized)))
    (doseq [changed [(assoc scope :org "other") (assoc scope :principal-id "foreign")
                     (assoc scope :branch-id (random-uuid)) (assoc scope :name "other")
                     (assoc scope :namespace-path "users.foreign")]]
      (is (= :graph-metadata/scope-mismatch
             (failure-type #(metadata/lookup-function capability changed)))))
    (let [denied (metadata/function-lookup storage (fn [& _] (throw (ex-info "Denied" {:type :authz/forbidden}))) scope)]
      (is (= :authz/forbidden (failure-type #(metadata/lookup-function denied scope)))))
    (let [absent (assoc scope :namespace-path nil)]
      (is (nil? (metadata/lookup-function (metadata/function-lookup storage (fn [& _]) absent) absent))))))
