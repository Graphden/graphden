(ns graphden.executor.compile.renames-test
  "Tests for `graphden.executor.compile.renames` — free-arg name
   translation and HOF lambda-param classification.

   `apply-renames` is pure; the rest walk the graph, so those tests
   build a real graph and `l/build-lookups` over it."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.compile.lookups :as l]
    [graphden.executor.compile.renames :as r]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]))


(use-fixtures :once (setup/create-container-fixture))


(defn- lookups-for
  [storage]
  (l/build-lookups
    {:fns        (sp/query-entities storage :fn {})
     :slots      (sp/query-entities storage :slot {})
     :fn-slots   (sp/query-entities storage :fn-slot {})
     :bindings   (sp/query-entities storage :binding {})
     :list-items (sp/query-entities storage :binding-list-item {})}))


;; ============================================================================
;; apply-renames — pure
;; ============================================================================

(deftest apply-renames-test
  (testing "each {R-name → F-name} entry exposes F's value under R's name"
    (is (= {:x 1 :b 2} (r/apply-renames {:a 1 :b 2} {:x :a}))))

  (testing "an entry whose F-name is absent is a no-op; extra keys pass through"
    (is (= {:b 2} (r/apply-renames {:b 2} {:x :a})))
    (is (= {:a 1} (r/apply-renames {:a 1} {}))))

  (testing "multiple renames are applied together"
    (is (= {:x 1 :y 2} (r/apply-renames {:a 1 :b 2} {:x :a :y :b})))))


;; ============================================================================
;; deep-free-ext-names
;; ============================================================================

(deftest deep-free-ext-names-test
  (testing "an unbound slot surfaces as a deep-free name; a bound one does not"
    (let [storage (setup/create-test-storage)]
      (try
        (let [base (setup/build-fn! storage
                                    {:name "dfn-base"
                                     :slots [{:name "a" :type :int}
                                             {:name "b" :type :int}]})
              fn1 (setup/build-fn! storage
                                   {:name "dfn-1"
                                    :parent base
                                    :bindings {"a" {:value 10}}})]
          ;; a is value-bound, b is free → only b surfaces.
          (is (= [:b] (r/deep-free-ext-names (-> fn1 :fn :id)
                                             (lookups-for storage)))))
        (finally (sp/close storage)))))

  (testing "deep-free walks across a non-HOF ref into the ref-target's free args"
    (let [storage (setup/create-test-storage)]
      (try
        (let [;; D exposes a free slot `inner`.
              base-d (setup/build-fn! storage
                                      {:name "dfn-base-d"
                                       :slots [{:name "inner" :type :int}]})
              d      (setup/build-fn! storage
                                      {:name "dfn-d" :parent base-d})
              ;; C binds its slot `s` to a ref of D.
              base-c (setup/build-fn! storage
                                      {:name "dfn-base-c"
                                       :slots [{:name "s" :type :int}]})
              c      (setup/build-fn! storage
                                      {:name "dfn-c"
                                       :parent base-c
                                       :bindings {"s" {:ref d}}})]
          ;; C's own slot s is ref-bound; D's `inner` bubbles up.
          (is (= [:inner] (r/deep-free-ext-names (-> c :fn :id)
                                                 (lookups-for storage)))))
        (finally (sp/close storage))))))


;; ============================================================================
;; hof-lambda-params
;; ============================================================================

