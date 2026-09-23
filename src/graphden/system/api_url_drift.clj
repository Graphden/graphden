(ns graphden.system.api-url-drift
  "Sync-time validator that fails the boot if any editor JS file
   references an `/api/*` or `/partials/*` URL that the live reitit
   router doesn't serve.

   The editor frontend hardcodes ~120 `/api/*` and ~50 `/partials/*`
   URL literals (fetches, and `hx-get=\"/partials/…\"` attributes built
   in JS strings) across the editor modules; the router declares the
   same paths in `app/routes/*`.
   These two surfaces drift silently — rename a route fn-def, the
   JS keeps calling the old path, the user sees a 404 in the
   browser. This validator catches that at sync time so the deploy
   itself fails.

   Algorithm (pure data, no DB):

   1. From the compiled reitit router, extract every full path
      pattern (e.g. `/api/branches/:ref/diff`).
   2. Build a set of literal forms a JS URL might be:
      - the full pattern verbatim (for paths with no params), AND
      - the literal prefix up to the first `:param` segment
        (so `/api/branches/' + encodeURIComponent(ref)` is allowed
        because `/api/branches/` is a known prefix).
   3. Scan every JS file under `resources/packages/app/editor/` for
      string literals (`'…'`, `\"…\"`, `` `…` ``) starting with `/api/`
      or `/partials/`. Comment lines are skipped — prose naming a
      route is not a request.
   4. For each literal, normalize and verify against the set.
   5. Throw with a per-file, per-line listing if any drift.

   The validator is run from an integrant init-key
   (`:exec/api-url-drift-check`) that depends on
   `:exec/compiled-registry`, so it sees the same router the
   request path serves."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [reitit.core :as r]
    [reitit.ring :as ring]))


;; =============================================================================
;; Router path enumeration
;; =============================================================================

(defn router-paths
  "All path patterns the compiled router serves, in route-table
   order. Accepts either a bare `reitit.core/Router` or a
   `reitit.ring` handler. A plain-fn route-collection router (e.g. the
   accounts `/auth/*` router) has no reitit route table → `[]`."
  [router]
  (if-let [rr (or (ring/get-router router) (when (satisfies? r/Router router) router))]
    (mapv first (r/routes rr))
    []))


