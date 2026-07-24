(ns graphden.dev.integrity-test
  "The integrity checker against a storage seeded with exactly the
   legacy shapes it exists to find — the outage class (stale
   same-named identities with resolved refs on the OLD id) plus the
   orphan families."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.dev.integrity :as integrity]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]))


(use-fixtures :once (setup/create-container-fixture))


(deftest detects-and-repairs-the-outage-shape
  (let [storage (setup/create-test-storage)]
    (try
      (let [ns-id nil
            ;; canonical + stale twin under the same (ns, name) —
            ;; the ns-move leftover shape.
            canonical (sp/create-entity storage :fn
                                        {:id (random-uuid)
                                         :name "moved-fn"
                                         :namespace-id ns-id
                                         :parent-ids []})
            stale (sp/create-entity storage :fn
                                    {:id (random-uuid)
                                     :name "moved-fn"
                                     :namespace-id ns-id
                                     :parent-ids []})
            ;; two callers: two refs at the canonical, one at the
            ;; stale — ranking must pick the majority target.
            caller-base (setup/create-base-fn! storage "int-caller")
            slot-a (setup/create-slot! storage "a" :int)
            slot-b (setup/create-slot! storage "b" :int)
            slot-c (setup/create-slot! storage "c" :int)
            _ (setup/attach-slot! storage (:id caller-base) (:id slot-a) 0)
            _ (setup/attach-slot! storage (:id caller-base) (:id slot-b) 1)
            _ (setup/attach-slot! storage (:id caller-base) (:id slot-c) 2)
            caller (setup/create-composed-fn! storage "int-caller-use"
                                              (:id caller-base))
            _ (sp/create-entity storage :binding
                                {:fn-id (:id caller) :slot-id (:id slot-a)
                                 :ref-fn-id (:id canonical)})
            _ (sp/create-entity storage :binding
                                {:fn-id (:id caller) :slot-id (:id slot-b)
                                 :ref-fn-id (:id canonical)})
            stale-ref (sp/create-entity storage :binding
                                        {:fn-id (:id caller)
                                         :slot-id (:id slot-c)
                                         :ref-fn-id (:id stale)})]
        (testing "detect: the twin group is found with majority-canonical"
          (let [[grp :as groups] (integrity/stale-identities storage)]
            (is (= 1 (count groups)))
            (is (= (:id canonical) (:id (:canonical grp))))
            (is (= [(:id stale)] (mapv :id (:extras grp))))))
        (testing "detect: the stale ref is flagged"
          (let [d (integrity/dangling-refs storage)]
            (is (some #(and (= :binding (:entity %))
                            (= (:id stale-ref) (:id %))
                            (= :stale-extra (:kind %)))
                      d))))
        (testing "dry-run plans, changes nothing"
          (let [r (integrity/repair-stale-identities! storage)]
            (is (:dry-run? r))
            (is (= 1 (:groups r)))
            (is (pos? (:removed r)))
            (is (pos? (:repointed r)))
            (is (= (:id stale)
                   (:ref-fn-id (sp/read-entity storage :binding
                                               (:id stale-ref)))))))
        (testing "repair repoints the ref and removes the extra subgraph"
          (integrity/repair-stale-identities! storage {:dry-run? false})
          (is (= (:id canonical)
                 (:ref-fn-id (sp/read-entity storage :binding
                                             (:id stale-ref)))))
          (is (empty? (integrity/stale-identities storage))
              "post-repair the graph is clean")))
      (finally (sp/close storage)))))


(deftest detects-orphan-families
  (let [storage (setup/create-test-storage)]
    (try
      (let [;; orphan slot: no fn-slot junction
            _orphan-slot (setup/create-slot! storage "unused" :int)
            ;; orphan anon: _anon- name, nothing references it
            _orphan-anon (sp/create-entity storage :fn
                                           {:id (random-uuid)
                                            :name "_anon-deadbeef"
                                            :parent-ids []})
            rep (integrity/report storage)]
        (is (pos? (get-in rep [:summary :orphan-slots])))
        (is (= 1 (get-in rep [:summary :orphan-anons])))
        (is (zero? (get-in rep [:summary :stale-identities]))))
      (finally (sp/close storage)))))
