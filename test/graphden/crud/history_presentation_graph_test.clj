(ns ^:serial graphden.crud.history-presentation-graph-test
  "Graph response policy over canonical bounded reads and owned writes."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.crud.inheritance :as inheritance]
    [graphden.crud.views :as views]
    [graphden.executor.interface :as exec]
    [graphden.test-infra.exec-harness :as harness]
    [graphden.versioning.storage.history :as history]
    [graphden.versioning.storage.restore :as restore]))


(use-fixtures :once (harness/exec-fixture (str (ns-name *ns*))))


(defn- call
  [name args]
  (exec/execute-by-name harness/*context* name args))


(deftest metadata-page-projection-retains-lookahead-and-safe-fields
  (let [id (random-uuid)
        stamp (java.time.Instant/parse "2026-01-01T00:00:00Z")
        row {:group_id id :change_id nil :change_revision nil :created_at stamp
             :branch_id (str id) :kinds "fn" :event_count 2 :deleted false
             :author_label "author" :unselected "not published"}
        page {:rows [row row row] :limit 2 :offset 10}]
    (with-redefs [history/change-group-page (fn [& _] page)]
      (is (= {:groups [{:group-id (str id) :change-id nil :change-revision nil
                        :created-at (str stamp) :branch-id (str id) :kinds "fn"
                        :event-count 2 :deleted? false :author-label "author"
                        :has-change-id? false :has-change-revision? false}
                       {:group-id (str id) :change-id nil :change-revision nil
                        :created-at (str stamp) :branch-id (str id) :kinds "fn"
                        :event-count 2 :deleted? false :author-label "author"
                        :has-change-id? false :has-change-revision? false}]
              :next-offset 12}
             (call "load-fn-change-events" {:fn-id id :limit 2 :offset 10}))))
    (with-redefs [history/change-group-page (fn [& _] nil)]
      (is (nil? (call "load-fn-change-events" {:fn-id id :limit 2 :offset 0}))))))


(deftest restore-guards-and-conflict-policy-stay-outside-owned-operation
  (let [args {:fn-id (random-uuid) :branch-id (random-uuid) :revision 1}
        token (str/join (repeat 64 "a"))
        calls (atom 0)]
    (is (true? (call "_history-positive-revision?" {:revision 1})))
    (is (true? (call "_history-restore-args-valid?" args)))
    (with-redefs [restore/preview (fn [& _] (swap! calls inc) {:status :ready})
                  restore/apply-restore! (fn [& _] (swap! calls inc) {:committed true})]
      (is (nil? (call "preview-fn-history-restore" (assoc args :revision nil))))
      (is (nil? (call "commit-fn-history-restore" (assoc args :expected-current "short"))))
      (is (zero? @calls))
      (is (= {:status :ready} (call "_preview-fn-history-restore" args)) "raw operation")
      (is (= {:status :ready} (call "_history-restore-preview-try" args)) "exception policy")
      (is (= {:status :ready} (call "preview-fn-history-restore" args)))
      (is (= {:committed true}
             (call "commit-fn-history-restore" (assoc args :expected-current token)))))
    (doseq [kind [:history/branch-mismatch :history/too-large
                  :history/incomplete-provenance :history/secret-type-conflict]]
      (with-redefs [restore/preview (fn [& _] (throw (ex-info "conflict" {:type kind})))]
        (is (= {:status :conflict :reason kind} (call "preview-fn-history-restore" args)))))
    (with-redefs [restore/preview (fn [& _] (throw (ex-info "unexpected" {:type :unexpected})))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unexpected"
            (call "preview-fn-history-restore" args))))))


(deftest save-error-and-preview-only-command-policy-are-graph-visible
  (with-redefs [views/save-operation! (fn [& _]
                                        (throw (ex-info "Invalid view name" {:type :validation-error/name})))]
    (let [result (call "save-explorer-view" {:command {} :request {}})]
      (is (false? (:committed result)))
      (is (= 400 (:http-status result)))
      (is (= "Invalid view name" (:message result)))))
  (with-redefs [views/save-operation! (fn [& _] (throw (RuntimeException. "unexpected")))]
    (is (thrown-with-msg? RuntimeException #"unexpected"
          (call "save-explorer-view" {:command {} :request {}}))))
  (with-redefs [inheritance/apply-operation! (fn [& _] (throw (AssertionError. "must not mutate")))]
    (is (= {:ok false :committed false :type :validation-error/inheritance-command
            :error "Candidates is a preview-only action" :http-status 400}
           (call "apply-inheritance" {:command {:action "candidates"} :request {}})))))
