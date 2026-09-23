(ns ^:serial graphden.system.init.services-test
  "`^:serial`: stubs `recon/restart-services-depending-on!` /
   `restart-services-on-branch!` with `with-redefs` — a root rebind that
   every CRUD write in a parallel namespace would call into (and break the
   exact-call assertions below with its own calls).

   Unit coverage for the NOTIFY-driven cross-pod service restart wiring.
   `restart-notified-services!` is the hook a sibling pod runs when it hears a
   `fn:invalidate` event, so a cron/loop singleton it owns restarts its closure
   after a fn edit / merge on ANOTHER pod (before this, only the writer pod's
   local hook fired, leaving siblings firing the pre-edit graph)."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.crud.entities.invalidation :as inval]
    [graphden.executor.context :as exec-ctx]
    [graphden.services.reconciler :as recon]
    [graphden.storage.postgres.notify :as pg-notify]
    [graphden.system.branch-router :as br]
    [graphden.system.branch-router.epoch :as br-epoch]
    [graphden.system.init.services :as svc]
    [graphden.versioning.storage.core :as vcore]))


(def ^:private restart! #'svc/restart-notified-services!)
(def ^:private on-notify #'svc/on-notify)


(deftest cross-pod-fn-notify-restart-routing-test
  (let [calls (atom [])
        b (str (random-uuid))
        f (str (random-uuid))]
    (with-redefs [recon/restart-services-depending-on!
                  (fn [_ctx _running seeds branch] (swap! calls conj [:depending seeds branch]))
                  recon/restart-services-on-branch!
                  (fn [_ctx _running branch] (swap! calls conj [:on-branch branch]))]
      (testing "a seeded fn:invalidate restarts services depending on that fn on the edit branch"
        (reset! calls [])
        (restart! {} f b)
        (is (= [[:depending [(java.util.UUID/fromString f)] (java.util.UUID/fromString b)]]
               @calls)))
      (testing "a full-clear event (no seed) conservatively restarts every service on the branch"
        (reset! calls [])
        (restart! {} "" b)
        (is (= [[:on-branch (java.util.UUID/fromString b)]] @calls)))
      (testing "no branch-id → no-op (nothing safe to target cross-pod)"
        (reset! calls [])
        (restart! {} f "")
        (is (empty? @calls)))
      (testing "a restart-hook exception is swallowed (best-effort — the write already committed)"
        (reset! calls [])
        (with-redefs [recon/restart-services-depending-on!
                      (fn [& _] (throw (ex-info "boom" {})))]
          (is (nil? (restart! {} f b)) "does not propagate"))))))


(deftest own-fn-notify-does-not-restart-services-twice-test
  ;; LISTEN echoes a pod's own `fn:invalidate` back to it. The local write
  ;; hook already restarted the affected services, so the echo bounced each
  ;; of them a second time (a third, fourth… per extra seed event). A
  ;; SIBLING's event must still restart them.
  (let [calls (atom [])
        b (str (random-uuid))
        f (str (random-uuid))
        mine (pg-notify/make-emitter nil)
        ctx {:notify-emitter mine}
        handle (on-notify ctx (fn [& _] nil))
        event (fn [emitter]
                (cond-> {:kind :fn :op :invalidate :id f :branch-id b}
                  emitter (assoc :emitter emitter)))]
    (with-redefs [exec-ctx/invalidate-graph-cache! (fn [& _] nil)
                  recon/restart-services-depending-on!
                  (fn [_ctx _running seeds _branch] (swap! calls conj seeds))]
      (testing "the pod's own echoed event restarts nothing"
        (handle (event (-> mine meta ::pg-notify/emitter-id)))
        (is (empty? @calls)))
      (testing "a sibling's event — or an older, unstamped one — still restarts"
        (handle (event (str (random-uuid))))
        (handle (event nil))
        (is (= 2 (count @calls)))))))


(deftest local-full-clear-restarts-the-branch-services-test
  ;; An unknown-shape write (nil seeds) full-clears locally; its empty-id
  ;; event restarts every service on the branch on SIBLINGS. The writer pod
  ;; got that restart only from its own echo — which it now skips — so the
  ;; local hook does it.
  (let [calls (atom [])
        b (random-uuid)]
    (with-redefs [inval/affected-fn-ids (constantly nil)
                  exec-ctx/invalidate-graph-cache! (fn [& _] nil)
                  br/current-router (constantly nil)
                  br-epoch/note-graph-epoch-validated! (fn [& _] nil)
                  vcore/current-branch-id (constantly b)
                  recon/restart-services-on-branch!
                  (fn [_ctx _running branch] (swap! calls conj [:on-branch branch]))
                  recon/restart-services-depending-on!
                  (fn [_ctx _running seeds _branch] (swap! calls conj [:depending seeds]))]
      (inval/invalidate! {} ::storage :binding {:id (random-uuid)})
      (is (= [[:on-branch b]] @calls)))))
