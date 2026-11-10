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


(deftest canonical-digests-remain-compatible-with-existing-certificates
  ;; Fixed outputs captured from the previous implementation. Container tags,
  ;; mixed keys and nested map/set ordering are part of the digest contract.
  (doseq [[input expected]
          '[[nil "5da3a4c7f117944275b4c8629c4916403625d5a4a6573a01ecb03f0e9d2edbe6"]
            [{} "d676b92f638f6df3813bda24a2b2fca75cf90a942a2ada80cd81e398ee7b0130"]
            [#{} "02f05379ae828b4b8f7a4819bae5fb5b039c6cc324d071d583115a5a2e2af5b3"]
            [[] "97ea6943cabf2884856576921b5d86c92d9badf9ed0f42cd3142856818f33313"]
            [() "d1a2802c50192ac0b7831217bbf27b00f31bbe90bf229ed4900f967144274a57"]
            [{:b #{1 3 2}, :a [nil false "x"]} "c5cbfa748db1e27e88e664fede10567a65726bf786acc01c706aea81a820fc12"]
            [{:nested {:text "line\nbreak", :values [#{:b :a} {:z false, :a nil}]}, :sequence (1 :two "three"), :vector [1 :two "three"]} "fe1e3c747f3e7bc9e3afcc02d783d9c68fb363ab23f77ecf51d81e92da200272"]
            [{"key" 1, :key 2, key 3, 42 :number, #uuid "00000000-0000-0000-0000-000000000123" {:deep #{[:y 2] [:x 1]}}} "5f4834b9acc8163a03b60255bbbffac9d24d199e6e1d09fd8b78053fcc1da857"]
            [#{{:a 2, :b [3 4]} [#{1 2} {:x "x"}] {:b [2 3], :a 1}} "3eef98588b098a73fad3ad227f611681e0e447d5cb25d5ea9d7de6df6cd35c04"]]]
    (is (= expected (provenance/fingerprint-data input)))))


(deftest canonical-order-does-not-depend-on-map-set-iteration-or-print-bindings
  (let [input (sorted-map-by #(compare %2 %1)
                             :a [nil false "x"] :b (sorted-set-by > 1 2 3))
        expected "c5cbfa748db1e27e88e664fede10567a65726bf786acc01c706aea81a820fc12"]
    (is (= expected (provenance/fingerprint-data input)))
    (binding [*print-length* 1 *print-level* 1 *print-meta* true
              *print-readably* false *print-dup* true *print-namespace-maps* true]
      (is (= expected (provenance/fingerprint-data input))))))
