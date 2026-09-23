(ns graphden.packages.registry-shared
  "What the registry package's impls MODULES share (`registry/registry/
   impls.clj` and `registry/marketplace/impls.clj`) and what the boot-time
   starter catalogue (`graphden.packages.starter-catalogue`) shares with
   the publish path. Module impls are evaluated standalone by the package
   loader — one `impls.clj` cannot `require` another — so what both need
   lives here: the remote bearers' origin rule, the moderation deploy flag,
   the platform-wide storage beneath the org-scoped decorator, the
   package-name lock, and the publish INSERT itself (the row a publish
   writes + the UNIQUE-tolerant insert), so a seeded listing is exactly
   the row a `POST /api/marketplace/publish` would have written."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [graphden.packages.records.ids :as ids]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.deploy-config :as deploy-config]
    [graphden.tenancy.context :as tc]
    [graphden.versioning.storage.core :as vs]
    [next.jdbc :as jdbc])
  (:import
    (java.sql
      Connection)))


(defn url-origin
  "`[scheme host port]` of `url` — lower-cased, the scheme's default port
   filled in — or nil when `url` has no scheme or host. Two URLs share an
   origin iff these are equal (RFC 6454)."
  [url]
  (when-let [u (try (java.net.URI. (str url)) (catch Exception _ nil))]
    (let [scheme (some-> (java.net.URI/.getScheme u) str/lower-case)
          host (some-> (java.net.URI/.getHost u) str/lower-case)
          port (java.net.URI/.getPort u)]
      (when (and scheme (not (str/blank? host)))
        [scheme host (if (neg? port) (case scheme "https" 443 "http" 80 port) port)]))))


(defn bearer-for-origin
  "`\"Bearer <token>\"` when `token` is set and `url` has the SAME origin as
   the operator-configured `configured-url`; nil otherwise (no token, no
   configured endpoint, or any other host). A deployment token belongs to
   one endpoint — a dial whose URL a caller chose must never carry it
   elsewhere."
  [url configured-url token]
  (when (and (not (str/blank? (str token)))
             (some? (url-origin configured-url))
             (= (url-origin url) (url-origin configured-url)))
    (str "Bearer " token)))


(def ^:dynamic *secret-env-override*
  "Test seam: when bound to a map, the remote bearers below read their
   env var NAME from it instead of the process environment. Thread-local
   (a `binding`), so a test in the parallel pool can stub the tokens
   without a `with-redefs` that every sibling namespace would see."
  nil)


(defn- secret-env
  [var-name]
  (if-let [o *secret-env-override*]
    (get o var-name)
    (System/getenv var-name)))


(defn registry-token
  "The remote-registry bearer (`GRAPHDEN_REGISTRY_TOKEN`) — a secret, so a
   process-environment read, never a deploy setting."
  []
  (secret-env "GRAPHDEN_REGISTRY_TOKEN"))


(defn hub-token
  "The hub bearer (`GRAPHDEN_HUB_TOKEN`) — a secret, like `registry-token`."
  []
  (secret-env "GRAPHDEN_HUB_TOKEN"))


(defn moderation-enabled?
  "Does this deployment moderate public listings? The public deploy
   setting `GRAPHDEN_MARKETPLACE_MODERATION` (`:marketplace-moderation`),
   truthy when \"1\" / \"true\" / \"yes\"."
  []
  (contains? #{"1" "true" "yes"} (some-> (deploy-config/read-setting :marketplace-moderation)
                                         str str/trim str/lower-case)))


(defn platform-base
  "The storage BENEATH the org-scoped decorator (`vs/unwrap` lands on the
   decorator; its `:base` is the backend). Cross-org reads — the
   operator's moderation queue, the registry-wide name check — go
   through it; the org-scoped view would show only the caller's own org.
   Row level security still applies at the pool (public rows are
   readable by every org — a pending listing is public by intent)."
  [storage]
  (let [s (vs/unwrap storage)]
    (or (:base s) s)))


(def ^:private name-lock-attempts
  "Non-blocking acquire attempts before a publish gives up — 25 ms apart,
   so ~5 s of another publisher holding the same name."
  200)


