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
    [graphden.versioning.identity-repair :as ir])
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
