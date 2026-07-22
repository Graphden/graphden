(ns graphden.packages.web.crud-tighten-test
  "Tests for the `tighten-effects-impl!` fn that powers
   `POST /api/bindings/:id/tighten-fn-effects`. The tightening logic
   now lives in `graphden.crud.entities` (extracted from the
   `web/crud` package impls), so the test calls it directly and
   asserts on its `{:status :reason :result}` map without bringing
   up the full HTTP stack.

   PARALLELISM: each test calls `clear-aliases!` +
   `register-type-alias!`. `:kaocha.plugin/parallel` binds a fresh
   `graphden.types.core/*type-aliases-override*` atom per NS thread,
   so the alias mutations stay thread-local and don't race with
   other parallel NSs. `with-isolated-rich-types` plus per-NS PG
   database (via `shared-container-fixture`) cover the remaining
   shared-state surfaces, so this ns runs safely under
   `:kaocha/parallelism > 1`."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.crud.entities :as entities]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.core :as registry]
    [graphden.executor.test-setup :as setup]
    [graphden.packages.records :as records]
    [graphden.storage.protocol.core :as sp]))


(use-fixtures :once
  (setup/create-container-fixture)
  ;; `record-rich-types(-raw)!` writes by this ns leak into sibling
  ;; integration tests otherwise — see check-test for the same fix.
  exec/with-isolated-rich-types)


;; ============================================================================
;; TIGHTEN LOGIC UNDER TEST
;; ============================================================================

(def ^:private tighten-effects-impl! entities/tighten-effects-impl!)


(def ^:private tighten-fn-type-impl! entities/tighten-fn-type-impl!)


;; ============================================================================
;; FIXTURE BUILDERS
;; ============================================================================
;;
;; Each test sets up a small scenario:
;;
;;   anon fn-type row     ─[type-fn-id]─►  slot ───[slot-id]───┐
;;   (constraint=[:fn …])                                       │
;;                                                              ▼
;;   handler fn-row    ◄──[ref-fn-id]── binding ◄──[fn-id]── host fn-row
;;   (rich-types: effects)
;;
;; `tighten-effects-impl!` operates on the binding row.

(defn- make-fn-type-row!
  "Storage helper: create an anonymous fn-row whose `:constraint`
   is a structural fn-type. Returns the row's id."
  [storage fn-args ret eff-set]
  (let [shape (cond-> [:fn fn-args ret] eff-set (conj eff-set))
        hash-hex (records/digest-hex "SHA-1" (pr-str shape))
        id (records/anonymous-fn-id hash-hex)]
    (sp/create-entity storage :fn
                      {:id id :name nil :namespace-id nil
                       :parent-ids []
                       :base-fn-id nil :element-fn-id nil
                       :return-type-fn-id nil
                       :anonymous-hash hash-hex
                       :constraint shape})
    id))


(defn- setup-fn-typed-binding!
  "Build the minimum scenario for testing tighten:

   - `host-name` fn (composed; no parents needed for storage layer
      but we add one to keep `reconstruct-fn-def` happy in the
      post-write check)
   - `handler-name` ref target with effects `ref-effects`
   - slot typed [:fn args ret] (3-arity, any effects)
   - fn-slot junction host → slot
   - binding (host, slot) → ref handler

   Returns `{:host-id :handler-id :slot-id :binding-id :type-fn-id}`."
  [storage host-name handler-name ref-effects]
  (let [;; Anon fn-type row: [:fn {} :null]
        type-fn-id (make-fn-type-row! storage {} :null nil)
        ;; Need a non-empty parent set or `reconstruct-fn-def`
        ;; short-circuits and the post-check is a no-op.
        parent (setup/create-base-fn! storage (str "parent-of-" host-name))
        handler (sp/create-entity storage :fn
                                  {:name handler-name
                                   :parent-ids nil})
        host (sp/create-entity storage :fn
                               {:name host-name
                                :parent-ids [(:id parent)]})
        slot (sp/create-entity storage :slot
                               {:name (str host-name "-slot")
                                :type-fn-id type-fn-id})
        _ (sp/create-entity storage :fn-slot
                            {:fn-id (:id host)
                             :slot-id (:id slot)
                             :position 0})
        ;; Skip :override-kind — the test storage's codec roundtrip
        ;; on enum columns would trip update-entity's read-then-write
        ;; cycle (see `feedback_codec_field_specs`). The tighten path
        ;; under test doesn't depend on the override-kind value.
        the-binding (sp/create-entity storage :binding
                                      {:fn-id (:id host)
                                       :slot-id (:id slot)
                                       :ref-fn-id (:id handler)})]
    ;; Record rich-types so the bound-callable effect check can read
    ;; the handler's :effects. Without this, ref-effects defaults to
    ;; #{} and any narrowing is allowed (silent gap). Keyed by the
    ;; handler ROW's id (3-arity) — the tighten path reads the registry
    ;; by identity, and this row's id is random, not name-derived.
    (registry/record-rich-types-raw!
      (:id handler)
      (keyword handler-name)
      {:return :null :args {} :effects ref-effects})
    {:host-id (:id host)
     :handler-id (:id handler)
     :slot-id (:id slot)
     :binding-id (:id the-binding)
     :type-fn-id type-fn-id}))