(defn with-package-name-lock
  "Run `f` holding a cluster-wide advisory lock on package `pkg-name` — the
   publish path's check-then-insert (the version pre-check, the public-name
   holder check, the insert) serialised across every executor, so two orgs
   racing for the same public name cannot both pass the holder check and
   both land (the DB's `(name, version)` key would not catch different
   versions). A SESSION lock on a connection borrowed for the duration, so
   `f`'s own storage calls run on the pool as usual. Acquired with
   `pg_try_advisory_lock` in a short retry loop rather than a blocking
   `pg_advisory_lock`: a blocked waiter would sit on a pool connection, and
   with N publishers of one name that is N connections parked — on a small
   pool a deadlock against the winner's own queries. Released in `finally`
   (and by the server if the session dies). Throws `:packages/name-busy`
   when the name stays held for the whole retry window. Falls through to a
   bare `(f)` when the storage has no pool (a test double)."
  [storage pkg-name f]
  (if-let [ds (:pool (platform-base storage))]
    (let [k (str "package-name:" pkg-name)
          try-lock! (fn [conn]
                      (-> (jdbc/execute-one! conn ["SELECT pg_try_advisory_lock(hashtext(?)::bigint) AS got" k])
                          :got boolean))]
      (loop [attempt 1]
        (let [^Connection conn (jdbc/get-connection ds)
              got? (try (try-lock! conn)
                        (catch Exception e (Connection/.close conn) (throw e)))]
          (if got?
            (try (f)
                 (finally
                   (try (jdbc/execute! conn ["SELECT pg_advisory_unlock(hashtext(?)::bigint)" k])
                        (finally (Connection/.close conn)))))
            (do (Connection/.close conn)
                (if (< attempt name-lock-attempts)
                  (do (Thread/sleep 25) (recur (inc attempt)))
                  (throw (ex-info "Another publish of this package name is in progress."
                                  {:type :packages/name-busy :name pkg-name}))))))))
    (f)))


;; =============================================================================
;; The publish INSERT — shared by the publish route and the starter catalogue
;; =============================================================================

(defn foreign-public-holder
  "The OTHER org that already lists `pkg-name` publicly (any moderation
   status), or nil — a public name is registry-wide, first come first
   served, the way pypi.org names are (docs/MARKETPLACE.md § 2, Names).
   Reads the platform-wide storage: the org-scoped view would hide the
   holder's rows... except its public ones, which is exactly the set that
   matters, so row level security answers the same question for a tenant."
  [storage pkg-name]
  (let [own (str (tc/current-org))]
    (->> (sp/query-entities (platform-base storage) :package-version {:name pkg-name})
         (filter #(and (:public? %) (not= own (str (:org-id %)))))
         first
         :org-id)))


(defn version-row
  "The `:package-version` row a publish writes for `(pkg-name, pkg-version)`
   — the bundle (`:namespace` / `:fns` / `:dependencies` /
   `:package-dependencies` / `:secrets`), the listing (`:kind` /
   `:description` / `:category` / `:tags` / `:payload`; nil = a plain fns
   publish with no metadata) and the write-time normalisations readers
   never re-derive: the content hash, `:public?` (the explicit opt-in OR a
   platform-tier publish — the shared registry), the moderation `:status`
   (a TENANT's public opt-in waits for the operator when the deployment
   moderates; the platform's own and every private publish are listed
   outright, docs/MARKETPLACE.md § 8), `:org-id` (the same value the
   tenancy decorator stamps — set here too so single-tenant rows carry the
   public org instead of NULL) and `:publisher-id` (who is told the
   moderation decision)."
  [pkg-name pkg-version bundle pkg-public listing]
  (let [fns (:fns bundle)]
    {:name pkg-name
     :version pkg-version
     :ns-root (:namespace bundle)
     :fns fns
     :dependencies (:dependencies bundle)
     :package-dependencies (:package-dependencies bundle)
     :secrets (vec (:secrets bundle))
     :content-hash (ids/digest-hex "SHA-256" (json/generate-string fns))
     :org-id (tc/current-org)
     :public? (boolean (or pkg-public (tc/current-platform-tier?)))
     :status (if (and pkg-public
                      (moderation-enabled?)
                      (not (tc/current-platform-tier?)))
               "pending"
               "approved")
     :published-at (java.time.Instant/now)
     :kind (:kind listing)
     :description (:description listing)
     :category (:category listing)
     :tags (some-> (:tags listing) vec)
     :payload (:payload listing)
     :publisher-id (tc/current-user-id)}))


(defn insert-or-exists!
  "The publish INSERT under the DB's `UNIQUE (name, version)`: the created
   row, or nil when the key is already taken — the same answer the
   pre-check gives, so a race or another org's private row (invisible to
   an org-scoped read) ends as `version-exists`, not a 500. A pending row
   (a tenant's public opt-in under moderation) is announced through the
   notification seam so the operator hears about the queue."
  [storage row]
  (try
    (let [created (sp/create-entity storage :package-version row)]
      (when (= "pending" (:status created))
        (tc/notify! :package-submitted created))
      created)
    (catch clojure.lang.ExceptionInfo e
      (when-not (= :unique-violation (:type (ex-data e)))
        (throw e)))))
