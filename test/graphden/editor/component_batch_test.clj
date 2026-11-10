(ns ^:serial graphden.editor.component-batch-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.editor.components :as components]
    [graphden.editor.config-resolution :as resolution]
    [graphden.executor.browser-snapshot :as snapshot]
    [graphden.executor.browser-source :as source]
    [graphden.executor.context :as context]
    [graphden.storage.protocol.core :as sp]))


(def ^:private selected (random-uuid))
(def ^:private inspector (random-uuid))
(def ^:private explorer (random-uuid))
(def ^:private ctx {:storage {:snapshot :test} :base-fns {} :rich-types-atom (atom {})})


(defn- with-export-fixture
  [f]
  (let [events (atom [])
        current-epoch (atom 1)
        failures (atom {})
        guard (fn [& args] (swap! events conj [:authorize args]))]
    (with-redefs [context/invalidation-epoch (fn [_] @current-epoch)
                  context/cached-graph (constantly {:hint :only})
                  snapshot/capture-context-policy (fn [_] (swap! events conj :policy) {:frozen true})
                  source/with-snapshot (fn [storage consume]
                                         (swap! events conj :snapshot)
                                         (consume storage guard))
                  components/assert-current-policy! (fn [_] (swap! events conj :fresh))
                  resolution/capture (fn [storage authorize! policy]
                                       (is (= (:storage ctx) storage))
                                       (is (identical? guard authorize!))
                                       (is (= {:frozen true} policy))
                                       (swap! events conj :capture)
                                       {:id selected :revision "revision" :scope {:branch-id selected}
                                        :configuration {:inspector {:view inspector} :explorer {:view explorer}}})
                  sp/read-entity (fn [_ entity id] (is (= :fn entity)) {:id id})
                  sp/query-entities (fn [_ entity _ opts]
                                      (is (= [:ui-pref {:limit 2}] [entity opts]))
                                      (swap! events conj :preference) [])
                  resolution/owned-writable-components (fn [& _] (swap! events conj :writable) {})
                  source/collect-closure (fn [storage roots authorize! hint]
                                           (is (= (:storage ctx) storage))
                                           (is (identical? guard authorize!))
                                           (is (= {:hint :only} hint))
                                           (swap! events conj [:closure (vec roots)])
                                           (when-let [error (get @failures (first roots))] (throw error))
                                           {:roots (vec roots)})
                  source/collect-namespaces (constantly [])
                  snapshot/export-snapshot (fn [source _ entries policy]
                                             (is (= {:frozen true} policy))
                                             (swap! events conj [:plan entries])
                                             {:graph (:graph source) :entries entries})]
      (f {:events events :epoch current-epoch :failures failures}))))


(deftest batch-shares-only-configuration-and-storage-snapshot
  (with-export-fixture
    (fn [{:keys [events]}]
      (let [result (components/export-resolved-batch ctx {:components ["inspector" "explorer"]})
            recorded @events]
        (is (true? (:ok result)))
        (doseq [event [:policy :snapshot :capture :preference :writable]]
          (is (= 1 (count (filter #{event} recorded)))))
        (is (= [:policy :snapshot :capture] (take 3 recorded)))
        (is (= [[:closure [inspector]] [:closure [explorer]]]
               (filter #(and (vector? %) (= :closure (first %))) recorded)))
        (is (= :fresh (last recorded)))
        (doseq [component ["inspector" "explorer"]]
          (is (= (components/export-resolved ctx {:component component})
                 (get-in result [:results component]))))))))


(deftest invalid-batches-read-no-source
  (with-redefs [source/with-snapshot (fn [& _] (throw (AssertionError. "Unexpected read")))]
    (doseq [input [nil {} {:components []} {:components '("inspector")}
                   {:components ["unknown"]} {:components [:inspector]}
                   {:components ["inspector" "inspector"]}
                   {:components ["inspector"] :component "explorer"}
                   {:components ["inspector"] :fn-id selected}]]
      (is (false? (:ok (components/export-resolved-batch ctx input)))))))


(deftest individual-failures-preserve-other-components
  (doseq [error [(ex-info "PRIVATE ACL" {:type :authz/forbidden})
                 (ex-info "PRIVATE budget" {:type :browser-plan/unsupported :reason :source-row-limit})
                 (ex-info "PRIVATE contract" {:type :browser-plan/unsupported :reason :public-type-contract-mismatch})]]
    (with-export-fixture
      (fn [{:keys [failures]}]
        (reset! failures {inspector error})
        (let [result (components/export-resolved-batch ctx {:components ["inspector" "explorer" "canvas"]})]
          (is (true? (:ok result)))
          (is (= (components/failure-facts error) (get-in result [:results "inspector"])))
          (is (true? (get-in result [:results "explorer" :ok])))
          (is (= "component-missing" (get-in result [:results "canvas" :failure-reason])))
          (is (not (.contains (pr-str result) "PRIVATE"))))))))


(deftest transaction-and-policy-failures-invalidate-entire-batch
  (doseq [error [(java.sql.SQLException. "PRIVATE aborted transaction")
                 (ex-info "wrapped" {:type :storage-error/candidate-limit} (java.sql.SQLException. "PRIVATE nested"))
                 (ex-info "PRIVATE storage" {:type :storage-error/unknown})
                 (ex-info "PRIVATE policy" {:type :browser-plan/unsupported :reason :policy-refresh-required})]]
    (with-export-fixture
      (fn [{:keys [failures events]}]
        (reset! failures {inspector error})
        (let [result (components/export-resolved-batch ctx {:components ["inspector" "explorer"]})]
          (is (false? (:ok result)))
          (is (not (contains? result :results)))
          (is (not-any? #{[:closure [explorer]]} @events))))))
  (with-export-fixture
    (fn [{:keys [epoch]}]
      (with-redefs [snapshot/export-snapshot (fn [& _] (swap! epoch inc) {})]
        (let [result (components/export-resolved-batch ctx {:components ["inspector" "explorer"]})]
          (is (= "policy-refresh-required" (:failure-reason result)))
          (is (not (contains? result :results))))))))
