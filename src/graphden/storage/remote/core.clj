(ns graphden.storage.remote.core
  "RemoteStorage — a READ-ONLY, in-memory storage leaf for an external / BYO
   executor (docs/SCALING.md § external executor).

   A customer runs the executor on their OWN hardware, but the graph lives in
   the platform's Postgres. This storage bootstraps the whole graph over HTTP
   (`GET /api/export/graph-rows`, authenticated as the org → org + public
   rows) into memory, and answers the executor's compile + execute reads from
   there — NO Postgres connection, NO per-read network round-trip on the hot
   path (matching the `compiled-registry` model where every pod holds its
   whole shard).

   Read surface (what the executor actually calls): `query-entities` (with
   `{}` / `{:name v}` / `{:id [ids]}` / `{:fn-id [ids]}` shapes), `read-entity`,
   `read-entities`. Writes THROW — this is a serve-the-graph executor, not an
   authoring one. The FaaS app path (`app_router` → `cr/execute`) is read-only
   and works; the `/api/execute` persistence path (`:fn-execution` rows) is a
   hosted-editor concern and is NOT supported here (see SCALING.md).

   Freshness: `refresh!` re-fetches the bundle; the SSE invalidation source
   (docs/SCALING.md § SSE) calls it when the hub signals a graph change.

   Branch: a RemoteStorage is pinned to ONE branch (nil ⇒ main), sent as the
   `X-Graphden-Branch` header on every fetch — so a BYO executor can serve
   prod or dev. Multiple branches on one BYO executor = multiple RemoteStorages
   (out of scope for the single-org serve case)."
  (:require
    [clojure.edn :as edn]
    [clojure.tools.logging :as log]
    [graphden.packages.records.wire :as wire]
    [graphden.storage.protocol.core :as sp]
    [org.httpkit.client :as http]))


;; =============================================================================
;; Bundle fetch — GET /api/export/graph-rows
;; =============================================================================

(def ^:private bundle-key->entity
  "The export bundle's plural keys → entity-name keywords used in queries."
  {:fns :fn
   :slots :slot
   :fn-slots :fn-slot
   :bindings :binding
   :list-items :binding-list-item})


