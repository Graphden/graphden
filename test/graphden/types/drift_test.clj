(ns graphden.types.drift-test
  "Tests for return-type drift detection in `check-fn-def!`.

   Drift fires when a fn-def author writes `:return-type T` AND the
   rule-computed return is STRICTLY narrower (computed ⊊ declared).
   The drift info is surfaced in the registry under
   `:return-type-drift` and consumed by `/api/types/drift` + the
   `bb types-drift` task. Detection is ADVISORY — the fn-def still
   loads; the registry just records the marker."
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.core :as registry]
    [graphden.packages.loader :as loader]
    [graphden.types.check :as check]
    [graphden.types.core :as types-core]))


(defonce ^:private core-base-fns
  (:base-fn-defs (loader/load-packages ["core"])))


;; Drift tests register synthetic rich-types via check-fn-def!
;; (which calls record-rich-types-raw!). `with-isolated-rich-types`
;; keeps those entries from leaking into sibling integration
;; tests — see check-test for the same rationale.
(use-fixtures :once exec/with-isolated-rich-types)


(use-fixtures :each
  exec/with-clean-registry
  (fn [test-fn]
    ;; Bulk-replay every alias the `core` package declares so a
    ;; `core-base-fns` arg-spec referencing e.g. `[:list :path-segment]`
    ;; doesn't trip `validate-arg-type!` during the per-test reseed.
    ;; Same pattern as `check_test`'s :each fixture.
    (types-core/clear-aliases!)
    ((requiring-resolve 'graphden.system.core/register-type-aliases!)
     (:fn-defs (loader/load-packages ["core"])))
    (test-fn))
  (fn [t]
    (doseq [[fn-name fn-def] core-base-fns]
      (registry/record-rich-types! fn-name fn-def))
    (t)))


(deftest no-drift-when-no-declared-return
  ;; Author omits :return-type — no drift can fire (no author claim).
  (check/check-fn-def! {:name :no-decl
                        :parent :first
                        :args {:coll [1 2 3]}})
  (is (nil? (-> (registry/rich-type-of :no-decl) :return-type-drift))
      "No :return-type declared → no drift marker."))


(deftest no-drift-when-declared-matches-computed
  ;; Author declares the same type the rule computes. Same shape → no
  ;; drift. `:first` over `[:list :int]` returns `[:union :null :int]`
  ;; via first-return-rule; declaring the same is benign.
  (check/check-fn-def! {:name :decl-matches
                        :parent :first
                        :args {:coll [1 2 3]}
                        :return-type [:union :null :int]})
  (is (nil? (-> (registry/rich-type-of :decl-matches) :return-type-drift))
      "Same declared / computed → no drift."))


(deftest drift-fires-when-declaration-strictly-wider
  ;; Author declares `:jsonb`, rule computes a known record. computed
  ;; ⊊ declared, so drift fires. We use `:assoc` because its rule
  ;; constructs a record-type from literal-key bindings.
  (check/check-fn-def! {:name :drift-wide
                        :parent :assoc
                        :args {:map {} :key "name" :value "alice"}
                        :return-type :jsonb})
  (let [drift (-> (registry/rich-type-of :drift-wide) :return-type-drift)]
    (is (some? drift) "Drift marker present.")
    (is (= :jsonb (:declared drift))
        "Declared is :jsonb.")
    (is (= {:name :text} (:computed drift))
        "Computed is the assoc-narrowed record-type.")))


(deftest drift-does-not-fire-when-types-incomparable
  ;; If declaration enforce-check would have thrown — incomparable types
  ;; are rejected upstream. So drift only fires when the subtype
  ;; relation already held. We just confirm enforce-declared still
  ;; throws on a NON-subtype declaration (drift code path isn't even
  ;; reached).
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"return-type"
        (check/check-fn-def! {:name :decl-incomparable
                              :parent :first
                              :args {:coll [1 2 3]}
                              :return-type :text}))
      "Incomparable declaration is rejected; drift never gets to check."))


(deftest drift-roundtrips-through-registry-entry
  ;; A drift marker is preserved on the registry entry so downstream
  ;; consumers (`/api/types`, the editor) read it without re-running
  ;; check-fn-def!.
  (check/check-fn-def! {:name :rt-drift
                        :parent :assoc
                        :args {:map {} :key "x" :value 1}
                        :return-type :jsonb})
  (let [entry (registry/rich-type-of :rt-drift)]
    (is (contains? entry :return-type-drift)
        "Entry carries :return-type-drift.")
    (is (= :jsonb (-> entry :return-type-drift :declared)))
    (is (= {:x :int} (-> entry :return-type-drift :computed)))))
