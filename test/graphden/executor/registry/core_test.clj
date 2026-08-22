(ns graphden.executor.registry.core-test
  "Tests for `graphden.executor.registry.core` — the in-memory
   rich-types registry, fn-def validation, and the synthesised
   type-row impls.

   Most of the namespace is pure; `sync-*` need a storage, so the
   shared container fixture is present for those few tests."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.registry.core :as reg]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tc]))


(deftest rich-type-of-isolates-same-named-fns-per-org
  ;; §4 Risk-2 (rich-types): the bare-name global collapses two orgs' same-named
  ;; composed fn (last-write-wins); the per-org slice keeps each org's own, and
  ;; the global remains the fallback for the compile / public path.
  (binding [reg/*per-org-rich-override* (atom {})
            reg/*rich-types-override* (atom {})]
    (tc/with-org "A" (reg/record-rich-types-raw! "myfilter" {:return :int :args {}}))
    (tc/with-org "B" (reg/record-rich-types-raw! "myfilter" {:return :text :args {}}))
    (testing "each org reads its OWN signature, not the global last-write-wins"
      (is (= :int (:return (tc/with-org "A" (reg/rich-type-of "myfilter")))))
      (is (= :text (:return (tc/with-org "B" (reg/rich-type-of "myfilter"))))))
    (testing "the per-arg arity is org-aware too"
      (is (= :int (:return (tc/with-org "A" (reg/rich-type-of "myfilter"))))))
    (testing "public / compile falls through to the global (last write = B's)"
      (is (= :text (:return (tc/with-org tc/public-org (reg/rich-type-of "myfilter"))))))))


(deftest tenant-slice-name-index-is-dual-keyed
  ;; The per-org slice used to key `:by-name` with the BARE name only —
  ;; a tenant's QUALIFIED ref to its own fn missed the slice and read
  ;; the global's (possibly another signature). The slice now uses the
  ;; same `index-keys-for` dual keying as the global name index.
  (binding [reg/*per-org-rich-override* (atom {})
            reg/*rich-types-override* (atom {})]
    (tc/with-org "A"
                 (reg/record-rich-types-raw! "dup"
                                             {:return :int :args {}
                                              :namespace "org-a.tools"}))
    ;; global gets a DIFFERENT signature under the same names
    (reg/record-rich-types-raw! "dup"
                                {:return :text :args {}
                                 :namespace "org-a.tools"})
    (testing "tenant resolves its own entry by the QUALIFIED name too"
      (is (= :int (:return (tc/with-org "A"
                                        (reg/rich-type-of :org-a.tools/dup))))))
    (testing "and by the bare name (unchanged)"
      (is (= :int (:return (tc/with-org "A" (reg/rich-type-of "dup"))))))
    (testing "public path reads the global under both spellings"
      (is (= :text (:return (tc/with-org tc/public-org
                                         (reg/rich-type-of :org-a.tools/dup))))))))


(use-fixtures :once (setup/create-container-fixture))


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

  (testing "rich-types-snapshot includes every recorded entry"
    (is (contains? (reg/rich-types-snapshot) :rtc-plain))))


(deftest record-rich-types-preserves-computed-effects-test
  ;; Race fix (commit 7a48234e): `record-rich-types!`'s former
  ;; unconditional `assoc` would clobber computed effects that the
  ;; type-checker had previously written through
  ;; `record-rich-types-raw!`. Re-running the raw seed pass on a
  ;; composed fn-def whose own `:fns.edn` line declares no `:effects`
  ;; — typical for composed defs that inherit `:process` from a
  ;; parent — would erase the inherited set. `cron-schedule-fires-
  ;; and-halts-end-to-end-test` failed flakily because of this.
  ;;
  ;; After the fix: when the incoming fn-def declares empty effects
  ;; and the existing entry already carries non-empty `:effects`,
  ;; the existing set is preserved.
  (testing "first-time write installs whatever the fn-def declares"
    (reg/record-rich-types! :rtc-pres1 {:args {} :return-type :int})
    (is (= #{} (:effects (reg/rich-type-of :rtc-pres1)))))

  (testing "computed effects (raw write) are preserved by a follow-up raw fn-def write that declares none"
    ;; Simulate the parallel-bootstrap shape:
    ;;   1. raw write seeds with fn-def's own declared effects (empty here).
    ;;   2. type-checker computes the inherited set + writes via -raw!.
    ;;   3. ANOTHER bootstrap thread re-runs the raw seed.
    ;; Without the fix step 3 wipes step 2; with the fix it preserves it.
    (reg/record-rich-types! :rtc-pres2 {:args {} :return-type :int})
    (reg/record-rich-types-raw! :rtc-pres2
                                {:return :int :args {}
                                 :effects #{:process :network}})
    (is (= #{:process :network} (:effects (reg/rich-type-of :rtc-pres2))))
    ;; Re-run the raw seed — the fn-def still declares no effects.
    (reg/record-rich-types! :rtc-pres2 {:args {} :return-type :int})
    (is (= #{:process :network} (:effects (reg/rich-type-of :rtc-pres2)))
        "raw re-write with empty :effects must NOT clobber the type-checker's set"))

  (testing "non-empty effects in a later fn-def write DO replace existing"
    ;; The preservation rule only kicks in for the empty → non-empty
    ;; direction. If the fn-def itself declares effects, the new write
    ;; is authoritative (admins may legitimately add a new effect to a
    ;; base-fn definition; the registry must reflect it).
    (reg/record-rich-types-raw! :rtc-pres3
                                {:return :int :args {}
                                 :effects #{:process}})
    (reg/record-rich-types! :rtc-pres3
                            {:args {} :return-type :int :effects #{:db}})
    (is (= #{:db} (:effects (reg/rich-type-of :rtc-pres3)))
        "explicit effects in fn-def win over previously-stored effects")))


(deftest record-rich-types-raw-test
  (testing "a precomputed map with effects is stashed verbatim (plus the
            identity keys every entry now carries)"
    (let [m {:return [:list :int] :args {:xs [:list :int]} :effects #{:db}}]
      (reg/record-rich-types-raw! :rtc-raw m)
      (let [entry (reg/rich-type-of :rtc-raw)]
        (is (= m (dissoc entry :fn-id :name)))
        (is (= :rtc-raw (:name entry)))
        (is (uuid? (:fn-id entry))
            "entries are keyed by (and carry) the fn's identity")
        (is (identical? entry (reg/rich-type-of-id (:fn-id entry)))
            "name-path and id-path resolve the same entry"))))
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


(deftest register-base-fns-accepts-type-rows-test
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


(deftest sync-defs-to-storage-3-arity-test
  (testing "3-arity threads ns-id-map; extra-name->id defaults to {}"
    (let [storage (setup/create-test-storage)]
      (try
        (let [name->id (reg/sync-defs-to-storage!
                         storage
                         {:reg-sync-fn-3 {:return-type :int
                                          :args {:a {:type :int}}
                                          :impl (fn [_ _] 1)
                                          :impl-source ['(quote 1)]}}
                         ;; Empty ns-id-map — namespace-less fns still
                         ;; get registered via fn-uuid's deterministic id.
                         {})]
          (is (contains? name->id :reg-sync-fn-3)))
        (finally (sp/close storage))))))
