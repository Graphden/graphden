(ns support-bundle-test
  (:require
    [babashka.fs :as fs]
    [babashka.process :as p]
    [clojure.string :as str]
    [clojure.test :refer [deftest is run-tests]]
    [support-bundle :as bundle]))


(def sentinel "SYNTHETIC_SUPPORT_SECRET_97")
(def hash-value (str/join (repeat 64 "a")))


(def sources
  {:base-url "https://support.invalid"
   :getenv (constantly sentinel)
   :dotenv-exists? (constantly true)
   :revision (constantly (str/join (repeat 40 "b")))
   :send-http (fn [_]
                {:status 200 :body (str "{\"backend\":\"" hash-value
                                        "\",\"packages\":\"" sentinel "\",\"password\":\"" sentinel "\"}")})})


(deftest collection-retains-only-safe-metadata
  (let [summary (bundle/collect-summary sources)]
    (is (not (str/includes? (pr-str summary) sentinel)))
    (is (= 200 (get-in summary [:health :status])))
    (is (= {:backend hash-value} (get-in summary [:version :hashes])))
    (is (true? (get-in summary [:configuration :dotenv-present])))
    (is (every? true? (vals (get-in summary [:configuration :environment-present]))))))


(deftest transport-exceptions-and-malformed-status-never-cross-boundary
  (doseq [send-http [(fn [_]
                       (throw (ex-info sentinel {:request {:authorization sentinel}}
                                       (Exception. sentinel))))
                     (constantly {:status sentinel :body sentinel})]]
    (let [summary (bundle/collect-summary (assoc sources :send-http send-http :revision #(throw (Exception. sentinel))))]
      (is (not (str/includes? (pr-str summary) sentinel)))
      (is (nil? (:revision summary)))
      (is (not= 200 (get-in summary [:health :status]))))))


(deftest credential-bearing-url-is-rejected-without-echo-or-cause
  (doseq [url [(str "https://user:" sentinel "@support.invalid")
               (str "https://support.invalid?token=" sentinel)
               (str "https://support.invalid/#" sentinel)
               (str "not a url " sentinel)]]
    (let [error (try (bundle/collect-summary (assoc sources :base-url url)) nil
                     (catch Exception e e))]
      (is (some? error))
      (is (nil? (ex-cause error)))
      (is (not (str/includes? (with-out-str (Throwable/.printStackTrace error (java.io.PrintWriter. *out*))) sentinel))))))


(deftest actual-archive-contains-only-the-validated-summary
  (let [dir (fs/create-temp-dir {:prefix "safe-support-test-"})
        archive (str dir ".tgz")
        collect bundle/collect-summary]
    (try
      (with-redefs [bundle/collect-summary (fn [_] (collect sources))
                    fs/create-temp-dir (constantly dir)]
        (let [output (with-out-str (bundle/-main "https://support.invalid"))
              summary (slurp (str (fs/path dir "summary.edn")))
              archived (:out (p/sh {:out :string :err :string}
                                   "tar" "-xOf" archive (str (fs/file-name dir) "/summary.edn")))]
          (is (= summary archived))
          (is (not (str/includes? (str output summary archived) sentinel)))
          (is (= ["summary.edn"] (mapv (comp str fs/file-name) (fs/list-dir dir))))))
      (finally (fs/delete-tree dir) (fs/delete-if-exists archive)))))


(let [{:keys [fail error]} (run-tests 'support-bundle-test)]
  (when (pos? (+ fail error)) (System/exit 1)))
