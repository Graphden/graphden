(ns graphden.editor.config-receipts-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is]]
    [graphden.editor.config-receipts :as receipts]))


(deftest undo-confirmation-requires-original-scoped-postcondition-witness
  (let [id (random-uuid) namespace-id (random-uuid) revision (str/join (repeat 64 "a"))
        receipt {:current-id id :expected-configuration-revision revision :cohort-revision revision
                 :previous nil :functions [{:id id :name "editor" :namespace-id namespace-id}]}
        input {:receipt receipt :scope {:principal-id "operator" :org "public" :branch-id (str (random-uuid))}
               :restore-revision revision}]
    (is (= input (receipts/parse-undo-confirmation input)))
    (doseq [invalid [nil false 1 "confirmation" (dissoc input :restore-revision)
                     (assoc input :restore-revision "observed-later")
                     (assoc input :current-id id)
                     (assoc-in input [:scope :branch-id] "main")
                     (assoc-in input [:scope :principal-id] nil)
                     (assoc input :receipt false)]]
      (is (thrown? clojure.lang.ExceptionInfo (receipts/parse-undo-confirmation invalid))))))
