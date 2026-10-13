(ns graphden.editor.config-activation-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is]]
    [graphden.editor.config-activation :as activation]))


(deftest activation-command-requires-client-root-and-preference-expectations
  (let [id (random-uuid)
        command {:selection-id (str id)
                 :expected-preference {:present false :value nil}
                 :expected-configuration-revision (str/join (repeat 64 "a"))}]
    (is (= (assoc command :selection-id id) (activation/parse-command command)))
    (doseq [input [nil 42 "editor" [] {}
                   (dissoc command :expected-preference)
                   (dissoc command :expected-configuration-revision)
                   (assoc command :selection-id "not-an-identity")
                   (assoc command :selection-id {})
                   (assoc command :expected-preference nil)
                   (assoc command :expected-preference {:present "false" :value nil})
                   (assoc command :expected-preference {:present false :value nil :root true})
                   (assoc command :expected-configuration-revision "fresh")
                   (assoc command :extra true)]]
      (is (= :invalid-request
             (try (activation/parse-command input) nil
                  (catch clojure.lang.ExceptionInfo error (:reason (ex-data error)))))))))


(deftest proposal-input-is-a-finite-expected-state-envelope
  (let [revision (str/join (repeat 64 "a")) nonce (random-uuid)
        command {:component "fn-picker" :expected-preference {:present false :value nil}
                 :expected-configuration-revision revision}
        preview {:operation "personalize" :command command}
        proposal (assoc preview :nonce (str nonce) :expected-proposal revision
                        :scope {:principal-id "owner" :org "example" :branch-id (str (random-uuid))})]
    (is (= :fn-picker (get-in (activation/parse-preview-command preview) [:command :component])))
    (is (= nonce (:nonce (activation/parse-proposal-command proposal))))
    (doseq [input [nil 1 "proposal" (assoc preview :operation "execute")
                   (assoc preview :operation {}) (assoc-in preview [:command :component] {})
                   (assoc-in preview [:command :roots] {:view (str (random-uuid))})]]
      (is (= :invalid-request
             (try (activation/parse-preview-command input) nil
                  (catch clojure.lang.ExceptionInfo error (:reason (ex-data error)))))))
    (doseq [input [nil 1 "proposal" (dissoc proposal :nonce) (assoc proposal :nonce {})
                   (assoc proposal :expected-proposal "fresh") (assoc proposal :scope false)
                   (assoc-in proposal [:scope :branch-id] 1) (assoc-in proposal [:scope :org] "")
                   (assoc-in proposal [:scope :extra] true) (assoc proposal :bundle {})]]
      (is (= :invalid-request
             (try (activation/parse-proposal-command input) nil
                  (catch clojure.lang.ExceptionInfo error (:reason (ex-data error)))))))))