(deftest hof-lambda-params-test
  (testing "a deep-free name nobody supplies is a lambda-param of the HOF target"
    (let [storage (setup/create-test-storage)]
      (try
        (let [;; R exposes free `x`.
              base-r (setup/build-fn! storage
                                      {:name "hlp-base-r"
                                       :slots [{:name "x" :type :int}]})
              r-fn   (setup/build-fn! storage
                                      {:name "hlp-r" :parent base-r})
              ;; F is built on an unrelated base — neither owns nor supplies `x`.
              base-f (setup/build-fn! storage
                                      {:name "hlp-base-f"
                                       :slots [{:name "y" :type :int}]})
              f-fn   (setup/build-fn! storage
                                      {:name "hlp-f" :parent base-f})]
          ;; Nothing in F's world supplies x → x is a per-call lambda-param.
          ;; Probe the alpha-equivalence resolver directly — this is the
          ;; helper `hof-lambda-params` delegates to for 1-arg structural
          ;; slots whose conventional name doesn't match R's free arg
          ;; (`:filter :pred :some?` case).
          (is (= [:x] (r/alpha-equiv-lambda-params (-> r-fn :fn :id)
                                                   (-> f-fn :fn :id)
                                                   (lookups-for storage)))))
        (finally (sp/close storage)))))

  (testing "a name a caller-relative supplies is captured, not a lambda-param"
    (let [storage (setup/create-test-storage)]
      (try
        (let [base (setup/build-fn! storage
                                    {:name "hlp2-base"
                                     :slots [{:name "x" :type :int}]})
              r-fn (setup/build-fn! storage
                                    {:name "hlp2-r" :parent base})
              f-fn (setup/build-fn! storage
                                    {:name "hlp2-f"
                                     :parent base
                                     :bindings {"x" {:value 99}}})]
          ;; f binds x → x flows in from the closure → no lambda-params.
          (is (= [] (r/alpha-equiv-lambda-params (-> r-fn :fn :id)
                                                 (-> f-fn :fn :id)
                                                 (lookups-for storage)))))
        (finally (sp/close storage))))))


;; ============================================================================
;; HOF :is-fn boundary — pins both `deep-free-ext-names` and
;; `find-slot-id-in-tree` :ref guard branches (line 55/96 in renames.clj).
;;
;; Without this test the walker only ever sees plain `:ref` bindings where
;; `:is-fn` is false, so the `(when-not (:is-fn bnd) …)` guard's truthy
;; arm is never hit and cloverage flags the line as half-covered.
;; ============================================================================

(deftest hof-ref-is-a-boundary-test
  (testing ":is-fn ref binding stops the walker from descending into the target"
    (let [storage (setup/create-test-storage)]
      (try
        (let [;; Inner fn `T` exposes a free slot `:inner-free`.
              base-t  (setup/create-base-fn! storage "bdy-base-t")
              s-free  (setup/create-slot! storage "inner-free" :int)
              _       (setup/attach-slot! storage (:id base-t) (:id s-free) 0)
              t-fn    (setup/create-composed-fn! storage "bdy-t" (:id base-t))
              ;; Outer fn `F` has a slot typed as the `:fn` primitive
              ;; (HOF marker) and binds it to a ref of T. The binding's
              ;; `:is-fn` flag becomes true → `deep-free-ext-names`'s
              ;; `:ref` branch hits the BOUNDARY arm and skips T's frees.
              base-f  (setup/create-base-fn! storage "bdy-base-f")
              s-callee (setup/create-slot! storage "callee" :fn)
              _       (setup/attach-slot! storage (:id base-f) (:id s-callee) 0)
              f-fn    (setup/create-composed-fn! storage "bdy-f" (:id base-f))
              _       (setup/bind-ref! storage (:id f-fn) (:id s-callee) (:id t-fn))]
          (testing "T's free args do NOT bubble up through the HOF boundary"
            (is (= [] (r/deep-free-ext-names (:id f-fn) (lookups-for storage)))
                "the :is-fn=true ref guard suppresses recursion into T"))
          (testing "find-slot-id-in-tree (via alpha-equiv resolver) honors the same guard"
            ;; alpha-equiv-lambda-params runs find-slot-id-in-tree
            ;; against every deep-free name of an outer HOF-target.
            ;; Nothing in F's tree supplies `inner-free`, so it
            ;; becomes a lambda-param of T when called from F.
            (is (= [:inner-free]
                   (r/alpha-equiv-lambda-params
                     (:id t-fn) (:id f-fn) (lookups-for storage))))))
        (finally (sp/close storage))))))


