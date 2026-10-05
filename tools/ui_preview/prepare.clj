(ns tools.ui-preview.prepare
  "Create a fresh editable preview using the existing ordinary graph import."
  (:require
    [babashka.http-client :as http]
    [cheshire.core :as json]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [graphden.packages.records.ids :as ids]))


(defn- request
  ([url method path body]
   (request url method path body "application/edn"))
  ([url method path body content-type]
   (let [token (System/getenv "AUTH_TOKEN")
         response (http/request
                    {:uri (str url path) :method method :throw false
                     :headers (cond-> {"Content-Type" content-type}
                                (seq token) (assoc "Authorization" (str "Bearer " token)))
                     :body body :timeout 60000})
         data (json/parse-string (:body response) true)]
     ;; Header presence, even an empty capability list, is the editor's
     ;; existing tenancy signal. Refuse before importing any graph data.
     (when (some #(= "x-graphden-capabilities" (str/lower-case (name %)))
                 (keys (:headers response)))
       (throw (ex-info "Preview preparation is limited to self-hosted installations" {})))
     (when (or (not (<= 200 (:status response) 299)) (false? (:ok data))
               (= "failed" (:status data)))
       (throw (ex-info "Preview preparation request failed"
                       {:status (:status response) :reason (:reason data) :path path
                        :execution-error (select-keys (:error-data data) [:reason :fn-id :path :type])})))
     data)))


(defn- prepare
  [url branch root account-menu?]
  (when-not (and (seq branch)
                 (re-matches #"[a-zA-Z][a-zA-Z0-9_-]*(?:\.[a-zA-Z][a-zA-Z0-9_-]*)*" root))
    (throw (ex-info "A nonempty branch and a dotted graph namespace are required" {})))
  (when (some #(= branch (:name %)) (:branches (request url :get "/api/branches" nil)))
    (throw (ex-info "Choose a new branch; existing preview edits are never overwritten" {})))
  ;; The authenticated reads above/below check capabilities and installation.
  ;; Package-authored graph export is not a prerequisite for ordinary copies.
  (when-not (some #(= (str (ids/fn-id "app.ui-preview" :browser-plan-export)) (:id %))
                  (:fns (request url :get "/api/graph/entities?scope=index" nil)))
    (throw (ex-info "The running prototype lacks browser-plan-export; rebuild this worktree first" {})))
  (let [graph-namespace (str root ".review_" (subs (str (random-uuid)) 0 8))
        project (java.io.File/.getParentFile (java.io.File/.getParentFile (java.io.File/.getParentFile (java.io.File/.getCanonicalFile (io/file *file*)))))
        module-name (if account-menu? "ui-account-menu" "ui-preview")
        module (edn/read-string (slurp (io/file project "resources/packages/app" module-name "fns.edn")))
        pure (take-while #(not= :_ui-preview-export (:name %)) (:fns module))
        entries (if account-menu?
                  {:initial :account-menu-initial :update :account-menu-update :view :account-menu-view}
                  {:initial :menu-initial :update :menu-update :view :menu-view})
        expected (into {} (map (fn [[entry n]] [entry (str (ids/fn-id graph-namespace n))])) entries)
        definitions (cond-> (mapv #(assoc % :namespace graph-namespace) pure)
                      (not account-menu?)
                      (conj {:name :browser-plan :namespace graph-namespace
                             :parent :app.ui-preview/browser-plan-export :args entries}))
        encoded-branch (java.net.URLEncoder/encode branch "UTF-8")
        query (str "branch=" encoded-branch "&initial=" (:initial expected)
                   "&update=" (:update expected) "&view=" (:view expected))
        plan-id (str (ids/fn-id graph-namespace :browser-plan))
        result (request url :post (str "/api/import/graph?target=" encoded-branch "&create=true")
                        (pr-str {:fns definitions}))]
    (try
      (when (seq (:skipped-owned result))
        (throw (ex-info "Preview import unexpectedly referenced package-owned identities" {})))
      (let [plan (if account-menu?
                   (request url :get (str "/ui-preview/plan?" query) nil)
                   (:result (request url :post (str "/api/execute?branch=" encoded-branch)
                                     (json/generate-string {:fn-id plan-id :args {} :timeout-ms 15000})
                                     "application/json")))]
        (when-not (= expected (:entries plan))
          (throw (ex-info "Preview entry functions do not resolve to the editable copy" {}))))
      (cond-> {:branch branch :namespace graph-namespace :entries expected
               :url (if account-menu?
                      (str url "/?branch=" encoded-branch
                           "&ui-initial=" (:initial expected) "&ui-update=" (:update expected)
                           "&ui-view=" (:view expected) "#" graph-namespace ".theme-canvas-background")
                      (str url "/ui-preview?branch=" encoded-branch
                           "&graph=" graph-namespace "&plan=" plan-id))}
        (not account-menu?) (assoc :plan-id plan-id))
      (catch Exception error
        (try (request url :delete (str "/api/branches/" encoded-branch) nil)
             (catch Exception cleanup-error
               (throw (ex-info "Preview preparation failed; remove its temporary branch manually"
                               {:branch branch :cleanup-status (:status (ex-data cleanup-error))} error))))
        (throw error)))))


(try
  (let [account-menu? (boolean (some #{"--account-menu"} *command-line-args*))
        args (remove #{"--account-menu"} *command-line-args*)
        [url branch root] args]
    (when-not (and (= 3 (count args)) url branch root)
      (throw (ex-info "Usage: bb -cp src tools/ui_preview/prepare.clj [--account-menu] <url> <new-branch> <namespace-root>" {})))
    (println (json/generate-string (prepare url branch root account-menu?))))
  (catch Exception error
    (binding [*out* *err*] (println (Throwable/.getMessage error) (ex-data error)))
    (System/exit 1)))
