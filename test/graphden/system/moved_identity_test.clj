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


(deftest leaves-ambiguous-and-removed-alone
  (let [storage (setup/create-test-storage)]
    (try
      (let [pkga (ns-row! storage "pkgb" nil)
            m1 (ns-row! storage "pkgb.a" (:id pkga))
            ;; a package row whose name is NOT in the new sync at all —
            ;; genuine removal, must be left (logged) untouched
            removed-id (records/fn-id "pkgb.a" :gone-fn)
            _ (sp/create-entity storage :fn
                                {:id removed-id :name "gone-fn"
                                 :namespace-id (:id m1)
                                 :parent-ids []})
            n (pkg-sync/reconcile-moved-identities!
                storage {:packages [{:name "pkgb"}]} [])]
        (is (= 1 n) "counted as a leftover")
        (is (some? (sp/read-entity storage :fn removed-id))
            "but NOT purged — removal is the author's call, not ours"))
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
