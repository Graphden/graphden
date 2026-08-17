(ns ^:serial graphden.types.narrowing-unit-test
  "Unit tests for `types.check.narrowing`'s two passes, driven with a
   stubbed registry (`with-redefs` over `rich-type-of` /
   `root-base-fn-name`) so no DB / package corpus is needed.

   Coverage the audit found missing:
   - Phase α' (`build-caller-narrowings`) had ZERO direct unit tests —
     only end-to-end through packages/sync.
   - The `:if`/`:cond` branch-override builders overwrote instead of
     unioning when the same fn is referenced from more than one branch
     (then=else typed the shared fn as if the guarded target were
     `:null`)."
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.executor.registry.core :as reg]
    [graphden.types.check.narrowing :as nar]))


(defn- stub-registry
  "Run `f` with `rich-type-of` answering from `info-map` and
   `root-base-fn-name` answering from `root-map` (identity fallback)."
  [info-map root-map f]
  (with-redefs [reg/rich-type-of      (fn [n] (get info-map n))
                reg/root-base-fn-name (fn [n] (get root-map n n))]
    (f)))


;; =============================================================================
;; Phase #170 — branch overrides
;; =============================================================================

(def ^:private some?-pred-registry
  ;; `:my-pred` is a `:some?` guard on `:tgt`, whose static return is
  ;; nullable text.
  {:my-pred {:resolved-bindings {:value {:ref :tgt}} :return :bool}
   :tgt     {:return [:union :null :text]}})


(deftest if-branch-distinct-refs-narrow-independently
  (stub-registry
    some?-pred-registry {:my-pred :some?}
    (fn []
      (is (= {:then-fn {:tgt :text}
              :else-fn {:tgt :null}}
             (#'nar/if-branch-overrides
              {:name :f :parent :if
               :args {:test :my-pred :then :then-fn :else :else-fn}}))
          "taken branch strips null, not-taken branch pins :null"))))


(deftest if-branch-same-ref-unions-not-overwrites
  ;; Regression: with `:then` and `:else` referencing the SAME fn, the
  ;; old assoc kept only the else entry — the shared fn was checked as
  ;; if `:tgt` returned `:null`, though the then path sees `:text`.
  ;; The union of both paths is the target's full static type.
  (stub-registry
    some?-pred-registry {:my-pred :some?}
    (fn []
      (is (= {:same-fn {:tgt [:union :null :text]}}
             (#'nar/if-branch-overrides
              {:name :f :parent :if
               :args {:test :my-pred :then :same-fn :else :same-fn}}))
          "narrowings from both branches union back to the static type"))))


(deftest cond-shared-result-ref-unions-across-clauses
  ;; Two clauses returning the SAME result fn: clause 1 under the
  ;; taken `:some?` guard (→ :text), clause 2 (else-sentinel) under
  ;; the accumulated not-taken guard (→ :null). The shared result fn
  ;; must see the union, not the last clause's view.
  (stub-registry
    some?-pred-registry {:my-pred :some?}
    (fn []
      (is (= {:shared {:tgt [:union :null :text]}}
             (#'nar/cond-branch-overrides
              {:name :f :parent :cond
               :args {:clauses [:my-pred :shared true :shared]}}))))))


;; =============================================================================
;; Phase #170 — `:is-a?` narrowing is MARKER-PRESERVING
;; =============================================================================
;; Regression: the `:is-a?` arm returned the tag's structural type
;; VERBATIM, discarding any `[:secret …]`/marker wrapper on the
;; target's static return — so a secret-returning ref narrowed under
;; `(:is-a? … :map)` lost its taint and a plain sink accepted it.

