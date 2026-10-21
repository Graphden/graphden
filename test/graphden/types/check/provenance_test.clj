(ns graphden.types.check.provenance-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.types.check.provenance :as provenance]))


(deftest storage-input-witness-retains-and-binds-original-checker-evidence
  (let [authored {:name :secret-source :parent :const
                  :args {:value {:value "checked"}} :return-type [:secret :text]}
        signature {:return [:secret :text] :args {} :effects #{:db}}
        checked (provenance/stamp signature authored)
        witnessed (provenance/stamp-storage-input checked authored "exact-writer-source")]
    (testing "additional private evidence does not alter the signature or authored check"
      (is (= checked witnessed))
      (is (provenance/matches? witnessed authored))
      (is (provenance/matches-storage-input? witnessed "exact-writer-source"))
      (is (false? (provenance/matches-storage-input? witnessed "mutated-source")))
      (is (false? (provenance/matches-storage-input? witnessed nil))))
    (testing "missing or superseded authored evidence cannot certify source"
      (is (= signature (provenance/stamp-storage-input signature authored "unchecked")))
      (is (= checked (provenance/stamp-storage-input checked (assoc authored :return-type :text) "other")))
      (is (false? (provenance/matches-storage-input?
                    (provenance/stamp witnessed (assoc authored :return-type :text))
                    "exact-writer-source"))))
    (testing "authored certificate mismatch cannot reuse an old witness"
      (is (false? (provenance/matches-storage-input?
                    (vary-meta witnessed assoc :graphden.types.check.provenance/input "new-check")
                    "exact-writer-source"))))
    (testing "a map containing correct public digests is not an issued certificate"
      (let [forged (vary-meta checked assoc :graphden.types.check.provenance/storage-input
                              {:authored-input (provenance/fingerprint authored)
                               :source-fingerprint "forged-source"})]
        (is (false? (provenance/matches-storage-input? forged "forged-source")))))))


(deftest marker-witness-survives-enrichment-and-equivalent-projection-only
  (let [id (random-uuid)
        authored {:fn-id id :name :checked-view :parent :const
                  :args {:value {:value "checked"}}}
        issuer (provenance/capture-marker-stamp authored id)
        signature (provenance/stamp
                    {:fn-id id :args {:value {:nested [:list :text]}}
                     :return {:result :text}} authored)
        issued (issuer signature)
        flags (provenance/checked-marker-flags id issued)]
    (is (= flags (provenance/checked-marker-flags id (assoc issued :effects #{:db})))
        "unrelated registry enrichment retains the issued projection")
    (is (= flags (provenance/checked-marker-flags
                   id (assoc issued :args (into (sorted-map) (:args issued)))))
        "an equal rebuilt projection still uses the canonical digest proof")
    (is (nil? (provenance/checked-marker-flags
                id (assoc-in issued [:args :value :nested 1] :int)))
        "copied metadata cannot certify a changed nested argument")
    (is (nil? (provenance/checked-marker-flags
                id (assoc-in issued [:return :result] [:secret :text])))
        "copied metadata cannot certify a changed return type")
    (is (nil? (provenance/checked-marker-flags (random-uuid) issued))
        "the witness remains bound to the issued function")))
