(ns graphden.input.source-inline-evidence-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.input.migration :as migration]
    [graphden.input.source :as source]
    [graphden.input.source-inline-evidence :as inline]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]))


(defn- fixture
  []
  (let [ns-id "fixture"
        reader {:namespace ns-id :name :reader :args {:message :text} :return-type :text}
        sink {:namespace ns-id :name :sink :args {:value :text} :return-type :text}
        owner {:namespace ns-id :name :owner :parent :sink
               :args {:value {:parent :reader :args {:message "first"}}
                      :message {:parent :reader :args {:message "second"}}}}]
    {:prepared (parse/prepare-module [reader sink owner] {} {}) :owner owner}))


(defn- code
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error (:code (ex-data error)))))


(deftest can-prove-the-detached-child-of-a-blocked-owner
  (let [{:keys [prepared owner]} (fixture)
        path [:args :message]
        plan (first (:definitions (source/plan-module [owner] prepared)))
        {:keys [origin child]} (inline/verify-child prepared owner path)
        [{:keys [target-path id value]}] (:bindings child)
        owner-id (ids/fn-id "fixture" :owner)
        message (ids/slot-id (ids/fn-id "fixture" :reader) :message)]
    (is (= [[:ref-scope-required path]] (mapv (juxt :code :source-path) (:blockers plan))))
    (is (= owner-id (:fn-id origin)))
    (is (= (ids/binding-id owner-id message) (:binding-id origin)))
    (is (= path (:source-path origin)))
    (is (= (ids/digest-hex "SHA-256" (migration/stable-edn owner)) (:definition-sha256 origin)))
    (is (= [message] target-path))
    (is (= (ids/binding-id (:fn-id child) message) id))
    (is (= "second" value))))


(deftest origin-proof-requires-the-exact-raw-site-and-prepared-owner
  (let [{:keys [prepared owner]} (fixture)
        path [:args :message]
        prepared-owner [:defs-by-name :fixture/owner]
        ref (get-in prepared (into prepared-owner path))
        child-id-path [:defs-by-name (keyword "fixture" (name ref)) :fn-id]]
    (is (= :direct-inline-source-path-required
           (code #(inline/verify-child prepared owner [:args :value :args :message]))))
    (is (= :inline-source-origin-required
           (code #(inline/verify-child prepared (update owner :args dissoc :message) path))))
    (is (= :inline-argument-ledger-required
           (code #(inline/verify-child prepared (assoc-in owner [:args :message :args :message] "forged") path))))
    (is (= :inline-argument-ledger-required
           (code #(inline/verify-child (assoc-in prepared child-id-path (random-uuid)) owner path))))
    (is (= :inline-argument-ledger-required
           (code #(inline/verify-child (assoc-in prepared (into prepared-owner path)
                                                 (get-in prepared (into prepared-owner [:args :value]))) owner path))))))