(deftest is-a-narrowing-preserves-secret-marker
  ;; Marker peeled, structural applied to the inner, marker re-wrapped.
  (is (= [:secret :text]
         (#'nar/narrowed-type-for-predicate :is-a? :taken [:secret :text] :text))
      ":is-a? :text on [:secret :text] keeps the secret")
  (is (= [:secret [:map :any :any]]
         (#'nar/narrowed-type-for-predicate :is-a? :taken [:secret :jsonb] :map))
      ":is-a? :map on [:secret :jsonb] → secret-wrapped structural map")
  ;; No marker present → plain structural projection, as before.
  (is (= [:map :any :any]
         (#'nar/narrowed-type-for-predicate :is-a? :taken :jsonb :map))
      "unmarked target still projects to the bare structural type")
  ;; Unknown tag → decline to narrow (leave target as-is).
  (is (= [:secret :text]
         (#'nar/narrowed-type-for-predicate :is-a? :taken [:secret :text] :nope))
      "unknown tag leaves the marked target untouched")
  ;; Sanity: the `:some?` arm was already marker-preserving (strip-null).
  (is (= [:secret [:union :null :text]]
         (#'nar/narrowed-type-for-predicate :some? :taken [:secret [:union :null :text]] nil))
      ":some? preserves the marker (strip-null baseline)"))


(deftest if-branch-is-a-narrowing-keeps-secret-end-to-end
  ;; `:my-isa` is an `(:is-a? :sec :map)` guard; `:sec` returns
  ;; `[:secret :jsonb]`. The taken branch must record the map-narrowed
  ;; type STILL wrapped in `:secret`; the not-taken branch sees the
  ;; full (still-secret) static type.
  (stub-registry
    {:my-isa {:resolved-bindings {:value {:ref :sec} :type {:value :map}}
              :return :bool}
     :sec    {:return [:secret :jsonb]}}
    {:my-isa :is-a?}
    (fn []
      (is (= {:then-fn {:sec [:secret [:map :any :any]]}
              :else-fn {:sec [:secret :jsonb]}}
             (#'nar/if-branch-overrides
              {:name :f :parent :if
               :args {:test :my-isa :then :then-fn :else :else-fn}}))
          "taken branch keeps secret+structural; not-taken keeps full secret"))))


;; =============================================================================
;; Phase α' — build-caller-narrowings
;; =============================================================================

(deftest caller-narrowing-reaches-rename-host
  ;; F binds its own (non-parent-slot) arg `:val` via ref `:producer`
  ;; and refs `:H`; H introduces `{:as :val}` — the rename-host. H
  ;; must see :val narrowed to producer's return.
  (stub-registry
    {:p        {:args {}}
     :producer {:return [:union :null :text]}}
    {}
    (fn []
      (is (= {:H {:val [:union :null :text]}}
             (nar/build-caller-narrowings
               [{:name :F :parent :p
                 :args {:val :producer :body :H}}
                {:name :producer :parent :p :args {}}
                {:name :H :parent :p :args {:val {:as :val}}}]))))))


(deftest caller-narrowing-stops-at-rebinding-callee
  ;; A callee that REBINDS the arg-name with a real value stops the
  ;; propagation branch — its subtree supplies its own :val.
  (stub-registry
    {:p        {:args {}}
     :producer {:return :text}}
    {}
    (fn []
      (is (= {}
             (nar/build-caller-narrowings
               [{:name :F :parent :p
                 :args {:val :producer :body :Stop}}
                {:name :producer :parent :p :args {}}
                {:name :Stop :parent :p :args {:val {:value 42} :next :H}}
                {:name :H :parent :p :args {:val {:as :val}}}]))
          "the rebind at :Stop shields :H from F's narrowing"))))


(deftest caller-narrowing-excludes-all-mi-parents-slots
  ;; Regression: the pass used only the FIRST parent's slots to
  ;; exclude parent-contract bindings. A binding to a SECONDARY MI
  ;; parent's slot is contract fulfilment — it must NOT seed
  ;; rename-host propagation just because a downstream fn-def happens
  ;; to introduce a same-named `{:as}` rename.
  (stub-registry
    {:p1       {:args {}}
     :p2       {:args {:x :text}}
     :producer {:return :text}}
    {}
    (fn []
      (is (= {}
             (nar/build-caller-narrowings
               [{:name :F :parents [:p1 :p2]
                 :args {:x :producer :body :H}}
                {:name :producer :parent :p1 :args {}}
                {:name :H :parent :p1 :args {:x {:as :x}}}]))
          "secondary-parent slot :x is excluded from narrowing seeds"))))


(deftest caller-narrowing-competing-callers-union
  ;; Two callers narrow the same rename-host arg with different
  ;; returns — the recorded narrowing is their union.
  (stub-registry
    {:p         {:args {}}
     :producer1 {:return :int}
     :producer2 {:return :text}}
    {}
    (fn []
      (is (= {:H {:val [:union :int :text]}}
             (nar/build-caller-narrowings
               [{:name :F1 :parent :p :args {:val :producer1 :body :H}}
                {:name :F2 :parent :p :args {:val :producer2 :body :H}}
                {:name :producer1 :parent :p :args {}}
                {:name :producer2 :parent :p :args {}}
                {:name :H :parent :p :args {:val {:as :val}}}]))))))
