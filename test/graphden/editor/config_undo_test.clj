(ns graphden.editor.config-undo-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is]]
    [graphden.editor.config-undo :as undo]))


(deftest undo-receipt-is-lifo-expected-state-not-a-delete-sweep
  (let [id (random-uuid) previous-id (random-uuid) namespace-id (random-uuid)
        revision (str/join (repeat 64 "a"))
        receipt {:id id :name "editor" :namespace-id namespace-id}
        previous {:id previous-id :name "_editor-prior-fixture" :namespace-id namespace-id :revision revision}
        input {:current-id id :expected-configuration-revision revision :cohort-revision revision
               :previous previous :functions [receipt]}]
    (is (= input (undo/parse-command input)))
    (is (nil? (:previous (undo/parse-command (assoc input :previous nil)))))
    (doseq [invalid [nil "undo" 1 (dissoc input :previous) (assoc input :previous false)
                     (assoc input :expected-configuration-revision "old")
                     (dissoc input :cohort-revision)
                     (assoc-in input [:previous :revision] "old")
                     (assoc-in input [:previous :id] id)
                     (assoc input :current-id previous-id)
                     (assoc input :functions [receipt (assoc receipt :id previous-id)])]]
      (is (= :invalid-request
             (try (undo/parse-command invalid) nil
                  (catch clojure.lang.ExceptionInfo error (:reason (ex-data error)))))))))
