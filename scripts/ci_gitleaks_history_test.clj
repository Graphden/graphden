(ns ci-gitleaks-history-test
  (:require
    [babashka.fs :as fs]
    [babashka.process :as p]
    [ci-gitleaks-history :as history]
    [clojure.string :as str]
    [clojure.test :refer [deftest is run-tests]]))


(def head-a (str/join (repeat 40 "a")))
(def head-b (str/join (repeat 40 "b")))


(def proof
  {:schema history/schema :result :passed :head head-a :tips [head-a]
   :config "config-a" :ignore nil :image-id "image-a"
   :shallow nil :grafts nil :replace-refs ""})


(def current
  {:head head-b :tips [head-a head-b] :config "config-a" :ignore nil
   :image-id "image-a" :shallow nil :grafts nil :replace-refs ""})


(deftest reuse-requires-identical-inputs-and-covered-refs
  (is (= {:mode :reuse}
         (history/plan proof (assoc current :head head-a :tips [head-a]))))
  (is (= {:mode :incremental :base-tips [head-a]}
         (history/plan proof current)))
  (doseq [changed [(assoc current :config "changed")
                   (assoc current :ignore "changed")
                   (assoc current :image-id "changed")
                   (assoc current :shallow "changed")
                   (assoc current :grafts "changed")
                   (assoc current :replace-refs "changed")]]
    (is (= {:mode :full} (history/plan proof changed))))
  (doseq [invalid [(assoc proof :result :failed)
                   (assoc proof :head "not-a-sha")
                   (assoc proof :schema 0)
                   (assoc proof :tips ["invalid"])
                   nil]]
    (is (= {:mode :full} (history/plan invalid current)))))


(defn git!
  [repo & args]
  (apply p/shell {:out :string} "git" "-C" repo args))


(deftest new-secret-failure-does-not-advance-proof-and-config-change-invalidates
  (let [repo (str (fs/create-temp-dir {:prefix "gitleaks-history-test-"}))
        cache (str repo "/.git/" history/cache-file)
        calls (atom [])
        original-inputs history/inputs]
    (try
      (git! repo "init" "-q" "-b" "develop")
      (git! repo "config" "user.email" "test@example.invalid")
      (git! repo "config" "user.name" "test")
      (spit (str repo "/.gitleaks.toml") "clean")
      (git! repo "add" ".")
      (git! repo "commit" "-qm" "base")
      (with-redefs [history/inputs (fn [path common]
                                     (assoc (original-inputs path common) :image-id "image-a"))
                    history/run-gitleaks!
                    (fn [path _ log-opts]
                      (swap! calls conj log-opts)
                      (let [args (into ["log" "-p"]
                                       (if log-opts (str/split log-opts #" ") ["--all" "HEAD"]))
                            output (:out (apply git! path args))]
                        (when (and (str/includes? output "SECRET_SHOULD_FAIL")
                                   (not (str/includes? (slurp (str path "/.gitleaks.toml")) "allow")))
                          (throw (ex-info "synthetic secret" {})))))]
        (history/scan-repo! repo (str repo "/.git"))
        (is (= [nil] @calls))
        (let [base (:head (history/read-cache cache))]
          (history/scan-repo! repo (str repo "/.git"))
          (is (= [nil] @calls))
          (git! repo "checkout" "-q" "-b" "other")
          (spit (str repo "/secret.txt") "SECRET_SHOULD_FAIL")
          (git! repo "add" ".")
          (git! repo "commit" "-qm" "secret")
          (git! repo "checkout" "-q" "develop")
          (is (thrown? Exception (history/scan-repo! repo (str repo "/.git"))))
          (is (= base (:head (history/read-cache cache))))
          (is (str/includes? (last @calls) (str "--all HEAD --not " base)))
          (spit (str repo "/.gitleaks.toml") "allow")
          (history/scan-repo! repo (str repo "/.git"))
          (is (nil? (last @calls)))
          (is (= (:tips (history/inputs repo (str repo "/.git")))
                 (:tips (history/read-cache cache))))))
      (finally
        (fs/delete-tree repo)))))


(deftest only-ref-churn-retries-without-publishing-an-unstable-proof
  (let [changed (update current :tips conj (str/join (repeat 40 "c")))
        config-changed (assoc current :config "other-config")]
    (doseq [[snapshots attempts expected]
            [[[current changed changed] 2 [changed]]
             [[current changed current] 2 []]
             [[current config-changed] 1 []]
             [[current changed config-changed] 2 []]]]
      (let [observations (atom snapshots)
            scans (atom 0)
            published (atom [])]
        (with-redefs [history/inputs (fn [& _]
                                      (let [snapshot (first @observations)]
                                        (swap! observations subvec 1)
                                        snapshot))
                      history/read-cache (constantly proof)
                      history/run-gitleaks! (fn [& _] (swap! scans inc))
                      history/write-cache! (fn [_ value] (swap! published conj (dissoc value :schema :result)))]
          (if (seq expected)
            (history/scan-repo! "/unused" "/unused")
            (is (thrown? clojure.lang.ExceptionInfo (history/scan-repo! "/unused" "/unused"))))
          (is (= attempts @scans))
          (is (= expected @published)))))))


(let [result (run-tests 'ci-gitleaks-history-test)]
  (when (pos? (+ (:fail result) (:error result)))
    (System/exit 1)))
