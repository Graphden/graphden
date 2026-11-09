(ns ^:serial graphden.executor.browser-policy-cache-test
  "Pure policy reuse; serial because classification counting wraps a public Var."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.executor.browser-snapshot :as snapshot]
    [graphden.executor.registry.core :as registry]
    [graphden.types.check.provenance :as provenance]
    [graphden.types.core.shapes :as shapes]))


(defn- plain-source
  []
  (let [id (random-uuid)]
    {:id id :rich {:by-id {id {:fn-id id :args {} :return :text}}}}))


(deftest repeated-policy-captures-classify-once
  (let [{:keys [id rich]} (plain-source)
        classify registry/trace-capture-class
        calls (atom 0)]
    (binding [shapes/*marker-registry-override* (atom (shapes/markers-snapshot))]
      (with-redefs [registry/trace-capture-class
                    (fn [fn-id row-name]
                      (swap! calls inc)
                      (classify fn-id row-name))]
        (let [results (repeatedly 7 #(snapshot/capture-policy rich))]
          (is (every? #(= :plain (get-in % [:classes id])) results))
          (is (= 2 @calls) "original and current classification once, not seven times"))))))


(deftest simultaneous-policy-captures-share-the-computation
  (let [{:keys [id rich]} (plain-source)
        classify registry/trace-capture-class
        calls (atom 0)
        entered (atom 0)
        all-entered (promise)
        classifying (promise)
        release (promise)]
    (binding [shapes/*marker-registry-override* (atom (shapes/markers-snapshot))]
      (with-redefs [registry/trace-capture-class
                    (fn [fn-id row-name]
                      (swap! calls inc)
                      (deliver classifying true)
                      (when (= ::timeout (deref release 10000 ::timeout))
                        (throw (ex-info "Classification release timed out" {})))
                      (classify fn-id row-name))]
        (let [jobs (mapv (fn [_]
                           (future
                             (when (= 7 (swap! entered inc)) (deliver all-entered true))
                             (snapshot/capture-policy rich)))
                         (range 7))]
          (try
            (is (= true (deref all-entered 10000 ::timeout)))
            (is (= true (deref classifying 10000 ::timeout)))
            (deliver release true)
            (is (every? #(= :plain (get-in (deref % 10000 {}) [:classes id])) jobs))
            (is (= 2 @calls) "concurrent misses must not repeat the full registry scan")
            (finally
              (deliver release true)
              (doseq [job jobs] (future-cancel job)))))))))


(deftest replacing-a-source-identity-invalidates-policy
  (let [{:keys [id rich]} (plain-source)]
    (is (= :plain (get-in (snapshot/capture-policy rich) [:classes id])))
    (let [changed (assoc-in rich [:by-id id :return] [:secret :text])]
      (is (= :secret-output (get-in (snapshot/capture-policy changed) [:classes id]))))))


(deftest marker-flags-and-provenance-are-part-of-policy-inputs
  (let [id (random-uuid)
        definition {:fn-id id :name :view :args {} :return-type [:private-tag :text]}
        flags {:private-tag {:hide-result? false}}
        signature (binding [shapes/*marker-registry-override* (atom flags)]
                    ((provenance/capture-marker-stamp definition id)
                     (provenance/stamp {:fn-id id :args {} :return [:private-tag :text]} definition)))
        rich {:by-id {id signature}}
        markers (atom flags)]
    (binding [shapes/*marker-registry-override* markers]
      (is (= :plain (get-in (snapshot/capture-policy rich) [:classes id])))
      (testing "a fresh marker restriction vetoes a previously plain signature"
        (swap! markers assoc-in [:private-tag :hide-result?] true)
        (is (= :secret-output (get-in (snapshot/capture-policy rich) [:classes id]))))
      (reset! markers flags)
      (is (= :plain (get-in (snapshot/capture-policy rich) [:classes id])))
      (testing "equal map values cannot reuse a signature with different evidence"
        (let [unwitnessed (assoc-in rich [:by-id id] (provenance/stamp (with-meta signature nil) definition))]
          (is (= rich unwitnessed) "ordinary map equality ignores provenance metadata")
          (is (not (identical? rich unwitnessed)))
          (is (= :unknown (get-in (snapshot/capture-policy unwitnessed) [:classes id]))))))))


(deftest policy-reuse-does-not-cache-the-recheck-frontier
  (let [{:keys [id rich]} (plain-source)
        frontier (atom {:status :complete :pending-ids #{}})
        ctx {:rich-types-atom (atom rich) :type-recheck-state frontier}]
    (is (= {:status :complete :pending-ids #{}}
           (:type-recheck (snapshot/capture-context-policy ctx))))
    (reset! frontier {:status :running :pending-ids #{id}})
    (is (= {:status :running :pending-ids #{id}}
           (:type-recheck (snapshot/capture-context-policy ctx))))))


(deftest policy-cache-retains-only-one-input-pair
  (let [first-source (plain-source)
        second-source (plain-source)
        classify registry/trace-capture-class
        calls (atom 0)]
    (with-redefs [registry/trace-capture-class
                  (fn [fn-id row-name]
                    (swap! calls inc)
                    (classify fn-id row-name))]
      (doseq [{:keys [id rich]} [first-source second-source first-source]]
        (is (= :plain (get-in (snapshot/capture-policy rich) [:classes id]))))
      (is (= 6 @calls) "returning to an evicted source computes it again"))))


(deftest failed-policy-computation-can-be-retried
  (let [{:keys [id rich]} (plain-source)
        classify registry/trace-capture-class
        fail-once (atom true)]
    (with-redefs [registry/trace-capture-class
                  (fn [fn-id row-name]
                    (if (compare-and-set! fail-once true false)
                      (throw (ex-info "Test classification failure" {}))
                      (classify fn-id row-name)))]
      (is (= :policy-capture-failed
             (:reason (try (snapshot/capture-policy rich)
                           (catch clojure.lang.ExceptionInfo error (ex-data error))))))
      (is (= :plain (get-in (snapshot/capture-policy rich) [:classes id]))))))
