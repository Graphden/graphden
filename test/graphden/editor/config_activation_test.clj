(ns ^:serial graphden.editor.config-activation-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is]]
    [graphden.editor.components :as components]
    [graphden.editor.config-activation :as activation]
    [graphden.editor.preference-write :as preferences]
    [graphden.executor.browser-snapshot :as snapshot]
    [graphden.executor.browser-source :as source]
    [graphden.storage.graph-writer :as writer]
    [graphden.storage.tx :as tx]))


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


(deftest preview-keeps-policy-and-epoch-guards-on-the-decorated-read-snapshot
  (let [storage (Object.) fixed (Object.) policy {:frozen :policy}
        epoch (atom 0) ctx {:storage storage :invalidation-count epoch}
        calls (atom [])]
    (with-redefs [snapshot/capture-context-policy
                  (fn [actual]
                    (is (identical? ctx actual))
                    (swap! calls conj :policy)
                    policy)
                  tx/assert-owns-commit!
                  (fn [actual]
                    (is (identical? storage actual))
                    (swap! calls conj :owned))
                  source/with-snapshot
                  (fn [actual consume]
                    (is (identical? storage actual))
                    (swap! calls conj :snapshot)
                    (consume fixed (constantly nil)))
                  components/assert-current-policy!
                  (fn [actual]
                    (is (identical? fixed actual))
                    (swap! calls conj :current-policy))
                  writer/call-with-write
                  (fn [& _] (throw (IllegalStateException. "Preview acquired a graph writer")))
                  preferences/call-with-lock
                  (fn [& _] (throw (IllegalStateException. "Preview acquired a preference lock")))]
      (let [result (activation/call-with-preview-snapshot
                     ctx (fn [actual frozen]
                           (is (identical? fixed actual))
                           (is (identical? policy frozen))
                           (swap! calls conj :operation)
                           :proposal))]
        (is (= :proposal result))
        (is (= [:policy :owned :snapshot :current-policy :operation] @calls)))
      (let [refusal (try
                      (activation/call-with-preview-snapshot
                        ctx (fn [_ _] (swap! epoch inc) :stale-proposal))
                      nil
                      (catch clojure.lang.ExceptionInfo error (:reason (ex-data error))))]
        (is (= :policy-refresh-required refusal))))))
