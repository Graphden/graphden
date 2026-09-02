(ns graphden.system.deploy-config-test
  "The boot snapshot of PUBLIC deployment settings — the process-global
   atom `graphden.system.deploy-config` + its `:exec/deploy-config`
   init-key. Pins the security-relevant contract: only DECLARED keys
   exist, blanks read as unset, a non-keyword key fails boot, and a
   non-keyword read gets nothing."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.system.deploy-config :as dc]
    [graphden.system.init.exec]
    [integrant.core :as ig]))


(use-fixtures :each
  (fn [f]
    (dc/clear!)
    (try (f) (finally (dc/clear!)))))


(deftest snapshot-lifecycle
  (testing "nothing installed ⇒ every read is nil (test bootstraps never run the init-key)"
    (is (nil? (dc/read-setting :hub-url)))
    (is (= #{} (dc/declared-keys))))
  (testing "declared keys read back; blank values normalise to nil (unset ≡ empty)"
    (dc/install! {:hub-url "https://hub.example" :feedback-url "" :feedback-intake nil})
    (is (= "https://hub.example" (dc/read-setting :hub-url)))
    (is (nil? (dc/read-setting :feedback-url)))
    (is (nil? (dc/read-setting :feedback-intake)))
    (is (= #{:hub-url :feedback-url :feedback-intake} (dc/declared-keys))))
  (testing "an undeclared key is simply absent — the snapshot is not a door to the environment"
    (is (nil? (dc/read-setting :path)))
    (is (nil? (dc/read-setting :GRAPHDEN_ALERT_TELEGRAM_TOKEN))))
  (testing "only a keyword reads; a string / nil shape gets nothing"
    (is (nil? (dc/read-setting "hub-url")))
    (is (nil? (dc/read-setting nil))))
  (testing "clear! empties it again"
    (dc/clear!)
    (is (nil? (dc/read-setting :hub-url)))))


(deftest install-rejects-non-keyword-keys
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"keys must be keywords"
        (dc/install! {"hub-url" "x"}))))


(deftest init-key-installs-and-halt-clears
  (let [v (ig/init-key :exec/deploy-config {:settings {:hub-url "https://hub.example"}})]
    (is (= :ok v))
    (is (= "https://hub.example" (dc/read-setting :hub-url)))
    (ig/halt-key! :exec/deploy-config v)
    (is (nil? (dc/read-setting :hub-url)))))