;; ============================================================================
;; Seq-binding deep-free emission — pins the `:seq` arm of
;; `deep-free-ext-names` (lines 57-73). The walker treats a list-item's
;; literal `{:as :name}` map as a positional rename that re-exposes
;; `:name` as a free arg of the binding's owner.
;; ============================================================================

(deftest deep-free-emits-positional-renames-from-seq-items-test
  (testing "seq-binding with `{:as :outer-name}` items exposes those names as free"
    (let [storage (setup/create-test-storage)]
      (try
        (let [;; F's `items` slot is a list — bind with :list-append true
              ;; so classify-slot lands on the :seq branch.
              base    (setup/create-base-fn! storage "seq-base")
              s-list  (setup/create-slot! storage "items" :int)
              _       (setup/attach-slot! storage (:id base) (:id s-list) 0)
              f-fn    (setup/create-composed-fn! storage "seq-f" (:id base))
              list-bn (sp/create-entity storage :binding
                                        {:fn-id (:id f-fn)
                                         :slot-id (:id s-list)
                                         :list-append true
                                         :override-kind :fixed})]
          ;; Two list items, both literal maps with `:as` → each re-
          ;; exposes its `:as` keyword as a deep-free name of F.
          (sp/create-entity storage :binding-list-item
                            {:binding-id (:id list-bn) :position 0
                             :value {:as "alpha"} :literal nil})
          (sp/create-entity storage :binding-list-item
                            {:binding-id (:id list-bn) :position 1
                             :value {:as "beta"}  :literal nil})
          ;; A third item with `:literal true` MUST be skipped — the
          ;; predicate is `(not (:literal item))`.
          (sp/create-entity storage :binding-list-item
                            {:binding-id (:id list-bn) :position 2
                             :value {:as "skip-me"} :literal true})
          (is (= [:alpha :beta]
                 (r/deep-free-ext-names (:id f-fn) (lookups-for storage)))
              "literal :as maps surface; :literal=true items are excluded"))
        (finally (sp/close storage))))))


;; ============================================================================
;; hof-lambda-params — structural-slot dispatch (0-arg / 2+-arg branches)
;;
;; The 1-arg + nil-structural paths are exercised by the tests above; the
;; remaining two arms of the `cond` need a slot whose `:type-fn-id` points
;; at a fn with a `[:fn {ARGS} ret]` constraint. We synthesise that fn
;; directly here rather than going through a real type-row builder.
;; ============================================================================

(defn- create-callable-type!
  "Insert a fn-row whose `:constraint` shape claims it's a callable
   with the given arg-spec map. Returns the new fn-id."
  [storage type-name args-spec]
  (:id (sp/create-entity storage :fn
                         {:name type-name
                          :parent-ids []
                          :impl-hash nil
                          :constraint [:fn args-spec :any]})))


(deftest hof-lambda-params-zero-arg-structural-slot-test
  ;; Slot typed `[:fn {} ret]` — variadic-ignore wrap; whatever R's
  ;; frees are, they're ALL captured at wrap time → 0 lambda-params.
  (let [storage (setup/create-test-storage)]
    (try
      (let [base-r (setup/build-fn! storage
                                    {:name "hlp0-base-r"
                                     :slots [{:name "x" :type :int}]})
            r-fn   (setup/build-fn! storage
                                    {:name "hlp0-r" :parent base-r})
            ;; Make a callable type fn with EMPTY args-spec.
            callable-fn-id (create-callable-type! storage "hlp0-callable" {})
            ;; F's slot is typed at that fn → structural args = #{}.
            base-f (setup/create-base-fn! storage "hlp0-base-f")
            s-cb   (setup/create-slot! storage "cb" callable-fn-id)
            _      (setup/attach-slot! storage (:id base-f) (:id s-cb) 0)
            f-fn   (setup/create-composed-fn! storage "hlp0-f" (:id base-f))
            b-row  (setup/bind-ref! storage (:id f-fn) (:id s-cb) (-> r-fn :fn :id))]
        (is (= [] (r/hof-lambda-params (-> r-fn :fn :id)
                                       (:id s-cb) b-row (:id f-fn)
                                       (lookups-for storage)))
            "0-arg structural slot → empty lambda-params (variadic-ignore wrap)"))
      (finally (sp/close storage)))))


