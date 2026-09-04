(ns graphden.system.moved-identity-test
  "The sync-time ghost reconciler (`pkg-sync/reconcile-moved-identities!`)
   — the ROOT fix for the namespace-move ghost class. Storage is
   seeded as the POST-round-2-sync state: the old deterministic-id
   row (round 1, ns `pkga.mail`) survives with a ref still pointing
   at it, the new row (ns `pkga.notify`) was just synced. The
   reconciler must repoint and purge the ghost — and must NOT touch
   per-ns twins, editor (random-id) rows, or rows of packages outside
   the synced set."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.test-setup :as setup]
    [graphden.packages.records :as records]
    [graphden.packages.sync :as pkg-sync]
    [graphden.storage.protocol.core :as sp]
    [graphden.versioning.identity-repair :as ir]
    [graphden.versioning.storage.core :as vs])
  (:import
    (java.time
      Instant)))


(use-fixtures :once (setup/create-container-fixture))


(defn- ns-row!
  [storage path parent-id]
  (sp/create-entity storage :ns
                    {:id (random-uuid)
                     :name (last (str/split path #"\."))
                     :parent-id parent-id}))


(deftest reconciles-a-namespace-move-with-production-map-shape
  ;; P1 regression: write-records! returns a {fn-name → id} MAP, not
  ;; rows. reconcile must handle that shape — the old body treated it as
  ;; rows (group-by :name over a map = {}), so EVERY move fell into the
  ;; removal branch and repoint-refs! never ran.
  (let [storage (setup/create-test-storage)]
    (try
      (let [pkga (ns-row! storage "pkga" nil)
            mail (ns-row! storage "pkga.mail" (:id pkga))
            notify (ns-row! storage "pkga.notify" (:id pkga))
            ghost-id (records/fn-id "pkga.mail" :send-email)
            _ (sp/create-entity storage :fn
                                {:id ghost-id :name "send-email"
                                 :namespace-id (:id mail) :parent-ids []})
            new-id (records/fn-id "pkga.notify" :send-email)
            _ (sp/create-entity storage :fn
                                {:id new-id :name "send-email"
                                 :namespace-id (:id notify) :parent-ids []})
            base (setup/create-base-fn! storage "mip-caller-base")
            slot (setup/create-slot! storage "f" :int)
            _ (setup/attach-slot! storage (:id base) (:id slot) 0)
            caller (setup/create-composed-fn! storage "mip-caller" (:id base))
            bind (sp/create-entity storage :binding
                                   {:fn-id (:id caller) :slot-id (:id slot)
                                    :ref-fn-id ghost-id})
            ;; PRODUCTION shape: a {fn-name-keyword → id} map from write-records!
            n (pkg-sync/reconcile-moved-identities!
                storage
                {:packages [{:name "pkga"}]}
                {:send-email new-id})]
        (testing "the ghost is reconciled (repoint-refs ran) — map shape works"
          (is (= 1 n))
          (is (= new-id (:ref-fn-id (sp/read-entity storage :binding (:id bind)))))
          (is (nil? (sp/read-entity storage :fn ghost-id)))))
      (finally (sp/close storage)))))


(deftest reconciles-a-namespace-move
  (let [storage (setup/create-test-storage)]
    (try
      (let [pkga (ns-row! storage "pkga" nil)
            mail (ns-row! storage "pkga.mail" (:id pkga))
            notify (ns-row! storage "pkga.notify" (:id pkga))
            ;; round-1 row: deterministic id under the OLD ns
            ghost-id (records/fn-id "pkga.mail" :send-email)
            _ (sp/create-entity storage :fn
                                {:id ghost-id :name "send-email"
                                 :namespace-id (:id mail)
                                 :parent-ids []})
            ;; round-2 row: the SAME fn moved — new deterministic id
            new-id (records/fn-id "pkga.notify" :send-email)
            new-row (sp/create-entity storage :fn
                                      {:id new-id :name "send-email"
                                       :namespace-id (:id notify)
                                       :parent-ids []})
            ;; a caller whose binding still refs the GHOST
            base (setup/create-base-fn! storage "mi-caller-base")
            slot (setup/create-slot! storage "f" :int)
            _ (setup/attach-slot! storage (:id base) (:id slot) 0)
            caller (setup/create-composed-fn! storage "mi-caller"
                                              (:id base))
            bind (sp/create-entity storage :binding
                                   {:fn-id (:id caller)
                                    :slot-id (:id slot)
                                    :ref-fn-id ghost-id})
            ;; a LEGAL per-ns twin in a namespace of ANOTHER package —
            ;; must never be touched (its package isn't being synced)
            other (ns-row! storage "otherpkg" nil)
            twin-id (records/fn-id "otherpkg" :send-email)
            _ (sp/create-entity storage :fn
                                {:id twin-id :name "send-email"
                                 :namespace-id (:id other)
                                 :parent-ids []})
            ;; an EDITOR row (random id ≠ deterministic) — never touched
            editor-row (sp/create-entity storage :fn
                                         {:id (random-uuid)
                                          :name "hand-made"
                                          :namespace-id (:id mail)
                                          :parent-ids []})
            n (pkg-sync/reconcile-moved-identities!
                storage
                {:packages [{:name "pkga"}]}
                [new-row])]
        (testing "exactly the ghost was reconciled"
          (is (= 1 n)))
        (testing "the ref now points at the moved fn"
          (is (= new-id (:ref-fn-id (sp/read-entity storage :binding
                                                    (:id bind))))))
        (testing "the ghost row is gone"
          (is (nil? (sp/read-entity storage :fn ghost-id))))
        (testing "the other-package twin and the editor row survive"
          (is (some? (sp/read-entity storage :fn twin-id)))
          (is (some? (sp/read-entity storage :fn (:id editor-row))))))
      (finally (sp/close storage)))))


(deftest purges-unreferenced-removal-keeps-referenced-one
  ;; P0.2: a fn dropped from a synced package's EDN (0 same-name
  ;; candidates) is DEAD identity — purge it when nothing references it,
  ;; but LEAVE it (loud) when something still does.
  (let [storage (setup/create-test-storage)]
    (try
      (let [pkga (ns-row! storage "pkgb" nil)
            m1 (ns-row! storage "pkgb.a" (:id pkga))
            ;; (1) removed AND unreferenced → purged
            gone-id (records/fn-id "pkgb.a" :gone-fn)
            _ (sp/create-entity storage :fn
                                {:id gone-id :name "gone-fn"
                                 :namespace-id (:id m1)
                                 :parent-ids []})
            ;; (2) removed BUT still referenced by a caller's binding → left
            used-id (records/fn-id "pkgb.a" :still-used-fn)
            _ (sp/create-entity storage :fn
                                {:id used-id :name "still-used-fn"
                                 :namespace-id (:id m1)
                                 :parent-ids []})
            base (setup/create-base-fn! storage "rm-caller-base")
            slot (setup/create-slot! storage "f" :int)
            _ (setup/attach-slot! storage (:id base) (:id slot) 0)
            caller (setup/create-composed-fn! storage "rm-caller" (:id base))
            _ (sp/create-entity storage :binding
                                {:fn-id (:id caller) :slot-id (:id slot)
                                 :ref-fn-id used-id})
            n (pkg-sync/reconcile-moved-identities!
                storage {:packages [{:name "pkgb"}]} [])]
        (is (= 2 n) "both removals counted as leftovers")
        (is (nil? (sp/read-entity storage :fn gone-id))
            "unreferenced dead identity is purged (no DB reset needed)")
        (is (some? (sp/read-entity storage :fn used-id))
            "a still-referenced removal is left in place, not silently deleted"))
      (finally (sp/close storage)))))


(deftest purges-a-retired-chain-as-a-set
  ;; A retired fn-def SECTION references itself (parent chains, arg
  ;; refs). Per-row reconciling left every non-leaf member behind as
  ;; "still referenced" — by its own removed siblings — while paying a
  ;; full ref-surface scan per row (the 2026-08-31 deploy-health
  ;; blowup). The set-aware pass must purge the whole chain in ONE
  ;; boot, and still keep a member referenced from OUTSIDE the set.
  (let [storage (setup/create-test-storage)]
    (try
      (let [pkgc (ns-row! storage "pkgc" nil)
            m1 (ns-row! storage "pkgc.a" (:id pkgc))
            mk! (fn [nm parent-ids]
                  (let [id (records/fn-id "pkgc.a" (keyword nm))]
                    (sp/create-entity storage :fn
                                      {:id id :name nm
                                       :namespace-id (:id m1)
                                       :parent-ids (vec parent-ids)})
                    id))
            ;; chain: root ← mid (parent) ; mid --ref--> leaf
            leaf-id (mk! "chain-leaf" [])
            mid-id (mk! "chain-mid" [])
            root-id (mk! "chain-root" [mid-id])
            slot (setup/create-slot! storage "cx" :int)
            _ (setup/attach-slot! storage mid-id (:id slot) 0)
            _ (sp/create-entity storage :binding
                                {:fn-id mid-id :slot-id (:id slot)
                                 :ref-fn-id leaf-id})
            ;; a 4th removal referenced from a LIVE fn stays put
            pinned-id (mk! "chain-pinned" [])
            base (setup/create-base-fn! storage "chain-caller-base")
            slot2 (setup/create-slot! storage "cy" :int)
            _ (setup/attach-slot! storage (:id base) (:id slot2) 0)
            caller (setup/create-composed-fn! storage "chain-caller" (:id base))
            _ (sp/create-entity storage :binding
                                {:fn-id (:id caller) :slot-id (:id slot2)
                                 :ref-fn-id pinned-id})
            n (pkg-sync/reconcile-moved-identities!
                storage {:packages [{:name "pkgc"}]} [])]
        (is (= 4 n) "all four removals counted as leftovers")
        (doseq [[nm id] [["chain-root" root-id] ["chain-mid" mid-id]
                         ["chain-leaf" leaf-id]]]
          (is (nil? (sp/read-entity storage :fn id))
              (str nm " purged — in-set refs don't pin the chain")))
        (is (some? (sp/read-entity storage :fn pinned-id))
            "a removal referenced from a LIVE fn is left in place"))
      (finally (sp/close storage)))))


(deftest inbound-refs-many-matches-per-fn-surface
  ;; The batch scan must agree with the per-fn `inbound-refs` on the
  ;; same storage — same targets reported, plus owner attribution.
  (let [storage (setup/create-test-storage)]
    (try
      (let [pkgd (ns-row! storage "pkgd" nil)
            m1 (ns-row! storage "pkgd.a" (:id pkgd))
            t1 (records/fn-id "pkgd.a" :t-one)
            t2 (records/fn-id "pkgd.a" :t-two)
            _ (sp/create-entity storage :fn {:id t1 :name "t-one"
                                             :namespace-id (:id m1)
                                             :parent-ids []})
            _ (sp/create-entity storage :fn {:id t2 :name "t-two"
                                             :namespace-id (:id m1)
                                             :parent-ids [t1]})
            base (setup/create-base-fn! storage "irm-base")
            slot (setup/create-slot! storage "cz" :int)
            _ (setup/attach-slot! storage (:id base) (:id slot) 0)
            caller (setup/create-composed-fn! storage "irm-caller" (:id base))
            _ (sp/create-entity storage :binding
                                {:fn-id (:id caller) :slot-id (:id slot)
                                 :ref-fn-id t1})
            many (ir/inbound-refs-many storage #{t1 t2})]
        (is (= (set (map #(dissoc % :owner-fn-id) (get many t1)))
               (set (ir/inbound-refs storage t1)))
            "batch scan reports the same refs as the per-fn scan (t1)")
        (is (= (set (map #(dissoc % :owner-fn-id) (get many t2)))
               (set (ir/inbound-refs storage t2)))
            "batch scan reports the same refs as the per-fn scan (t2)")
        (is (= #{(:id caller) t2}
               (into #{} (map :owner-fn-id) (get many t1)))
            "owner attribution: the caller's binding and t-two's parent edge"))
      (finally (sp/close storage)))))


(deftest same-bare-name-in-another-namespace-is-a-removal-not-a-move
  ;; ADR-identity stage 5: the same bare name may live in several
  ;; namespaces. Removing pkgd.x/foo while unrelated pkgd.y/foo keeps
  ;; syncing must NOT repoint the world at pkgd.y/foo — with the
  ;; :preexisting-fn-ids guard the 1-candidate "move" is accepted only
  ;; when the candidate row was minted by THIS sync.
  (let [storage (setup/create-test-storage)]
    (try
      (let [pkgd (ns-row! storage "pkgd" nil)
            mx (ns-row! storage "pkgd.x" (:id pkgd))
            my (ns-row! storage "pkgd.y" (:id pkgd))
            removed-id (records/fn-id "pkgd.x" :dupfoo)
            _ (sp/create-entity storage :fn
                                {:id removed-id :name "dupfoo"
                                 :namespace-id (:id mx) :parent-ids []})
            unrelated-id (records/fn-id "pkgd.y" :dupfoo)
            _ (sp/create-entity storage :fn
                                {:id unrelated-id :name "dupfoo"
                                 :namespace-id (:id my) :parent-ids []})
            ;; a live caller still references the REMOVED one
            base (setup/create-base-fn! storage "dup-caller-base")
            slot (setup/create-slot! storage "dz" :int)
            _ (setup/attach-slot! storage (:id base) (:id slot) 0)
            caller (setup/create-composed-fn! storage "dup-caller" (:id base))
            binding-row (sp/create-entity storage :binding
                                          {:fn-id (:id caller) :slot-id (:id slot)
                                           :ref-fn-id removed-id})
            ;; both rows PRE-DATE the sync; only pkgd.y/dupfoo re-syncs
            n (pkg-sync/reconcile-moved-identities!
                storage {:packages [{:name "pkgd"}]}
                [{:name "dupfoo" :id unrelated-id}]
                {:preexisting-fn-ids #{removed-id unrelated-id
                                       (:id base) (:id caller)}})]
        (is (= 1 n) "the dropped identity is a leftover")
        (is (some? (sp/read-entity storage :fn removed-id))
            "still-referenced removal stays put — NOT repointed away")
        (is (= removed-id (:ref-fn-id (sp/read-entity storage :binding (:id binding-row))))
            "the caller's ref still targets the removed fn, not the unrelated same-name one"))
      (finally (sp/close storage)))))


(deftest retired-chain-with-own-type-slot-purges-fully
  ;; A retired section whose slot is TYPED by its own type-row: the
  ;; slot has no owning fn (external by naive attribution), but every
  ;; fn-slot exposing it belongs to the removal set and nothing else
  ;; binds it — the set-aware pass must see through the slot, purge
  ;; the chain AND the orphaned slot, so the type-row isn't pinned
  ;; forever.
  (let [storage (setup/create-test-storage)]
    (try
      (let [pkge (ns-row! storage "pkge" nil)
            m1 (ns-row! storage "pkge.a" (:id pkge))
            type-id (records/fn-id "pkge.a" :sec-type)
            _ (sp/create-entity storage :fn
                                {:id type-id :name "sec-type"
                                 :namespace-id (:id m1) :parent-ids []})
            user-id (records/fn-id "pkge.a" :sec-user)
            _ (sp/create-entity storage :fn
                                {:id user-id :name "sec-user"
                                 :namespace-id (:id m1) :parent-ids []})
            slot (sp/create-entity storage :slot
                                   {:id (random-uuid) :name "sv"
                                    :type-fn-id type-id})
            _ (sp/create-entity storage :fn-slot
                                {:fn-id user-id :slot-id (:id slot) :position 0})
            n (pkg-sync/reconcile-moved-identities!
                storage {:packages [{:name "pkge"}]} [])]
        (is (= 2 n) "both retired identities counted")
        (is (nil? (sp/read-entity storage :fn user-id)) "slot owner purged")
        (is (nil? (sp/read-entity storage :fn type-id))
            "type-row purged — its only pin was the set's own slot")
        (is (nil? (sp/read-entity storage :slot (:id slot)))
            "the fully-orphaned slot is purged too, not left dangling"))
      (finally (sp/close storage)))))


(deftest bulk-namespace-move-repoints-in-one-batch
  ;; Two fns move namespaces in the same sync — the batched path must
  ;; repoint BOTH callers' refs in one pass and purge both ghosts.
  (let [storage (setup/create-test-storage)]
    (try
      (let [pkgf (ns-row! storage "pkgf" nil)
            m-old (ns-row! storage "pkgf.old" (:id pkgf))
            m-new (ns-row! storage "pkgf.new" (:id pkgf))
            mk-old! (fn [nm]
                      (let [id (records/fn-id "pkgf.old" (keyword nm))]
                        (sp/create-entity storage :fn
                                          {:id id :name nm
                                           :namespace-id (:id m-old)
                                           :parent-ids []})
                        id))
            mk-new! (fn [nm]
                      (let [id (records/fn-id "pkgf.new" (keyword nm))]
                        (sp/create-entity storage :fn
                                          {:id id :name nm
                                           :namespace-id (:id m-new)
                                           :parent-ids []})
                        id))
            old-a (mk-old! "mv-a")
            old-b (mk-old! "mv-b")
            new-a (mk-new! "mv-a")
            new-b (mk-new! "mv-b")
            base (setup/create-base-fn! storage "mv-caller-base")
            slot-a (setup/create-slot! storage "ma" :int)
            slot-b (setup/create-slot! storage "mb" :int)
            _ (setup/attach-slot! storage (:id base) (:id slot-a) 0)
            caller (setup/create-composed-fn! storage "mv-caller" (:id base))
            bind-a (sp/create-entity storage :binding
                                     {:fn-id (:id caller) :slot-id (:id slot-a)
                                      :ref-fn-id old-a})
            bind-b (sp/create-entity storage :binding
                                     {:fn-id (:id caller) :slot-id (:id slot-b)
                                      :ref-fn-id old-b})
            n (pkg-sync/reconcile-moved-identities!
                storage {:packages [{:name "pkgf"}]}
                [{:name "mv-a" :id new-a} {:name "mv-b" :id new-b}]
                ;; the NEW rows were minted this sync — a true move
                {:preexisting-fn-ids #{old-a old-b (:id base) (:id caller)}})]
        (is (= 2 n) "both old identities are leftovers")
        (is (= new-a (:ref-fn-id (sp/read-entity storage :binding (:id bind-a))))
            "caller's first ref repointed at the moved id")
        (is (= new-b (:ref-fn-id (sp/read-entity storage :binding (:id bind-b))))
            "caller's second ref repointed in the same batch")
        (is (nil? (sp/read-entity storage :fn old-a)) "first ghost purged")
        (is (nil? (sp/read-entity storage :fn old-b)) "second ghost purged"))
      (finally (sp/close storage)))))


(deftest kept-members-slot-keeps-its-type-row
  ;; F1 of the 2026-09-01 audit: a retired fn that stays KEPT (still
  ;; referenced from live graph) exposes a slot typed by a retired
  ;; type-row. Keptness must flow THROUGH the slot: purging the
  ;; type-row would dangle the surviving fn's slot.type-fn-id, and the
  ;; row is gone from EDN so nothing recreates it.
  (let [storage (setup/create-test-storage)]
    (try
      (let [pkgg (ns-row! storage "pkgg" nil)
            m1 (ns-row! storage "pkgg.a" (:id pkgg))
            type-id (records/fn-id "pkgg.a" :kept-type)
            _ (sp/create-entity storage :fn
                                {:id type-id :name "kept-type"
                                 :namespace-id (:id m1) :parent-ids []})
            user-id (records/fn-id "pkgg.a" :kept-user)
            _ (sp/create-entity storage :fn
                                {:id user-id :name "kept-user"
                                 :namespace-id (:id m1) :parent-ids []})
            slot (sp/create-entity storage :slot
                                   {:id (random-uuid) :name "kv"
                                    :type-fn-id type-id})
            _ (sp/create-entity storage :fn-slot
                                {:fn-id user-id :slot-id (:id slot) :position 0})
            ;; a LIVE caller pins kept-user (→ it is kept, not purged)
            base (setup/create-base-fn! storage "kept-caller-base")
            cslot (setup/create-slot! storage "kc" :int)
            _ (setup/attach-slot! storage (:id base) (:id cslot) 0)
            caller (setup/create-composed-fn! storage "kept-caller" (:id base))
            _ (sp/create-entity storage :binding
                                {:fn-id (:id caller) :slot-id (:id cslot)
                                 :ref-fn-id user-id})
            n (pkg-sync/reconcile-moved-identities!
                storage {:packages [{:name "pkgg"}]} [])]
        (is (= 2 n) "both retired identities counted")
        (is (some? (sp/read-entity storage :fn user-id))
            "the still-referenced fn is kept")
        (is (some? (sp/read-entity storage :fn type-id))
            "…and the type-row its slot points at is kept THROUGH the slot")
        (is (some? (sp/read-entity storage :slot (:id slot)))
            "the kept fn's slot survives too"))
      (finally (sp/close storage)))))


(deftest leaves-ambiguous-move-alone
  ;; >1 same-name candidate in the synced set = a move we cannot resolve;
  ;; the leftover is counted, warned, and left untouched.
  (let [storage (setup/create-test-storage)]
    (try
      (let [pkga (ns-row! storage "pkgc" nil)
            m1 (ns-row! storage "pkgc.a" (:id pkga))
            m2 (ns-row! storage "pkgc.b" (:id pkga))
            m3 (ns-row! storage "pkgc.c" (:id pkga))
            leftover-id (records/fn-id "pkgc.a" :amb-fn)
            _ (sp/create-entity storage :fn
                                {:id leftover-id :name "amb-fn"
                                 :namespace-id (:id m1)
                                 :parent-ids []})
            ;; two just-synced same-name candidates → ambiguous
            cand1 {:id (records/fn-id "pkgc.b" :amb-fn) :name "amb-fn"
                   :namespace-id (:id m2) :parent-ids []}
            cand2 {:id (records/fn-id "pkgc.c" :amb-fn) :name "amb-fn"
                   :namespace-id (:id m3) :parent-ids []}
            n (pkg-sync/reconcile-moved-identities!
                storage {:packages [{:name "pkgc"}]} [cand1 cand2])]
        (is (= 1 n) "counted as a leftover")
        (is (some? (sp/read-entity storage :fn leftover-id))
            "ambiguous move target left untouched"))
      (finally (sp/close storage)))))


(deftest repoint-refs!-fills-the-version-plane-not-just-identity
  ;; `repoint-refs!`'s load-bearing claim is that it repoints refs across BOTH
  ;; planes — identity rows AND every branch's `*-version` rows — because a
  ;; diverging-branch version ref would otherwise be left pointing at a purged
  ;; ghost (the broken-graph-on-one-branch class). The other tests seed only
  ;; identity-plane refs, so a miss on the version plane would pass silently.
  ;; Here we seed a `:binding-version` row whose `:ref-fn-id` is the ghost (ref
  ;; columns carry no FK, so arbitrary owner ids suffice) and assert repoint
  ;; moves it. It also seeds an identity-plane ref, to pin BOTH in one pass.
  (let [storage (setup/create-test-storage)
        ghost (random-uuid)
        new-id (random-uuid)]
    (try
      (let [id-plane (sp/create-entity storage :binding
                                       {:fn-id (random-uuid) :slot-id (random-uuid)
                                        :ref-fn-id ghost})
            ver-plane (sp/create-entity storage :binding-version
                                        {:binding-id (random-uuid) :branch-id (random-uuid)
                                         :fn-id (random-uuid) :slot-id (random-uuid)
                                         :ref-fn-id ghost :value-present false
                                         :created-at (Instant/now)})]
        (ir/repoint-refs! storage {ghost new-id})
        (testing "the identity-plane ref is repointed off the ghost"
          (is (= new-id (:ref-fn-id (sp/read-entity storage :binding (:id id-plane))))))
        (testing "the VERSION-plane ref is repointed too (the audited gap)"
          (is (= new-id (:ref-fn-id (sp/read-entity storage :binding-version (:id ver-plane)))))))
      (finally (sp/close storage)))))


;; --- removal liveness: identity rows and superseded versions are history ---

(defn- versioned-storage
  "A per-test VersionedStorage over a fresh raw storage, on its main
   branch — the shape the boot sync writes through in production."
  [raw]
  (vs/wrap-with-versioning raw "main"))


(deftest a-removal-referenced-only-by-history-is-purged
  ;; The 2026-09-04 lint sweep: a two-month-old instance carried 472
  ;; retired package identities; 175 were "referenced" — through the
  ;; create-time identity row of a binding a re-sync had since repointed,
  ;; or through a superseded version. Neither is a ref the current graph
  ;; follows, so neither may keep a removal alive.
  (let [raw (setup/create-test-storage)]
    (try
      (let [storage (versioned-storage raw)
            pkga (ns-row! raw "pkga" nil)
            retired-id (records/fn-id "pkga" :retired-helper)
            _ (sp/create-entity storage :fn {:id retired-id :name "retired-helper"
                                             :namespace-id (:id pkga) :parent-ids []})
            keeper-id (records/fn-id "pkga" :keeper)
            _ (sp/create-entity storage :fn {:id keeper-id :name "keeper"
                                             :namespace-id (:id pkga) :parent-ids []})
            base (setup/create-base-fn! storage "rl-caller-base")
            slot (setup/create-slot! storage "f" :int)
            _ (setup/attach-slot! storage (:id base) (:id slot) 0)
            caller (setup/create-composed-fn! storage "rl-caller" (:id base))
            ;; created pointing at the retired helper (the identity row keeps
            ;; that value for good) …
            bind (sp/create-entity storage :binding {:fn-id (:id caller) :slot-id (:id slot)
                                                     :ref-fn-id retired-id})
            ;; … then repointed by a later sync: the newest version names keeper
            _ (sp/update-entity storage :binding (:id bind) {:ref-fn-id keeper-id})
            plan (pkg-sync/reconcile-moved-identities!
                   storage {:packages [{:name "pkga"}]} {:keeper keeper-id}
                   {:preexisting-fn-ids #{keeper-id retired-id} :dry-run? true})]
        (testing "the create-time identity row still names the retired helper"
          (is (= retired-id (:ref-fn-id (sp/read-entity raw :binding (:id bind))))))
        (testing "the dry run plans the purge and touches nothing"
          (is (= ["retired-helper"] (:purgeable plan)))
          (is (= [] (:kept plan)))
          (is (some? (sp/read-entity raw :fn retired-id))))
        (testing "the real run purges it"
          (pkg-sync/reconcile-moved-identities!
            storage {:packages [{:name "pkga"}]} {:keeper keeper-id}
            {:preexisting-fn-ids #{keeper-id retired-id}})
          (is (nil? (sp/read-entity raw :fn retired-id)))
          (is (some? (sp/read-entity raw :fn keeper-id)))
          (is (= keeper-id (:ref-fn-id (sp/read-entity storage :binding (:id bind)))))))
      (finally (sp/close raw)))))


(deftest a-removal-the-newest-version-still-references-is-kept
  (let [raw (setup/create-test-storage)]
    (try
      (let [storage (versioned-storage raw)
            pkga (ns-row! raw "pkga" nil)
            retired-id (records/fn-id "pkga" :still-used)
            _ (sp/create-entity storage :fn {:id retired-id :name "still-used"
                                             :namespace-id (:id pkga) :parent-ids []})
            base (setup/create-base-fn! storage "rk-caller-base")
            slot (setup/create-slot! storage "f" :int)
            _ (setup/attach-slot! storage (:id base) (:id slot) 0)
            caller (setup/create-composed-fn! storage "rk-caller" (:id base))
            _ (sp/create-entity storage :binding {:fn-id (:id caller) :slot-id (:id slot)
                                                  :ref-fn-id retired-id})
            plan (pkg-sync/reconcile-moved-identities!
                   storage {:packages [{:name "pkga"}]} {}
                   {:preexisting-fn-ids #{retired-id} :dry-run? true})]
        (is (= ["still-used"] (:kept plan)))
        (is (= [] (:purgeable plan)))
        (pkg-sync/reconcile-moved-identities!
          storage {:packages [{:name "pkga"}]} {} {:preexisting-fn-ids #{retired-id}})
        (is (some? (sp/read-entity raw :fn retired-id)) "a live ref keeps the removal, loudly"))
      (finally (sp/close raw)))))


(deftest a-stale-anon-row-goes-with-the-chain-it-pinned
  ;; An inline def the parser lifted under a shape-hash name is a package
  ;; row like any other; once its shape is gone it must not keep the
  ;; retired helper it references alive.
  (let [raw (setup/create-test-storage)]
    (try
      (let [storage (versioned-storage raw)
            pkga (ns-row! raw "pkga" nil)
            helper-id (records/fn-id "pkga" :_old-helper)
            _ (sp/create-entity storage :fn {:id helper-id :name "_old-helper"
                                             :namespace-id (:id pkga) :parent-ids []})
            anon-id (records/fn-id "pkga" :_anon-0123456789abcdef)
            base (setup/create-base-fn! storage "an-base")
            slot (setup/create-slot! storage "f" :int)
            _ (setup/attach-slot! storage (:id base) (:id slot) 0)
            _ (sp/create-entity storage :fn {:id anon-id :name "_anon-0123456789abcdef"
                                             :namespace-id (:id pkga) :parent-ids [(:id base)]})
            _ (sp/create-entity storage :binding {:fn-id anon-id :slot-id (:id slot)
                                                  :ref-fn-id helper-id})
            plan (pkg-sync/reconcile-moved-identities!
                   storage {:packages [{:name "pkga"}]} {}
                   {:preexisting-fn-ids #{helper-id anon-id} :dry-run? true})]
        (is (= #{"_old-helper" "_anon-0123456789abcdef"} (set (:purgeable plan))))
        (pkg-sync/reconcile-moved-identities!
          storage {:packages [{:name "pkga"}]} {} {:preexisting-fn-ids #{helper-id anon-id}})
        (is (nil? (sp/read-entity raw :fn helper-id)))
        (is (nil? (sp/read-entity raw :fn anon-id))))
      (finally (sp/close raw)))))
