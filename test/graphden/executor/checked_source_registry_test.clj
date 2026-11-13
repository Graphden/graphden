(ns graphden.executor.checked-source-registry-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.executor.checked-source-data :as data])
  (:import
    (java.util
      Date
      UUID)))


(def ^:private admission {:max-bytes 10000000 :max-nodes 100000 :callbacks []})


(defn- registry-fixture
  [empty-map]
  (let [shared-id (UUID. 0 1)
        distinct-id (UUID. 1 0)
        missing-id (UUID. 0 2)
        nil-id (UUID. 0 3)
        shared (with-meta {:return :text :default (vec (repeat 256 "shared payload"))} {:line 1})
        nonmirrored (with-meta (into {} shared) (meta shared))
        global (into empty-map [[shared-id shared] [distinct-id shared] [nil-id nil]])
        by-id (with-meta (into empty-map [[shared-id shared] [distinct-id nonmirrored]
                                          [missing-id nil] [nil-id nil]]) {:line 2})
        names {"shared" shared-id "nonmirrored" distinct-id}
        org-registry (with-meta (assoc empty-map :by-id by-id :by-name names :extra [:kept]) {:line 3})
        other-org {:by-id {shared-id {:return :number}} :by-name {"shared" shared-id}}
        per-org (with-meta (assoc empty-map "acme" org-registry "beta" other-org) {:line 4})]
    {:state (with-meta {:rich {:by-id global} :per-org per-org} {:line 5})
     :shared shared :nonmirrored nonmirrored :names names
     :shared-id shared-id :distinct-id distinct-id :missing-id missing-id :nil-id nil-id}))


(deftest compact-artifact-preserves-registry-semantics-and-retained-identities
  (doseq [empty-map [{} clojure.lang.PersistentHashMap/EMPTY]]
    (let [{:keys [state shared nonmirrored names shared-id distinct-id missing-id nil-id]}
          (registry-fixture empty-map)
          encoded (data/compact-registry-state state)
          restored (data/expand-registry-state encoded)]
      (is (= (hash shared-id) (hash distinct-id)) "Exercise an actual UUID hash collision")
      (is (= #{shared-id nil-id} (get-in encoded [:mirrors "acme"])))
      (is (not (contains? (get-in encoded [:mirrors "acme"]) missing-id)))
      (is (= state restored))
      (is (data/same-input? state restored) "Includes exact map classes, iteration and metadata identity")
      (is (identical? (:rich state) (:rich restored)))
      (is (identical? shared (get-in restored [:per-org "acme" :by-id shared-id])))
      (is (identical? nonmirrored (get-in restored [:per-org "acme" :by-id distinct-id])))
      (is (identical? names (get-in restored [:per-org "acme" :by-name])))
      (is (identical? (get-in state [:per-org "beta"]) (get-in restored [:per-org "beta"]))
          "Another tenant's nonmirrored signature is retained without substitution")
      (is (< (data/charge encoded admission) (data/charge state admission))))))


(deftest compaction-never-hides-ineligible-mirrored-data
  (doseq [bad [(Date. 0) (with-meta {} {:unknown :metadata})]]
    (let [signature {:return :any :default bad}
          state {:rich {:by-id {:example signature}}
                 :per-org {"acme" {:by-id {:example signature}}}}
          encoded (data/compact-registry-state state)]
      (is (= #{:example} (get-in encoded [:mirrors "acme"])))
      (is (nil? (data/charge encoded admission))))))


(deftype HiddenRegistry
  [reads]

  clojure.lang.ILookup

  (valAt [_ _] (swap! reads inc) {})


  (valAt [_ _ not-found] (swap! reads inc) not-found))


(deftest unknown-registry-containers-remain-ineligible-without-executing-lookups
  (let [reads (atom 0)
        hidden (HiddenRegistry. reads)
        state {:rich {:by-id {}} :per-org {"acme" hidden}}
        encoded (data/compact-registry-state state)]
    (is (identical? hidden (get-in encoded [:registry :per-org "acme"])))
    (is (nil? (data/charge encoded admission)))
    (is (zero? @reads))))


(deftype HostileRegistryKey
  [calls]

  Object

  (hashCode [_] (swap! calls inc) 0)


  (equals [this other] (swap! calls inc) (identical? this other)))


(deftest unknown-registry-keys-are-never-used-for-probing-or-association
  (let [calls (atom 0)
        hostile (HostileRegistryKey. calls)
        signature {:return :text}
        global {:example signature}]
    (doseq [per-org [(array-map hostile {:by-id {:example signature}})
                     {"acme" {:by-id (array-map hostile signature)}}]]
      (reset! calls 0)
      (let [encoded (data/compact-registry-state {:rich {:by-id global} :per-org per-org})]
        (is (nil? (data/charge encoded admission)))
        (is (zero? @calls))))
    (let [artifact {:registry {:rich {:by-id global} :per-org {"acme" {:by-id {:example nil}}}}
                    :mirrors {"acme" #{hostile}}}]
      (reset! calls 0)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid compact checker registry"
            (data/expand-registry-state artifact)))
      (is (zero? @calls)))))


(deftest invalid-artifact-references-are-refused
  (let [{:keys [state shared-id]} (registry-fixture {})
        encoded (data/compact-registry-state state)]
    (testing "a reference cannot silently restore a missing signature as nil"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid compact checker registry"
            (data/expand-registry-state
              (update-in encoded [:registry :rich :by-id] dissoc shared-id)))))
    (testing "a reference cannot insert a missing tenant key"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid compact checker registry"
            (data/expand-registry-state
              (update-in encoded [:registry :per-org "acme" :by-id] dissoc shared-id)))))
    (testing "a reference cannot overwrite an unexpected non-placeholder value"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid compact checker registry"
            (data/expand-registry-state
              (assoc-in encoded [:registry :per-org "acme" :by-id shared-id] :unexpected)))))))
