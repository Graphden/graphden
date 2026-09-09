(ns graphden.packages.registry-shared
  "The three conventions the registry package's impls MODULES share
   (`registry/registry/impls.clj` and `registry/marketplace/impls.clj`).
   Module impls are evaluated standalone by the package loader — one
   `impls.clj` cannot `require` another — so what both need lives here:
   the remote registry's bearer, the moderation deploy flag and the
   platform-wide storage beneath the org-scoped decorator."
  (:require
    [clojure.string :as str]
    [graphden.system.deploy-config :as deploy-config]
    [graphden.versioning.storage.core :as vs]
    [next.jdbc :as jdbc])
  (:import
    (java.sql
      Connection)))


(defn remote-auth-headers
  "Headers for a dial to the configured remote registry — the
   `GRAPHDEN_REGISTRY_TOKEN` bearer when one is set, else none."
  []
  (let [token (System/getenv "GRAPHDEN_REGISTRY_TOKEN")]
    (cond-> {} (seq (str token)) (assoc "Authorization" (str "Bearer " token)))))


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
