(ns graphden.executor.registry.core-test
  "Tests for `graphden.executor.registry.core` — impl-hash computation,
   the in-memory rich-types registry, fn-def validation, and the
   synthesised type-row impls.

   Most of the namespace is pure; `sync-*` need a storage, so the
   shared container fixture is present for those few tests."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.registry.core :as reg]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]))


(use-fixtures :once (setup/create-container-fixture))


;; ============================================================================
;; compute-impl-hash
;; ============================================================================

(deftest compute-impl-hash-test
  (testing "same canonical form → same hash, regardless of map-key order"
    (is (= (reg/compute-impl-hash {:args {:a :int :b :int}
                                   :return-type :int
                                   :impl-source ['(+ a b)]})
           (reg/compute-impl-hash {:args {:b :int :a :int}
                                   :return-type :int
                                   :impl-source ['(+ a b)]}))))

  (testing "a changed return-type / body changes the hash"
    (let [base {:args {:a :int} :return-type :int :impl-source ['(inc a)]}]
      (is (not= (reg/compute-impl-hash base)
                (reg/compute-impl-hash (assoc base :return-type :text))))
      (is (not= (reg/compute-impl-hash base)
                (reg/compute-impl-hash (assoc base :impl-source ['(dec a)])))))))


;; ============================================================================
;; fn-uuid
;; ============================================================================

(deftest fn-uuid-test
  (testing "deterministic per name, distinct across names"
    (is (uuid? (reg/fn-uuid :some-fn)))
    (is (= (reg/fn-uuid :some-fn) (reg/fn-uuid :some-fn)))
    (is (not= (reg/fn-uuid :fn-a) (reg/fn-uuid :fn-b)))))


;; ============================================================================
;; rich-types registry
;; ============================================================================

(deftest record-rich-types-test
  (testing "args + return are snapshotted; rich-type-of reads them back"
    (reg/record-rich-types! :rtc-plain {:args {:a :int} :return-type :int})
    (let [entry (reg/rich-type-of :rtc-plain)]
      (is (= :int (:return entry)))
      (is (= {:a :int} (:args entry)))
      ;; `:effects` is ALWAYS recorded (even empty) so downstream
      ;; consumers stop having to write `(or (:effects info) #{})`
      ;; just to recover the pure case. compute-effects is total.
      (is (= #{} (:effects entry)) "pure fns carry an explicit empty set"))
    (is (= :int (reg/rich-type-of :rtc-plain :a))))

  (testing "an :effects set is recorded as a set of category tags"
    (reg/record-rich-types! :rtc-eff {:args {} :return-type :int :effects [:db :io]})
    (is (= #{:db :io} (:effects (reg/rich-type-of :rtc-eff)))))

  (testing "legacy :effectful? boolean is ignored (the generic :effect tag was retired)"
    (reg/record-rich-types! :rtc-legacy {:args {} :return-type :int :effectful? true})
    (is (= #{} (:effects (reg/rich-type-of :rtc-legacy)))
        "Pure rich-type entry — :effectful? doesn't add an effect tag; the empty set is the computed-pure marker."))

  (testing "rich-types-snapshot includes every recorded entry"
    (is (contains? (reg/rich-types-snapshot) :rtc-plain))))


(deftest record-rich-types-raw-test
  (testing "a precomputed map with effects is stashed verbatim"
    (let [m {:return [:list :int] :args {:xs [:list :int]} :effects #{:db}}]
      (reg/record-rich-types-raw! :rtc-raw m)
      (is (= m (reg/rich-type-of :rtc-raw)))))
  (testing "a precomputed map WITHOUT :effects defaults to #{} (pure) on read"
    ;; Mirrors `record-rich-types!`'s P8 invariant — `:effects` is
    ;; always present in registry entries so downstream consumers
    ;; don't have to write `(or (:effects info) #{})` to recover the
    ;; pure case.
    (reg/record-rich-types-raw! :rtc-raw-no-eff
                                {:return :int :args {}})
    (is (= #{} (:effects (reg/rich-type-of :rtc-raw-no-eff)))
        "missing :effects defaults to the explicit empty set")))


(deftest effectful-rich-type-test
  (testing "true iff the entry carries any effect tag"
    (is (true? (reg/effectful-rich-type? {:effects #{:db}})))
    (is (false? (reg/effectful-rich-type? {:effects #{}})))
    (is (false? (reg/effectful-rich-type? {})))))


;; ============================================================================
;; validate-fn-def! / validate-all-defs!
;; ============================================================================

(deftest validate-fn-def-test
  (testing "a well-formed base-fn def validates without throwing"
    (is (nil? (reg/validate-fn-def! :vfd-ok {:return-type :int :args {:a :int}}))))

  (testing "a type-row def needs no :return-type"
    (is (nil? (reg/validate-fn-def! :vfd-rec {:type {:a :int}}))))

  (testing "non-keyword fn-name → :invalid-fn-def"
    (let [ex (try (reg/validate-fn-def! "not-kw" {:return-type :int})
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (= :invalid-fn-def (:type (ex-data ex))))))

  (testing "a base-fn def with no :return-type → :invalid-fn-def"
    (let [ex (try (reg/validate-fn-def! :vfd-noret {:args {:a :int}})
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (= :invalid-fn-def (:type (ex-data ex))))))

  (testing "an unknown return-type → :invalid-return-type"
    (let [ex (try (reg/validate-fn-def! :vfd-badret {:return-type [:not :a :type]})
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (= :invalid-return-type (:type (ex-data ex))))))

  (testing "a refinement whose constraint op is illegal on the base → rejected"
    (let [ex (try (reg/validate-fn-def! :vfd-badref
                                        {:refine {:base :text :constraint [:>= 0]}})
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (= :invalid-refinement-constraint (:type (ex-data ex)))))))


(deftest validate-all-defs-test
  (testing "all valid → nil; one invalid → throws"
    (is (nil? (reg/validate-all-defs! {:vad-a {:return-type :int}
                                       :vad-b {:return-type :text}})))
    (is (thrown? clojure.lang.ExceptionInfo
          (reg/validate-all-defs! {:vad-a {:return-type :int}
                                   :vad-bad {:args {:a :int}}})))))


;; ============================================================================
;; Synthesised type-row impls (private — exercised via the var)
;; ============================================================================

(deftest synthesised-impls-test
  (let [record-type-impl     @#'reg/record-type-impl
        refinement-type-impl @#'reg/refinement-type-impl
        synthesised-impl-for @#'reg/synthesised-impl-for]

    (testing "record-type-impl forces delayed fields and returns the map"
      (is (= {:a 1 :b 2}
             (record-type-impl {:a (delay 1) :b 2} nil))))

    (testing "refinement-type-impl passes a satisfying value, throws on violation"
      (let [impl (refinement-type-impl [:> 0])]
        (is (= 5 (impl {:value (delay 5)} nil)))
        (let [ex (try (impl {:value (delay -1)} nil)
                      (catch clojure.lang.ExceptionInfo e e))]
          (is (= :refinement/violated (:type (ex-data ex)))))))

    (testing "synthesised-impl-for dispatches on the type-row marker"
      (is (= record-type-impl (synthesised-impl-for {:type {:a :int}})))
      (is (fn? (synthesised-impl-for {:refine {:constraint [:> 0]}})))
      (is (fn? (synthesised-impl-for {:list :int})))
      (let [user-impl (fn [_ _] 42)]
        (is (= user-impl (synthesised-impl-for {:impl user-impl})))))))


(deftest register-base-fns-test
  (testing "registering a mix of base-fns and type-rows does not throw"
    (is (nil? (reg/register-base-fns!
                {:rbf-base {:impl (fn [_ _] 1) :return-type :int}
                 :rbf-record {:type {:a :int}}})))))


;; ============================================================================
;; Storage sync
;; ============================================================================

(deftest sync-primitives-test
  (testing "sync-primitives! seeds the primitive fn-rows, idempotently"
    (let [storage (setup/create-test-storage)]
      (try
        (reg/sync-primitives! storage)
        (let [names (set (keep :name (sp/query-entities storage :fn {})))]
          (is (contains? names "int"))
          (is (contains? names "text")))
        ;; second call must not throw — returns the primitive name→id map
        (is (map? (reg/sync-primitives! storage)))
        (finally (sp/close storage))))))


(deftest sync-defs-to-storage-test
  (testing "syncing a base-fn def writes a fn-row and returns the name→id map"
    (let [storage (setup/create-test-storage)]
      (try
        (let [name->id (reg/sync-defs-to-storage!
                         storage
                         {:reg-sync-fn {:return-type :int
                                        :args {:a {:type :int}}
                                        :impl (fn [_ _] 1)
                                        :impl-source ['(quote 1)]}})]
          (is (contains? name->id :reg-sync-fn))
          (is (seq (sp/query-entities storage :fn {:name "reg-sync-fn"}))))
        (finally (sp/close storage))))))
