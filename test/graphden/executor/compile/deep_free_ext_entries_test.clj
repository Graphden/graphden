(ns graphden.executor.compile.deep-free-ext-entries-test
  "Tests for `deep-free-ext-entries` — the Phase 1 walker of the
   runtime slot-id-keyed refactor (docs/RUNTIME_SLOT_ID_REFACTOR.md).

   Returns `[{:ext-name K :slot-id UUID} …]` — one entry per
   chain-leaf slot the inner consumers under `fn-id` read from `fa`,
   keyed by SLOT-ID so two distinct slots sharing an ext-name (#104
   `:body`) BOTH surface."
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


(deftest single-free-arg-emits-chain-leaf-slot-id-test
  (testing "an unbound slot surfaces with its chain-leaf base-fn slot id"
    (let [storage (setup/create-test-storage)]
      (try
        (let [base (setup/build-fn! storage
                                    {:name "dfe-base"
                                     :slots [{:name "a" :type :int}
                                             {:name "b" :type :int}]})
              fn1  (setup/build-fn! storage
                                    {:name "dfe-1"
                                     :parent base
                                     :bindings {"a" {:value 10}}})
              entries (r/deep-free-ext-entries (-> fn1 :fn :id)
                                               (lookups-for storage))]
          (is (= 1 (count entries))
              "only :b is free — :a is value-bound and covered by slot-id")
          (is (= :b (:ext-name (first entries))))
          (is (= (-> base :slots (get "b") :id) (:slot-id (first entries)))
              "slot-id IS base-fn's :b slot row — chain-leaf reader's bnd.slot-id"))
        (finally (sp/close storage))))))


(deftest two-slots-sharing-ext-name-both-surface-test
  ;; The #104 case in synthetic form: two structurally distinct slots
  ;; reachable from one fn happen to share an ext-name. The legacy
  ;; `deep-free-ext-names` dedupes by name and emits ONE; the new
  ;; walker dedupes by slot-id and emits BOTH.
  (testing "same name, different slot-ids → two entries"
    (let [storage (setup/create-test-storage)]
      (try
        (let [;; G owns its own root slot :x — chain-leaf SID_g.
              base-g (setup/build-fn! storage
                                      {:name "dfe-collide-base-g"
                                       :slots [{:name "x" :type :int}]})
              fn-g   (setup/build-fn! storage
                                      {:name "dfe-collide-g" :parent base-g})
              ;; F owns its OWN root slot also named :x (chain-leaf
              ;; SID_f) plus a routing slot :y to ref G.
              base-f (setup/build-fn! storage
                                      {:name "dfe-collide-base-f"
                                       :slots [{:name "x" :type :int}
                                               {:name "y" :type :int}]})
              fn-f   (setup/build-fn! storage
                                      {:name "dfe-collide-f"
                                       :parent base-f
                                       :bindings {"y" {:ref fn-g}}})
              entries (r/deep-free-ext-entries (-> fn-f :fn :id)
                                               (lookups-for storage))
              ext-names (mapv :ext-name entries)
              slot-ids  (set (map :slot-id entries))
              sid-f-x   (-> base-f :slots (get "x") :id)
              sid-g-x   (-> base-g :slots (get "x") :id)]
          (is (= 2 (count entries))
              "F's own :x (free) and G's :x (free, via ref) both surface")
          (is (= [:x :x] ext-names)
              "BOTH entries have ext-name :x — ambiguity is the whole point")
          (is (= #{sid-f-x sid-g-x} slot-ids)
              "the two entries hold the two distinct chain-leaf slot-ids")
          (testing "legacy name-keyed walker hides the collision"
            (is (= [:x] (r/deep-free-ext-names (-> fn-f :fn :id)
                                               (lookups-for storage)))
                "deep-free-ext-names dedupes by name and emits one")))
        (finally (sp/close storage))))))


(deftest seq-positional-rename-emits-owner-slot-test
  (testing "seq item `{:as :name}` emits the owner's slot-id for :name"
    (let [storage (setup/create-test-storage)]
      (try
        (let [base    (setup/create-base-fn! storage "dfe-seq-base")
              s-list  (setup/create-slot! storage "items" :int)
              _       (setup/attach-slot! storage (:id base) (:id s-list) 0)
              f-fn    (setup/create-composed-fn! storage "dfe-seq-f" (:id base))
              list-bn (sp/create-entity storage :binding
                                        {:fn-id (:id f-fn)
                                         :slot-id (:id s-list)
                                         :list-append true
                                         :override-kind :fixed})
              ;; A positional `{:as :alpha}` creates a rename slot on
              ;; f-fn named "alpha" whose source chains back to
              ;; s-list. parser does this in production; for the
              ;; synthetic test we mint the rename slot directly so
              ;; the walker's `slot-by-fn-name [fid :alpha]` lookup
              ;; succeeds.
              alpha-slot (sp/create-entity storage :slot
                                           {:name "alpha"
                                            :type-fn-id (:type-fn-id s-list)
                                            :source-slot-id (:id s-list)})
              _ (sp/create-entity storage :fn-slot
                                  {:fn-id (:id f-fn)
                                   :slot-id (:id alpha-slot)
                                   :position 1})]
          (sp/create-entity storage :binding-list-item
                            {:binding-id (:id list-bn) :position 0
                             :value {:as "alpha"} :literal nil})
          (let [entries (r/deep-free-ext-entries (:id f-fn)
                                                 (lookups-for storage))]
            (is (= [{:ext-name :alpha :slot-id (:id alpha-slot)}]
                   entries)
                "ext-name :alpha + slot-id is the owner's rename slot")))
        (finally (sp/close storage))))))


(deftest hof-ref-is-a-boundary-test
  ;; Mirrors renames-test's `hof-ref-is-a-boundary-test` — the new
  ;; walker honors the same `:is-fn` HOF boundary.
  (testing ":is-fn ref binding stops the entries-walker from descending"
    (let [storage (setup/create-test-storage)]
      (try
        (let [base-t   (setup/create-base-fn! storage "dfe-hof-base-t")
              s-free   (setup/create-slot! storage "inner-free" :int)
              _        (setup/attach-slot! storage (:id base-t) (:id s-free) 0)
              t-fn     (setup/create-composed-fn! storage "dfe-hof-t" (:id base-t))
              base-f   (setup/create-base-fn! storage "dfe-hof-base-f")
              s-callee (setup/create-slot! storage "callee" :fn)
              _        (setup/attach-slot! storage (:id base-f) (:id s-callee) 0)
              f-fn     (setup/create-composed-fn! storage "dfe-hof-f" (:id base-f))
              _        (setup/bind-ref! storage (:id f-fn) (:id s-callee) (:id t-fn))]
          (is (= [] (r/deep-free-ext-entries (:id f-fn) (lookups-for storage)))
              "T's frees do NOT bubble up through the HOF boundary"))
        (finally (sp/close storage))))))


(deftest ref-target-frees-bubble-up-test
  (testing "non-HOF ref-target's free args surface at the caller"
    (let [storage (setup/create-test-storage)]
      (try
        (let [base-d (setup/build-fn! storage
                                      {:name "dfe-bubble-base-d"
                                       :slots [{:name "inner" :type :int}]})
              d      (setup/build-fn! storage
                                      {:name "dfe-bubble-d" :parent base-d})
              base-c (setup/build-fn! storage
                                      {:name "dfe-bubble-base-c"
                                       :slots [{:name "s" :type :int}]})
              c      (setup/build-fn! storage
                                      {:name "dfe-bubble-c"
                                       :parent base-c
                                       :bindings {"s" {:ref d}}})
              entries (r/deep-free-ext-entries (-> c :fn :id)
                                               (lookups-for storage))]
          (is (= 1 (count entries)))
          (is (= :inner (:ext-name (first entries))))
          (is (= (-> base-d :slots (get "inner") :id)
                 (:slot-id (first entries)))
              "slot-id is D's :inner slot — what the inner :free reader uses"))
        (finally (sp/close storage))))))


(deftest memoisation-returns-identical-result-test
  (testing "repeated calls with the same lookups return identical vector"
    (let [storage (setup/create-test-storage)]
      (try
        (let [base (setup/build-fn! storage
                                    {:name "dfe-memo-base"
                                     :slots [{:name "a" :type :int}]})
              fn1  (setup/build-fn! storage
                                    {:name "dfe-memo-1" :parent base})
              lookups (lookups-for storage)
              r1 (r/deep-free-ext-entries (-> fn1 :fn :id) lookups)
              r2 (r/deep-free-ext-entries (-> fn1 :fn :id) lookups)]
          (is (identical? r1 r2)
              "second call hits :deep-free-ext-entries-cache and returns same vec"))
        (finally (sp/close storage))))))


(deftest cache-projection-carries-collision-slot-ids-test
  ;; F3 (name→id audit): the per-execute call-cache projects `fa` into
  ;; its key via `cache-projection-frees`. Projecting by NAME collapses
  ;; two distinct slots that share an ext-name (`fa[:x]` holds only the
  ;; last write) → a latent WRONG cache-hit even though the slot-id-aware
  ;; readers would route correctly. The projection now ALSO carries the
  ;; surface slot-ids so the two slots discriminate in the cache key.
  (testing "cache-projection-frees carries BOTH distinct slot-ids for a shared ext-name"
    (let [storage (setup/create-test-storage)]
      (try
        (let [base-g (setup/build-fn! storage
                                      {:name "cpf-collide-base-g"
                                       :slots [{:name "x" :type :int}]})
              fn-g   (setup/build-fn! storage
                                      {:name "cpf-collide-g" :parent base-g})
              base-f (setup/build-fn! storage
                                      {:name "cpf-collide-base-f"
                                       :slots [{:name "x" :type :int}
                                               {:name "y" :type :int}]})
              fn-f   (setup/build-fn! storage
                                      {:name "cpf-collide-f"
                                       :parent base-f
                                       :bindings {"y" {:ref fn-g}}})
              proj    (r/cache-projection-frees (-> fn-f :fn :id)
                                                (lookups-for storage))
              sid-f-x (-> base-f :slots (get "x") :id)
              sid-g-x (-> base-g :slots (get "x") :id)]
          (is (contains? proj :x)
              "ext-name :x still projected — the superset-of-names invariant holds")
          (is (not= sid-f-x sid-g-x)
              "the two same-named slots have genuinely distinct ids")
          (is (contains? proj sid-f-x)
              "F's own :x slot-id is in the cache key")
          (is (contains? proj sid-g-x)
              "G's :x slot-id (reached via ref) is in the cache key — no collapse"))
        (finally (sp/close storage))))))
