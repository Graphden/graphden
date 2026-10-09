(ns support-bundle
  "Support artifacts contain only validated version/status metadata and config
   presence. No dotenv contents, environment values, logs or exception causes."
  (:require
    [babashka.fs :as fs]
    [babashka.http-client :as http]
    [babashka.process :as p]
    [cheshire.core :as json]
    [clojure.string :as str]))


(defn- http-status
  [x]
  (when (and (integer? x) (<= 100 x 599)) x))


(defn- digest
  [x]
  (when (and (string? x) (re-matches #"[0-9a-f]{40,64}" x)) x))


(defn- probe
  [send-http url version?]
  (try
    (let [{:keys [status body]} (send-http url)
          status (http-status status)
          version (when (and version? (= 200 status))
                    (try (json/parse-string body true) (catch Exception _ nil)))]
      (cond-> {:status status}
        version? (assoc :hashes (into {} (keep (fn [k]
                                                 (when-let [v (digest (get version k))]
                                                   [k v])))
                                      [:frontend :backend :packages]))))
    (catch Exception _ {:kind :transport})))


(defn collect-summary
  "Injected sources make the actual collection boundary testable without
   credentials, network or Docker. Never includes arbitrary source strings."
  [{:keys [base-url send-http getenv dotenv-exists? revision]}]
  (let [uri (try (java.net.URI. base-url) (catch Exception _ nil))]
    (when-not (and uri (#{"http" "https"} (java.net.URI/.getScheme uri)) (java.net.URI/.getHost uri)
                   (nil? (java.net.URI/.getUserInfo uri)) (nil? (java.net.URI/.getQuery uri)) (nil? (java.net.URI/.getFragment uri)))
      (throw (ex-info "Support URL requires HTTP(S) without credentials, query or fragment" {})))
    (let [base (str/replace base-url #"/$" "")]
      {:format 1
       :revision (try (digest (str/trim (revision))) (catch Exception _ nil))
       :health (probe send-http (str base "/health") false)
       :version (probe send-http (str base "/version") true)
       :configuration {:dotenv-present (boolean (dotenv-exists?))
                       :environment-present
                       (into {} (map (fn [k] [k (some? (getenv k))]))
                             ["AUTH_TOKEN" "GRAPHDEN_SESSION_COOKIE" "GRAPHDEN_LICENSE_KEY"
                              "GD_INSTANCE" "GD_PORT_HTTP" "JDBC_URL" "VAULT_ADDR" "VAULT_TOKEN"])}})))


(defn -main
  [& [base-url]]
  (let [summary (collect-summary
                  {:base-url (or base-url "http://localhost:9002")
                   :send-http #(http/get % {:throw false :timeout 5000 :follow-redirects false})
                   :getenv #(System/getenv %)
                   :dotenv-exists? #(fs/exists? ".env")
                   :revision #(let [r (p/sh {:out :string :err :string :continue true}
                                            "git" "rev-parse" "HEAD")]
                                (when (zero? (:exit r)) (:out r)))})
        dir (fs/create-temp-dir {:dir (str (fs/create-dirs "target")) :prefix "support-bundle-"})
        file (fs/path dir "summary.edn")
        archive (str dir ".tgz")]
    (fs/set-posix-file-permissions dir "rwx------")
    (spit (str file) (pr-str summary))
    (fs/set-posix-file-permissions file "rw-------")
    (let [r (p/sh {:out :string :err :string :continue true}
                  "tar" "-czf" archive "-C" (str (fs/parent dir)) (str (fs/file-name dir)))]
      (when-not (zero? (:exit r))
        (throw (ex-info "Support archive creation failed" {}))))
    (fs/set-posix-file-permissions archive "rw-------")
    (println "support bundle:" archive)))