(defn fetch-graph-rows
  "GET the raw five-table bundle from `hub-url` (authenticated with `token`)
   and return it keyed by ENTITY NAME: `{:fn [...] :slot [...] :fn-slot [...]
   :binding [...] :binding-list-item [...]}`. Throws on a transport error or
   non-200. `edn/read-string` (not `read`) parses the body — safe, no eval;
   `#uuid` / `#inst` are native edn tags, `#graphden/ref` decodes via
   `records.wire`.

   `branch` (nil ⇒ the org's main branch) pins the bootstrap to one branch via
   the same `X-Graphden-Branch` header the editor uses — so a BYO executor can
   serve prod or dev, not only main."
  ([hub-url token] (fetch-graph-rows hub-url token nil))
  ([hub-url token branch]
   (let [url (str hub-url "/api/export/graph-rows")
         headers (cond-> {"Authorization" (str "Bearer " token)}
                   branch (assoc "X-Graphden-Branch" branch))
         resp @(http/get url {:headers headers :timeout 30000 :as :text})]
     (when-let [err (:error resp)]
       (throw (ex-info (str "RemoteStorage bootstrap GET failed: " (Throwable/.getMessage err))
                       {:type :remote-storage/fetch-failed :url url})))
     (when (not= 200 (:status resp))
       (throw (ex-info (str "RemoteStorage bootstrap GET returned " (:status resp))
                       {:type :remote-storage/fetch-failed :url url :status (:status resp)})))
     (let [bundle (edn/read-string {:readers wire/wire-readers} (:body resp))]
       (reduce-kv (fn [m bkey entity] (assoc m entity (vec (get bundle bkey))))
                  {}
                  bundle-key->entity)))))


;; =============================================================================
;; In-memory query matching
;; =============================================================================

(defn- matches?
  "Row matches `where` iff every `[k v]` matches: a vector/set `v` is a
   membership test (`{:id [ids]}` / `{:fn-id [ids]}`), any other `v` is
   equality (`{:name x}`). Covers every where-shape the executor emits."
  [where row]
  (every? (fn [[k v]]
            (if (or (vector? v) (set? v))
              (contains? (set v) (get row k))
              (= v (get row k))))
          where))


(defn- query-rows
  [rows entity where]
  (filterv #(matches? where %) (get rows entity)))


(defn- read-only!
  [op]
  (throw (ex-info (str "RemoteStorage is read-only — " op " is not supported. "
                       "A BYO executor serves the graph; authoring stays on the hub.")
                  {:type :remote-storage/read-only :op op})))


;; =============================================================================
;; The storage leaf
;;
;; `rows` is an atom of `{entity-name → [row …]}` so `refresh!` can swap in a
;; fresh bundle without rebuilding the record. Reads deref it per call.
;; =============================================================================

(defrecord RemoteStorage
  [rows hub-url token branch]

  sp/StorageCRUD

  (create-entity [_ _ _] (read-only! "create-entity"))


  (read-entity [_ entity id] (first (query-rows @rows entity {:id id})))


  (update-entity [_ _ _ _] (read-only! "update-entity"))


  (delete-entity [_ _ _] (read-only! "delete-entity"))


  (query-entities [_ entity where] (query-rows @rows entity where))


  (query-entities
    [this entity where opts]
    ;; RemoteStorage holds only the compile-time graph tables, all
    ;; non-versioned; `:limit` is the only opt the executor uses. Order/offset
    ;; aren't emitted against these tables, so limit-after-filter is exact.
    (let [res (sp/query-entities this entity where)]
      (if-let [lim (:limit opts)] (vec (take lim res)) res)))


  (query-latest-per-group
    [_ entity where group-cols]
    ;; In-memory grouping (the protocol says the contract is the result, not
    ;; the mechanism). Latest per group-cols tuple by :created-at. `:created-at`
    ;; is a `#inst` (java.util.Date), so compare by `inst-ms` — `max-key` over
    ;; a raw Date-vs-0 mix would throw ClassCastException.
    (->> (query-rows @rows entity where)
         (group-by (apply juxt group-cols))
         (vals)
         (mapv (fn [group]
                 (apply max-key #(if-let [c (:created-at %)] (inst-ms c) 0) group)))))


  sp/StorageBatchCRUD

  (create-entities [_ _ _] (read-only! "create-entities"))


  (read-entities [_ entity ids] (query-rows @rows entity {:id (vec ids)}))


  (update-entities [_ _ _] (read-only! "update-entities"))


  (upsert-entities [_ _ _] (read-only! "upsert-entities"))


  (delete-entities [_ _ _] (read-only! "delete-entities"))


  (query-ref-many-owners [_ _ _ _] (read-only! "query-ref-many-owners"))


  sp/ExecutionGraph

  ;; Declared to satisfy `executor.context/validate-context-options!`
  ;; (`satisfies? ExecutionGraph`). The compiled-registry path never calls it
  ;; — it reads the tables via `query-entities` and does the BFS in-process.
  (resolve-execution-graph
    [_ _]
    (throw (ex-info "RemoteStorage does not resolve-execution-graph — the compiled executor reads the tables directly"
                    {:type :remote-storage/unsupported}))))


;; =============================================================================
;; Construction + refresh
;; =============================================================================

(defn from-bundle
  "Build a RemoteStorage from an already-fetched raw-rows `bundle` (the plural-
   keyed export shape `{:fns … :slots … :fn-slots … :bindings … :list-items …}`
   OR the entity-keyed shape). No HTTP — used by tests and by
   `create-remote-storage` after the fetch. `hub-url`/`token` are kept so
   `refresh!` can re-fetch (nil ⇒ refresh is a no-op-able snapshot)."
  ([bundle] (from-bundle bundle nil nil nil))
  ([bundle hub-url token] (from-bundle bundle hub-url token nil))
  ([bundle hub-url token branch]
   (let [rows (if (contains? bundle :fns)
                (reduce-kv (fn [m bkey entity] (assoc m entity (vec (get bundle bkey))))
                           {}
                           bundle-key->entity)
                bundle)]
     (->RemoteStorage (atom rows) hub-url token branch))))


(defn create-remote-storage
  "Bootstrap a RemoteStorage: fetch the whole graph from `hub-url` (org +
   public, authenticated with `token`) into memory. `hub-url` is the platform
   base URL, e.g. \"https://acme.graphden.app\". `branch` (nil ⇒ main) pins it
   to one branch — `refresh!` re-fetches the same branch."
  ([hub-url token] (create-remote-storage hub-url token nil))
  ([hub-url token branch]
   (let [rows (fetch-graph-rows hub-url token branch)]
     (log/info "RemoteStorage bootstrapped"
               {:hub hub-url :branch branch
                :counts (into {} (map (fn [[k v]] [k (count v)])) rows)})
     (->RemoteStorage (atom rows) hub-url token branch))))


(defn refresh!
  "Re-fetch the whole graph (same branch) and swap it in. Called by the SSE
   invalidation source when the hub signals a change. Best-effort: on a fetch
   error the previous snapshot is kept and the error logged, so a transient
   blip doesn't blank the executor's graph."
  [{:keys [rows hub-url token branch]}]
  (try
    (reset! rows (fetch-graph-rows hub-url token branch))
    (log/info "RemoteStorage refreshed" {:hub hub-url :branch branch})
    true
    (catch Exception e
      (log/warn e "RemoteStorage refresh failed — keeping the previous snapshot"
                {:hub hub-url})
      false)))