(deftest hof-lambda-params-map-callable-structural-slot-test
  ;; Slot typed `[:fn {:request :any :next-handler :any} ret]` — map-callable
  ;; wrap; R's frees that match those structural names are lambda-params,
  ;; the rest are captured.
  (let [storage (setup/create-test-storage)]
    (try
      (let [base-r (setup/build-fn! storage
                                    {:name "hlp2-base-r"
                                     :slots [{:name "request" :type :int}
                                             {:name "next-handler" :type :int}
                                             {:name "other" :type :int}]})
            r-fn   (setup/build-fn! storage
                                    {:name "hlp2-r" :parent base-r})
            callable-fn-id (create-callable-type! storage "hlp2-callable"
                                                  {:request :any :next-handler :any})
            base-f (setup/create-base-fn! storage "hlp2-base-f")
            s-cb   (setup/create-slot! storage "cb" callable-fn-id)
            _      (setup/attach-slot! storage (:id base-f) (:id s-cb) 0)
            f-fn   (setup/create-composed-fn! storage "hlp2-f" (:id base-f))
            b-row  (setup/bind-ref! storage (:id f-fn) (:id s-cb) (-> r-fn :fn :id))
            out    (r/hof-lambda-params (-> r-fn :fn :id)
                                        (:id s-cb) b-row (:id f-fn)
                                        (lookups-for storage))]
        (is (= #{:request :next-handler} (set out))
            ":request and :next-handler match the slot's structural args → lambda-params")
        (is (not (contains? (set out) :other))
            ":other isn't in the structural args → captured, not lambda-param"))
      (finally (sp/close storage)))))


;; ============================================================================
;; build-ref-renames — translates F's rename-bindings into the map that
;; rewrites R's incoming free-arg names.
;; ============================================================================

(deftest build-ref-renames-empty-when-no-rename-overlap
  ;; R exposes :a but F has no rename binding for it → empty rename map.
  (let [storage (setup/create-test-storage)]
    (try
      (let [base-r (setup/build-fn! storage
                                    {:name "brr0-base-r"
                                     :slots [{:name "a" :type :int}]})
            r-fn   (setup/build-fn! storage
                                    {:name "brr0-r" :parent base-r})
            base-f (setup/create-base-fn! storage "brr0-base-f")
            f-fn   (setup/create-composed-fn! storage "brr0-f" (:id base-f))]
        (is (= {} (r/build-ref-renames (-> r-fn :fn :id) (:id f-fn)
                                       (lookups-for storage)))))
      (finally (sp/close storage)))))


(deftest build-ref-renames-translates-renamed-slot
  ;; F's slot `inner` is exposed under the external name `outer`
  ;; (via slot.source-slot-id → rename). R has a free arg :inner. The
  ;; rename map should produce {:inner :outer}.
  (let [storage (setup/create-test-storage)]
    (try
      (let [;; R has a free :inner.
            base-r (setup/build-fn! storage
                                    {:name "brr1-base-r"
                                     :slots [{:name "inner" :type :int}]})
            r-fn   (setup/build-fn! storage
                                    {:name "brr1-r" :parent base-r})
            ;; F is a fresh composed fn that owns a rename-slot:
            ;; a NEW slot named "outer" whose :source-slot-id points
            ;; at R-base's "inner" slot. That makes F's binding view
            ;; treat :outer as the renamed external of :inner.
            base-f (setup/build-fn! storage
                                    {:name "brr1-base-f"
                                     :slots [{:name "inner" :type :int}]})
            f-fn   (setup/create-composed-fn! storage "brr1-f" (:id (:fn base-f)))
            rename-slot (sp/create-entity storage :slot
                                          {:name "outer"
                                           :type-fn-id (-> base-f :slots (get "inner") :type-fn-id)
                                           :source-slot-id (-> base-f :slots (get "inner") :id)})
            _ (setup/attach-slot! storage (:id f-fn) (:id rename-slot) 0)
            out (r/build-ref-renames (-> r-fn :fn :id) (:id f-fn)
                                     (lookups-for storage))]
        (is (= {:inner :outer} out)
            ":inner (R's free) maps to :outer (F's rename ext-name)"))
      (finally (sp/close storage)))))


(deftest build-ref-renames-cross-tree-via-slot-id
  ;; Cross-tree rename: R's free arg `:inner` slot is OWNED BY R
  ;; itself (not a same-named slot on F's side). F owns a rename-slot
  ;; named `outer` whose `:source-slot-id` FK points DIRECTLY at R's
  ;; `inner` slot. Earlier build-ref-renames implementations missed
  ;; this because they walked F's bindings looking for `:free` rows
  ;; with renamed `:ext-name` matching R's free names — but F has no
  ;; such binding row when the source slot lives in R's tree (reached
  ;; via ref-target from R's perspective, not via inheritance from
  ;; F's). The two-pass build-ref-renames now resolves R's free
  ;; slot-id via `find-slot-id-in-tree` and asks
  ;; `l/rename-for-slot(F, that-slot-id)` for F's external name.
  (let [storage (setup/create-test-storage)]
    (try
      (let [base-r (setup/build-fn! storage
                                    {:name "brr-xtree-base-r"
                                     :slots [{:name "inner" :type :int}]})
            r-fn   (setup/build-fn! storage
                                    {:name "brr-xtree-r" :parent base-r})
            base-f (setup/create-base-fn! storage "brr-xtree-base-f")
            f-fn   (setup/create-composed-fn! storage "brr-xtree-f" (:id base-f))
            ;; F owns a rename-slot named "outer" with source pointing
            ;; at R-base's "inner" slot — the cross-tree case.
            rename-slot (sp/create-entity storage :slot
                                          {:name "outer"
                                           :type-fn-id (-> base-r :slots (get "inner") :type-fn-id)
                                           :source-slot-id (-> base-r :slots (get "inner") :id)})
            _ (setup/attach-slot! storage (:id f-fn) (:id rename-slot) 0)
            out (r/build-ref-renames (-> r-fn :fn :id) (:id f-fn)
                                     (lookups-for storage))]
        (is (= {:inner :outer} out)
            "cross-tree pass: F's `outer` slot with source-slot-id → R's `inner` produces {:inner :outer}"))
      (finally (sp/close storage)))))


;; ============================================================================
;; chain-source-slot-ids — pure (slot-id walker over a plain slot-map).
;; No storage needed; covers the head case, multi-hop chains, missing
;; intermediates, the cycle guard, and the depth cap at 16.
;; ============================================================================

(deftest chain-source-slot-ids-test
  (testing "slot with no source — returns [slot-id]"
    (let [s1 (random-uuid)
          slot-map {s1 {:id s1 :source-slot-id nil}}]
      (is (= [s1] (r/chain-source-slot-ids s1 slot-map)))))

  (testing "two-link chain — head then source"
    (let [src (random-uuid)
          mid (random-uuid)
          slot-map {mid {:id mid :source-slot-id src}
                    src {:id src :source-slot-id nil}}]
      (is (= [mid src] (r/chain-source-slot-ids mid slot-map)))))

  (testing "missing intermediate slot — chain still includes the start"
    ;; `slot-map` doesn't carry the next hop's row → `(get slot-map sid)`
    ;; returns nil → `:source-slot-id` of nil is nil → loop terminates.
    (let [a (random-uuid)
          dangling (random-uuid)
          slot-map {a {:id a :source-slot-id dangling}}]
      (is (= [a dangling] (r/chain-source-slot-ids a slot-map))
          "the unknown hop is included; the walker stops at its nil source")))

  (testing "cycle guard — a ↔ b loop terminates after one full pass"
    (let [a (random-uuid)
          b (random-uuid)
          slot-map {a {:id a :source-slot-id b}
                    b {:id b :source-slot-id a}}]
      (is (= [a b] (r/chain-source-slot-ids a slot-map))
          "seen-set rejects the second hit on :a")))

  (testing "depth cap at 16 — an unbounded chain truncates"
    (let [ids (vec (repeatedly 30 random-uuid))
          slot-map (into {} (map-indexed (fn [i id]
                                           [id {:id id
                                                :source-slot-id (get ids (inc i))}])
                                         ids))
          result (r/chain-source-slot-ids (first ids) slot-map)]
      (is (= 16 (count result)) "result bounded to 16 hops")
      (is (= (first ids) (first result)) "still starts at the head")))

  (testing "nil start — empty chain"
    (is (= [] (r/chain-source-slot-ids nil {})))))


;; ============================================================================
;; apply-rename-aliases — pure runtime; map + alias-vector in, map out.
;; ============================================================================

(deftest apply-rename-aliases-test
  (testing "empty aliases — input passes through untouched (the common case)"
    (is (= {:a 1 :b 2} (r/apply-rename-aliases {:a 1 :b 2} []))))

  (testing "rename-name present, chain-name absent — copies value into chain-name"
    (is (= {:item 10 :branch-row 10}
           (r/apply-rename-aliases {:item 10}
                                   [{:chain-name :branch-row :rename-name :item}]))))

  (testing "chain-name already supplied by caller — caller value wins, no overwrite"
    (is (= {:item 10 :branch-row 99}
           (r/apply-rename-aliases {:item 10 :branch-row 99}
                                   [{:chain-name :branch-row :rename-name :item}]))
        "explicit caller-supplied binding outranks the alias copy"))

  (testing "rename-name absent — alias is a no-op"
    (is (= {:other 5}
           (r/apply-rename-aliases {:other 5}
                                   [{:chain-name :chain :rename-name :rename}]))))

  (testing "multiple aliases each applied independently"
    (is (= {:item 1 :other 2 :branch-row 1 :coll 2}
           (r/apply-rename-aliases {:item 1 :other 2}
                                   [{:chain-name :branch-row :rename-name :item}
                                    {:chain-name :coll :rename-name :other}])))))


;; ============================================================================
;; compute-rename-aliases — picks up own rename-slots whose source-
;; slot-id points OUTSIDE the root slot set. Two coverage shapes:
;; (1) fn with NO own rename-slots → empty vec (early-exit branch);
;; (2) own rename-slot whose source IS a root slot → filtered out by
;;     the `:when (not (contains? root-ids src))` guard.
;; The "outside the root set" alias path is exercised through the
;; integration `:_list-branches-as-json-item` chain on the production
;; graph — synthesising it here would require building a multi-hop
;; source chain by hand, which the existing integration paths cover.
;; ============================================================================

(deftest compute-rename-aliases-empty-when-no-own-rename-slots
  (let [storage (setup/create-test-storage)]
    (try
      (let [base (setup/create-base-fn! storage "cra0-base")
            f-fn (setup/create-composed-fn! storage "cra0-f" (:id base))]
        (is (= [] (r/compute-rename-aliases (:id f-fn) (lookups-for storage)))
            "no own rename-slots → vec'd empty seq"))
      (finally (sp/close storage)))))


(deftest compute-rename-aliases-filters-source-pointing-at-own-root-slot
  ;; Guard `:when (not (contains? root-ids src))`: a rename whose
  ;; source is already a root slot of the SAME fn is not aliased —
  ;; the caller already supplies the value under the renamed name
  ;; and no chain-link copy is needed.
  (let [storage (setup/create-test-storage)]
    (try
      (let [base-f (setup/build-fn! storage
                                    {:name "cra1-base-f"
                                     :slots [{:name "root-slot" :type :int}]})
            f-fn (:fn base-f)
            root-slot-id (-> base-f :slots (get "root-slot") :id)
            ;; F owns a rename-slot pointing AT its own root-slot.
            rename-slot (sp/create-entity storage :slot
                                          {:name "renamed"
                                           :type-fn-id (-> base-f :slots
                                                           (get "root-slot")
                                                           :type-fn-id)
                                           :source-slot-id root-slot-id})
            _ (setup/attach-slot! storage (:id f-fn) (:id rename-slot) 1)]
        (is (= [] (r/compute-rename-aliases (:id f-fn) (lookups-for storage)))
            "rename whose source is F's own root-slot — no chain alias"))
      (finally (sp/close storage)))))


;; ============================================================================
;; build-ref-slot-renames — Phase 3 of the slot-id-keyed runtime refactor.
;; Slot-id sibling of build-ref-renames: produces {callee-slot-id →
;; caller-slot-id} rows the runtime applies at ref-call boundaries.
;; ============================================================================

(deftest build-ref-slot-renames-empty-when-no-overlap-test
  (testing "callee surfaces a name caller doesn't carry — no translation row"
    (let [storage (setup/create-test-storage)]
      (try
        (let [base-r (setup/build-fn! storage
                                      {:name "brsr0-base-r"
                                       :slots [{:name "x" :type :int}]})
              r-fn   (setup/build-fn! storage
                                      {:name "brsr0-r" :parent base-r})
              base-f (setup/create-base-fn! storage "brsr0-base-f")
              f-fn   (setup/create-composed-fn! storage "brsr0-f" (:id base-f))]
          (is (= {} (r/build-ref-slot-renames (-> r-fn :fn :id)
                                              (:id f-fn)
                                              (lookups-for storage)))
              "F has no walker entry for :x → R-x stays at its own slot-id"))
        (finally (sp/close storage))))))


(deftest build-ref-slot-renames-skips-identity-when-caller-has-same-slot-id-test
  (testing "F's transitive walk includes R's chain-leaf → no row needed"
    ;; F has slot :y bound to ref R. R has free :x. F's walker walks
    ;; into R and emits {:ext-name :x :slot-id R-x-sid}. So F's fa
    ;; already gets R-x-sid via the Phase 2 boundary translator —
    ;; per-ref translation has nothing to copy.
    (let [storage (setup/create-test-storage)]
      (try
        (let [base-r (setup/build-fn! storage
                                      {:name "brsr1-base-r"
                                       :slots [{:name "x" :type :int}]})
              r-fn   (setup/build-fn! storage
                                      {:name "brsr1-r" :parent base-r})
              base-f (setup/build-fn! storage
                                      {:name "brsr1-base-f"
                                       :slots [{:name "y" :type :int}]})
              f-fn   (setup/build-fn! storage
                                      {:name "brsr1-f"
                                       :parent base-f
                                       :bindings {"y" {:ref r-fn}}})]
          (is (= {} (r/build-ref-slot-renames (-> r-fn :fn :id)
                                              (-> f-fn :fn :id)
                                              (lookups-for storage)))
              "transitively-included slot-id → identity skip"))
        (finally (sp/close storage))))))


(deftest build-ref-slot-renames-degenerates-when-no-caller-coverage-test
  (testing "callee has surface entry but caller's walker is empty → no row"
    ;; The function still emits {} when caller's walker yields no
    ;; surface entries at all (e.g. F is a bare composed fn with no
    ;; bindings, no parent slots, no refs). This pins the "no
    ;; caller-side name to map to" branch of the helper.
    (let [storage (setup/create-test-storage)]
      (try
        (let [base-r (setup/build-fn! storage
                                      {:name "brsr2-base-r"
                                       :slots [{:name "inner" :type :int}]})
              r-fn   (setup/build-fn! storage
                                      {:name "brsr2-r" :parent base-r})
              base-f (setup/create-base-fn! storage "brsr2-base-f")
              f-fn   (setup/create-composed-fn! storage "brsr2-f" (:id base-f))]
          (is (= {} (r/build-ref-slot-renames (-> r-fn :fn :id)
                                              (:id f-fn)
                                              (lookups-for storage)))
              "no caller entries → callee surface stays at its own slot-ids"))
        (finally (sp/close storage))))))
