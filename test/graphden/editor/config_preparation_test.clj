(ns ^:serial graphden.editor.config-preparation-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.crud.entities.invalidation :as invalidation]
    [graphden.editor.config-preparation :as preparation]
    [graphden.executor.context :as context]
    [graphden.system.branch-router.epoch :as epoch]))


(deftest unknown-storage-layout-cannot-reserve-resolved-absent-identities
  (is (= :unsupported-storage
         (try
           (preparation/prepared-records {} nil nil
                                         (fn [& _] (throw (ex-info "Unexpected source authorization" {})))
                                         nil (random-uuid))
           nil
           (catch clojure.lang.ExceptionInfo error (:reason (ex-data error)))))))


(deftest committed-preparation-keeps-cleanup-receipt-after-publication-failure
  (let [id (random-uuid)
        namespace-id (random-uuid)
        receipt {:id id :created true :committed true :functions [id] :namespaces [namespace-id]}
        calls (atom [])
        result (with-redefs [context/invalidate-graph-cache!
                             (fn [_ ids]
                               (swap! calls conj ids)
                               (throw (ex-info "PRIVATE exception detail" {:private "PRIVATE data"})))]
                 (#'preparation/publish! {} nil receipt))]
    (is (= [[id]] @calls))
    (is (= receipt (dissoc result :publication-warnings)))
    (is (= [{:reason "Your editor configuration was created. Refresh the editor to reload its derived state."}]
           (:publication-warnings result)))))


(deftest namespace-only-home-setup-acknowledges-its-committed-epoch
  (let [id (random-uuid)
        result {:setup true :home {:id id :name "personal"} :changed-namespaces [id]}
        calls (atom [])]
    (with-redefs [context/invalidate-graph-cache! (fn [& _] (throw (ex-info "No function was created" {})))
                  invalidation/notify-after-write! (fn [& _] (throw (ex-info "No function was changed" {})))
                  epoch/note-graph-epoch-validated! (fn [storage]
                                                      (is (= ::storage storage))
                                                      (swap! calls conj :acknowledged))]
      (is (= result (#'preparation/publish! {} ::storage result)))
      (is (= [:acknowledged] @calls))
      (reset! calls [])
      (is (= {:setup true} (#'preparation/publish! {} ::storage {:setup true})))
      (is (empty? @calls)))))
