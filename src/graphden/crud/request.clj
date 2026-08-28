(ns graphden.crud.request
  "Request / URI parsing helpers for the web/crud base functions.

   Leaf namespace — no other `graphden.crud.*` dependency. Holds the
   pure HTTP-boundary plumbing the CRUD impls share: query-string and
   URI-segment parsing, entity-type coercion, storage extraction, and
   the JSON / UUID coercion helpers."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]))


(defn safe-url-decode
  "URL-decode `s`, failing soft to the raw string on malformed
   percent-encoding. `URLDecoder/decode` throws IllegalArgumentException
   on a lone `%` / `%zz`; this runs on UNTRUSTED query-string + form-body
   input, so an uncaught throw would 500 the endpoint."
  ^String [^String s]
  (try (java.net.URLDecoder/decode s "UTF-8")
       (catch IllegalArgumentException _ s)))


(defn parse-query-string
  [s]
  ;; URL-decode BOTH keys and values. Without key-decoding, fields
  ;; like `:enabled?` arrive as `enabled%3F` from URLSearchParams-
  ;; encoded bodies (browser default) and the parser silently fails to
  ;; bind the field — caller's downstream code throws "Required field
  ;; ':enabled?' is missing".
  (when (and s (not (str/blank? s)))
    (into {} (for [pair (str/split s #"&")
                   :let [[k v] (str/split pair #"=" 2)
                         ^String vv (or v "")]
                   :when k]
               [(safe-url-decode k)
                (safe-url-decode vv)]))))


(defn require-storage
  [ctx]
  (or (:storage ctx)
      (throw (ex-info "Storage not available in context"
                      {:type :execution-error/missing-storage}))))


(def core-entity-types
  "The entity types the CORE schema ships. An ADDON adds its own (the
   tenancy addon's `:grant`, `:org`, `:account`, `:app`, …) and reaches
   them through the same `/api/entities/:type/:id` routes — so this set
   is the fast path, not the whole answer."
  {"fn" :fn
   "ns" :ns
   "slot" :slot
   "fn-slot" :fn-slot
   "binding" :binding
   "binding-list-item" :binding-list-item
   "service" :service})


(defn entity-type-from-string
  "URL segment → entity-type keyword, or nil when nothing by that name
   exists. `known` is the live schema's entity set (see
   `schema-entity-types`); without it only the core types resolve.

   The 1-arity was the whole function, and it made every addon entity
   un-deletable through the generic route: the tenancy Grants panel's ×
   posts `DELETE /api/entities/grant/:id`, the segment resolved to nil,
   and the handler answered 400 “Invalid request” — a dead button in a
   shipped panel, and the step tutorial lesson 24 tells the reader to
   perform."
  ([s] (entity-type-from-string s nil))
  ([s known]
   (or (get core-entity-types s)
       (when (and s (seq known))
         (let [kw (keyword s)]
           (when (contains? (set known) kw) kw))))))


(defn schema-entity-types
  "Entity-type keywords the STORAGE knows, from its schema metadata
   (`{:entities {uuid → name}}`) — cached in the storage, so this is a
   map read per request, not a query. nil-safe: a storage that cannot
   answer leaves the caller with the core set."
  [storage]
  (try
    (some-> storage
            ((requiring-resolve 'graphden.storage.protocol.core/schema-metadata))
            :entities
            vals
            set)
    (catch Exception _ nil)))


(defn parse-uri-segments
  "Pulls the `(type [id])` tail out of `:uri` for the entity routes.

   We can't rely on reitit's `:path-params` here because the route
   handler is invoked through a hof-wrap whose `:request` deep-free is
   captured from the outer fn-graph scope rather than from reitit's
   per-call `enrich-request` augmentation. The captured request is
   the raw http-kit one and never sees `:path-params`. Parsing the URI
   ourselves is dependency-free and exact for this small path family."
  [uri]
  (when uri
    ;; Recognised shapes:
    ;;   /api/entities/:type
    ;;   /api/entities/:type/:id
    ;;   /api/sequence/append/:fn-id
    ;;   /api/sequence/item/:item-id
    (let [segs (->> (str/split uri #"/") (remove str/blank?) vec)]
      (cond
        (and (= "api" (get segs 0)) (= "entities" (get segs 1)))
        {:type-str (get segs 2) :id-str (get segs 3)}

        (and (= "api" (get segs 0)) (= "sequence" (get segs 1)) (= "append" (get segs 2)))
        {:fn-id-str (get segs 3)}

        (and (= "api" (get segs 0)) (= "sequence" (get segs 1)) (= "item" (get segs 2)))
        {:item-id-str (get segs 3)}

        (and (= "api" (get segs 0)) (= "bindings" (get segs 1))
             (= "tighten-fn-effects" (get segs 3)))
        {:binding-id-str (get segs 2)}

        :else {}))))


(defn extract-entity-params
  "Extracts type-str, id-str, entity-type from request. Prefers
   reitit's `:path-params` when present; falls back to URI parsing
   (the handler is sometimes reached with the raw http-kit request
   that hasn't been through reitit's `enrich-request`).

   `known` — the live schema's entity-type set, so an addon's entities
   resolve too. Omit it and only the core types do."
  ([request] (extract-entity-params request nil))
  ([request known]
   (let [pp (:path-params request)
         rp (when (nil? pp) (parse-uri-segments (:uri request)))
         type-str (or (:type pp) (:type-str rp))
         id-str (or (:id pp) (:id-str rp))]
     {:type-str type-str
      :id-str id-str
      :entity-type (entity-type-from-string type-str known)})))


(defn read-json-body
  "Pull the JSON body off a Ring request whether it arrives as a
   string, an InputStream, or already-parsed Clojure data. Returns
   a Clojure map with keyword keys, or nil for an empty body. The
   layout endpoint has the same logic — we duplicate here so the
   types API doesn't depend on app.layout (cross-package)."
  [request]
  (let [raw (:body request)]
    ;; A malformed body is UNTRUSTED, unauthenticated-reachable input
    ;; (every /auth/* handler and every graph JSON endpoint reads here).
    ;; A bare `JsonParseException` carries no ex-data `:type`, so the
    ;; error boundary would map it to 500 AND page on `:http/server-error`.
    ;; Re-raise as a typed `:validation-error/*` → 400, no server-error.
    (try
      (cond
        (nil? raw)                            nil
        (map? raw)                            raw
        (instance? java.io.InputStream raw)
        (json/parse-stream
          (java.io.InputStreamReader. ^java.io.InputStream raw "UTF-8") true)
        (and (string? raw) (not (str/blank? raw)))
        (json/parse-string raw true)
        :else                                 nil)
      (catch com.fasterxml.jackson.core.JsonProcessingException _
        (throw (ex-info "Malformed JSON in request body."
                        {:type :validation-error/malformed-json}))))))


(defn parse-uuid-or-clear
  "Parse `v` as a UUID. Returns nil for:
   - non-string input (`123`, `:foo`, nested map …) — protects HTTP
     bodies where the JSON decoder turned the field into something
     other than a string;
   - blank string (the `or-clear` path — caller treats it as 'field
     cleared');
   - any string that isn't a well-formed UUID.

   Every failure mode collapses to nil so HTTP callers don't get
   bare `java.lang.IllegalArgumentException: Invalid UUID string …`
   leaks bubbling up as 500-style response bodies (see the
   `/api/execute {:args {:nums {:ref \"not-a-uuid\"}}}` regression
   that surfaced this — strict parsing here was the only call site
   missing a defensive wrap). The strict legacy behaviour lives on
   in plain `java.util.UUID/fromString` for callers that genuinely
   need it (none in this codebase)."
  [v]
  (when (and (string? v) (not (str/blank? v)))
    (try (java.util.UUID/fromString v)
         (catch IllegalArgumentException _ nil))))