(defn- literal-prefix
  "Everything in `path` up to (but not including) the first segment
   that starts with `:`. Includes the trailing `/`.

   `/api/branches`           → `/api/branches`   (no params; same as path)
   `/api/branches/:ref`      → `/api/branches/`
   `/api/fns/:id/versions`   → `/api/fns/`"
  [path]
  (let [segs (str/split path #"/")
        kept (take-while (fn [s] (not (str/starts-with? s ":")))
                         segs)]
    (if (= (count kept) (count segs))
      path
      (str (str/join "/" kept) "/"))))


(def ^:private checked-roots
  "The URL roots the editor JS calls on its own origin — every
   literal under one of these must be served by the router."
  ["/api/" "/partials/"])


(defn- checked-path?
  [path]
  (some #(str/starts-with? path %) checked-roots))


(defn allowed-literal-set
  "Set of `/api/*` and `/partials/*` literals a JS file may legally
   contain, derived from the router's path patterns. Each pattern
   contributes its literal prefix; non-parametric patterns contribute
   themselves verbatim."
  [paths]
  (->> paths
       (filter checked-path?)
       (map literal-prefix)
       set))


;; =============================================================================
;; JS literal extraction
;; =============================================================================

;; URL-shaped substrings inside single-, double- or back-quoted
;; strings. Greedy `/api/...` / `/partials/...` capture stops at the
;; first non-URL character (quote, backtick, whitespace, `+`, `?`, `$`,
;; etc.) — those are the characters that end a string-literal or start
;; a JS expression. A double quote also opens an HTML attribute inside
;; a JS string, so `'<div hx-get="/partials/stats">'` is captured too.
(def ^:private url-literal-regex
  #"['\"`]((?:/api|/partials)/[a-zA-Z0-9_\-/]*)")


;; Optional-package marker. A line carrying
;; `// api-url-drift-optional: <router-fn-name>` names a URL an OPTIONAL
;; first-party package serves (registry / mcp — dropped from
;; `:package-names` on some deployments, served per-branch by the
;; branch-router). Its literals are checked against that router when it is
;; loaded, and skipped when it is not — the editor gates those surfaces at
;; runtime, so an absent package is not drift.
(def ^:private optional-marker-regex
  #"//\s*api-url-drift-optional:\s*([a-zA-Z0-9_\-]+)")


;; A line that is only comment (`// …`, ` * …`, `/* …`) is prose, not a
;; request — a comment naming a route must not fail the boot.
(def ^:private comment-line-regex
  #"^\s*(?://|/\*|\*)")


;; Opt-out marker. A line containing `// api-url-drift-allow:` is
;; skipped — every literal on it counts as deliberately exempt.
;; Reserved for cases like `url.startsWith('/api/')` (discriminator,
;; not a URL we send) or one-off third-party URLs.
(def ^:private allow-marker-regex
  #"//\s*api-url-drift-allow:")


(defn extract-js-literals
  "Returns a seq of `{:file :line :literal}` for every `/api/*` and
   `/partials/*` string-literal in the given JS source (plus
   `:optional-router` when the line carries the `api-url-drift-optional:`
   marker). Lines carrying the `api-url-drift-allow:` opt-out marker, and
   comment-only lines, are skipped wholesale."
  [file source]
  (let [lines (str/split-lines source)]
    (->> (map vector (range 1 (inc (count lines))) lines)
         (remove (fn [[_ line]]
                   (or (re-find allow-marker-regex line)
                       (re-find comment-line-regex line))))
         (mapcat
           (fn [[lineno line]]
             (let [optional (second (re-find optional-marker-regex line))]
               (for [[_ literal] (re-seq url-literal-regex line)]
                 (cond-> {:file file
                          :line lineno
                          :literal literal}
                   optional (assoc :optional-router optional)))))))))


(def ^:private editor-js-prefix "packages/app/editor/")


(defn- list-editor-js-resources
  "Resource-relative paths of every `.js` file under
   `packages/app/editor/`. Works for both a filesystem-rooted
   classpath (REPL / `bb test`) and a packaged JAR (deploy)."
  []
  (let [url (io/resource editor-js-prefix)]
    (case (some-> url java.net.URL/.getProtocol)
      "file"
      (->> (java.io.File/.listFiles (io/file url))
           (map java.io.File/.getName)
           (filter #(str/ends-with? % ".js"))
           (map #(str editor-js-prefix %))
           sort)

      "jar"
      ;; The URL is `jar:file:/path/to.jar!/packages/app/editor/` —
      ;; open the underlying JAR and list every entry whose name
      ;; starts with our prefix and ends in `.js` (one level deep).
      (let [conn (java.net.URL/.openConnection url)
            jar  (java.net.JarURLConnection/.getJarFile
                   (cast java.net.JarURLConnection conn))]
        (->> (java.util.jar.JarFile/.entries jar)
             enumeration-seq
             (map java.util.jar.JarEntry/.getName)
             (filter #(and (str/starts-with? % editor-js-prefix)
                           (str/ends-with? % ".js")
                           (not (str/includes?
                                  (subs % (count editor-js-prefix))
                                  "/"))))
             sort))

      nil
      (throw (ex-info "Editor JS resources not found on classpath"
                      {:type :web/api-url-drift-no-resources
                       :prefix editor-js-prefix})))))


(defn extract-all-editor-literals
  "Returns every `/api/*` and `/partials/*` literal across every editor JS file,
   tagged with `{:file :line :literal}`."
  []
  (->> (list-editor-js-resources)
       (mapcat (fn [resource-path]
                 (let [src (slurp (io/resource resource-path))]
                   (extract-js-literals resource-path src))))))


;; =============================================================================
;; Drift detection
;; =============================================================================

(defn- literal-matches?
  "True iff `literal` is acceptable given `allowed-set`. Acceptable
   means EITHER:

   - the literal exactly equals an allowed pattern (`/api/branches`),
     OR
   - the literal extends an allowed prefix that ends in `/`
     (`/api/entities/fn/` extends `/api/entities/`; the trailing
     part is the dynamic `:entity-type` segment the JS supplies at
     runtime via string concat or `encodeURIComponent`), OR
   - implicit-trailing-slash variant — the literal lacks a trailing
     `/` but `literal + \"/\"` is itself an allowed prefix
     (`/api/secrets` against `/api/secrets/`)."
  [allowed-set literal]
  (or (contains? allowed-set literal)
      (and (not (str/ends-with? literal "/"))
           (contains? allowed-set (str literal "/")))
      ;; Prefix-extends-allowed: the literal starts with some
      ;; allowed prefix that ends in `/`. Only `/`-terminated
      ;; prefixes participate so we don't accept `/api/foozzy` just
      ;; because `/api/foo` is allowed.
      (some (fn [allowed]
              (and (str/ends-with? allowed "/")
                   (str/starts-with? literal allowed)))
            allowed-set)))


(defn find-drift
  "Returns the subset of `literals` that aren't acceptable under
   `allowed-set`. Empty seq = no drift."
  [allowed-set literals]
  (->> literals
       (remove (fn [{:keys [literal]}]
                 (literal-matches? allowed-set literal)))))


(defn- format-drift-message
  [drift allowed-set]
  (str "Found " (count drift) " /api/* or /partials/* URL literal(s) in editor JS that "
       "don't match any route the live router serves. Either fix the "
       "JS (the route was renamed) or add the route to "
       "`app/routes/*`. Drift:\n"
       (->> drift
            (sort-by (juxt :file :line))
            (map (fn [{:keys [file line literal]}]
                   (str "  " file ":" line "  " literal)))
            (str/join "\n"))
       "\n\nAllowed prefixes (" (count allowed-set) "):\n"
       (->> allowed-set sort (map #(str "  " %)) (str/join "\n"))))


(defn assert-no-drift!
  "Throws `:web/api-url-drift` if any literal in `literals` isn't
   in `allowed-set`. Returns `:ok` otherwise. Exposed as its own fn
   so unit tests can drive it without booting the system."
  [allowed-set literals]
  (let [drift (find-drift allowed-set literals)]
    (if (empty? drift)
      :ok
      (throw (ex-info (format-drift-message drift allowed-set)
                      {:type :web/api-url-drift
                       :drift drift
                       :allowed allowed-set})))))


(defn checkable-literals
  "`literals` minus those tagged for an optional router that is not loaded
   (`loaded-optional` — a set of router fn-names)."
  [literals loaded-optional]
  (remove (fn [{:keys [optional-router]}]
            (and optional-router (not (contains? loaded-optional optional-router))))
          literals))


(defn check-router!
  "Top-level entry: enumerate the paths of `router` plus every loaded
   OPTIONAL router (`optional` — `{router-fn-name router}`), scan editor JS
   for `/api/*` and `/partials/*` literals, throw if any drift. Idempotent
   + pure modulo the slurp."
  ([router] (check-router! router {}))
  ([router optional]
   (let [allowed (->> (cons router (vals optional))
                      (mapcat router-paths)
                      allowed-literal-set)
         literals (checkable-literals (extract-all-editor-literals)
                                      (set (keys optional)))]
     (assert-no-drift! allowed literals))))