;; ============================================================================
;; TESTS
;; ============================================================================

(deftest tighten-fn-effects-happy-path
  (testing "3-arity → 4-arity with handler's exact effect set succeeds"
    (let [storage (setup/create-test-storage)]
      (try
        (let [{:keys [binding-id]}
              (setup-fn-typed-binding! storage "host-1" "handler-1" #{:io :db})
              result (tighten-effects-impl! storage binding-id ["io" "db"])]
          (is (= 200 (:status result)))
          (is (= [:fn {} :null #{:io :db}]
                 (get-in result [:result :constraint])))
          ;; Binding row now has :type-override-fn-id pointing at the
          ;; new anon row; storage upsert dedupes on subsequent calls.
          (let [b (sp/read-entity storage :binding binding-id)]
            (is (= (get-in result [:result :type-override-fn-id])
                   (:type-override-fn-id b)))))
        (finally (sp/close storage))))))


(deftest tighten-rejects-when-handler-effects-escape
  (testing "Bound handler does {:io :db}; tightening to {:network} only is rejected because :io / :db would escape the new ceiling"
    (let [storage (setup/create-test-storage)]
      (try
        (let [{:keys [binding-id]}
              (setup-fn-typed-binding! storage "host-2" "handler-2" #{:io :db})
              result (tighten-effects-impl! storage binding-id ["network"])]
          (is (= 400 (:status result)))
          (is (re-find #"produces effects" (:reason result)))
          ;; No write happened — type-override-fn-id stays nil.
          (let [b (sp/read-entity storage :binding binding-id)]
            (is (nil? (:type-override-fn-id b)))))
        (finally (sp/close storage))))))


(deftest tighten-rejects-non-narrowing
  (testing "Already at #{:io}; trying to widen to #{:io :db} is rejected (#{:io :db} ⊄ #{:io} as constraint)"
    (let [storage (setup/create-test-storage)]
      (try
        (let [{:keys [binding-id]}
              (setup-fn-typed-binding! storage "host-3" "handler-3" #{:io})
              ;; First narrow to #{:io} — should succeed.
              first-result (tighten-effects-impl! storage binding-id ["io"])
              _ (is (= 200 (:status first-result)))
              ;; Now try to widen to #{:io :db} — caller's intent is to
              ;; "include :db too" but that's a WIDENING relative to the
              ;; current 4-arity constraint. The narrowing-only contract
              ;; rejects.
              widen-result (tighten-effects-impl! storage binding-id ["io" "db"])]
          (is (= 400 (:status widen-result)))
          (is (re-find #"narrowing" (:reason widen-result))))
        (finally (sp/close storage))))))


(deftest tighten-rejects-non-fn-slot
  (testing "Slot whose type-fn-id is a primitive :int (not fn-type) — endpoint refuses to tighten"
    (let [storage (setup/create-test-storage)]
      (try
        (let [base (setup/create-base-fn! storage "int-host-base")
              host (sp/create-entity storage :fn
                                     {:name "int-host"
                                      :parent-ids [(:id base)]})
              slot (sp/create-entity storage :slot
                                     {:name "n"
                                      :type-fn-id (records/primitive-fn-id :int)})
              _ (sp/create-entity storage :fn-slot
                                  {:fn-id (:id host) :slot-id (:id slot) :position 0})
              the-binding (sp/create-entity storage :binding
                                            {:fn-id (:id host)
                                             :slot-id (:id slot)
                                             :value 42})
              result (tighten-effects-impl! storage (:id the-binding) ["io"])]
          (is (= 400 (:status result)))
          (is (re-find #"not an fn-type" (:reason result))))
        (finally (sp/close storage))))))


(deftest tighten-rejects-missing-binding
  (testing "Unknown binding-id → 404"
    (let [storage (setup/create-test-storage)]
      (try
        (let [result (tighten-effects-impl! storage (random-uuid) ["io"])]
          (is (= 404 (:status result))))
        (finally (sp/close storage))))))


(deftest tighten-narrow-ret-via-fn-type-impl
  (testing "Replacing ret with a more-specific named record narrows the slot. The new constraint must be ⊆ the current one — same args, narrower ret, no effect change."
    (let [storage (setup/create-test-storage)]
      (try
        ;; Set up two record-types: a wider one (the slot's current
        ;; ret) and a narrower one (a record extending it with an
        ;; extra field). Subtype rule: more fields ⊂ fewer fields.
        ;; Ring style: `{:status :int}` ⊆ `{:status :int}` only;
        ;; we need a STRICT subtype to narrow.
        (let [;; "wide" = {:status :int} — slot's current ret
              wide {:status :int}
              ;; "narrow" = {:status :int :body :text} — extra field
              narrow {:status :int :body :text}
              ;; Register both as named aliases so subtype? + the
              ;; impl's lookup by string name resolve them.
              _ ((requiring-resolve 'graphden.types.core/clear-aliases!))
              _ ((requiring-resolve 'graphden.types.core/register-type-alias!)
                 :wide-resp wide)
              _ ((requiring-resolve 'graphden.types.core/register-type-alias!)
                 :narrow-resp narrow)
              ;; Slot type: [:fn {:request :jsonb} :wide-resp]
              type-fn-id (make-fn-type-row! storage {:request :jsonb} :wide-resp nil)
              parent (setup/create-base-fn! storage "parent-of-narrow-host")
              host (sp/create-entity storage :fn
                                     {:name "narrow-host"
                                      :parent-ids [(:id parent)]})
              handler (sp/create-entity storage :fn
                                        {:name "narrow-handler"
                                         :parent-ids nil})
              slot (sp/create-entity storage :slot
                                     {:name "callback"
                                      :type-fn-id type-fn-id})
              _ (sp/create-entity storage :fn-slot
                                  {:fn-id (:id host) :slot-id (:id slot) :position 0})
              the-binding (sp/create-entity storage :binding
                                            {:fn-id (:id host)
                                             :slot-id (:id slot)
                                             :ref-fn-id (:id handler)})
              _ ((requiring-resolve 'graphden.executor.registry.core/record-rich-types-raw!)
                 :narrow-handler {:return :null :args {} :effects #{}})
              ;; Narrow ret to :narrow-resp
              result (tighten-fn-type-impl! storage (:id the-binding)
                                            {:ret "narrow-resp"})]
          (is (= 200 (:status result)))
          (let [c (get-in result [:result :constraint])]
            (is (= [:fn {:request :jsonb} :narrow-resp] c)
                "ret replaced with the narrower alias; args / 3-arity preserved")))
        (finally (sp/close storage))))))


(deftest tighten-rejects-widening-ret
  (testing "Widening ret (subtype reverses) is rejected"
    (let [storage (setup/create-test-storage)]
      (try
        (let [_ ((requiring-resolve 'graphden.types.core/clear-aliases!))
              _ ((requiring-resolve 'graphden.types.core/register-type-alias!)
                 :wide-resp {:status :int})
              _ ((requiring-resolve 'graphden.types.core/register-type-alias!)
                 :narrow-resp {:status :int :body :text})
              ;; Slot's ret = :narrow-resp; tighten with :wide-resp
              ;; would WIDEN (more fields → narrower per the model;
              ;; fewer fields → wider). Rejected.
              type-fn-id (make-fn-type-row! storage {} :narrow-resp nil)
              parent (setup/create-base-fn! storage "parent-of-widen-host")
              host (sp/create-entity storage :fn
                                     {:name "widen-host"
                                      :parent-ids [(:id parent)]})
              handler (sp/create-entity storage :fn
                                        {:name "widen-handler"
                                         :parent-ids nil})
              slot (sp/create-entity storage :slot
                                     {:name "callback"
                                      :type-fn-id type-fn-id})
              _ (sp/create-entity storage :fn-slot
                                  {:fn-id (:id host) :slot-id (:id slot) :position 0})
              the-binding (sp/create-entity storage :binding
                                            {:fn-id (:id host)
                                             :slot-id (:id slot)
                                             :ref-fn-id (:id handler)})
              _ ((requiring-resolve 'graphden.executor.registry.core/record-rich-types-raw!)
                 :widen-handler {:return :null :args {} :effects #{}})
              result (tighten-fn-type-impl! storage (:id the-binding)
                                            {:ret "wide-resp"})]
          (is (= 400 (:status result)))
          (is (re-find #"not a narrowing" (:reason result))))
        (finally (sp/close storage))))))


(deftest tighten-dedup-by-shape
  (testing "Tightening twice with the same effect set lands on the same anon fn-row id (deterministic via SHA-1 of constraint)"
    (let [storage (setup/create-test-storage)]
      (try
        (let [{:keys [binding-id]}
              (setup-fn-typed-binding! storage "host-dedup" "handler-dedup" #{:io})
              r1 (tighten-effects-impl! storage binding-id ["io"])
              ;; "Re-tighten" — current eff is #{:io}, requesting #{:io}
              ;; again is a no-op narrowing (still ⊆). Should succeed
              ;; AND land on the same id.
              r2 (tighten-effects-impl! storage binding-id ["io"])]
          (is (= 200 (:status r1)))
          (is (= 200 (:status r2)))
          (is (= (get-in r1 [:result :type-override-fn-id])
                 (get-in r2 [:result :type-override-fn-id]))))
        (finally (sp/close storage))))))
