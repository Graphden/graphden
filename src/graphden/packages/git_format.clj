(ns graphden.packages.git-format
  "The GIT-facing text layout of an exported graph bundle
   (docs/PACKAGE_DISTRIBUTION.md § runtime bundle import; the CLI's
   `export --out DIR` / `import DIR` round-trip rides this).

   `bundle->files` lays a fn-def vector out as a DIRECTORY of EDN files —
   one file per namespace (`fns/<dotted-ns>.edn`, root defs in
   `fns/_root.edn`) plus a `graphden.edn` manifest — and `files->bundle`
   reads the directory map back into the same vector. Each file is a
   MODULE map ({:namespace … :fns […]}), i.e. exactly the shape
   `resources/packages/**/fns.edn` uses, so a git-stored graph reads like
   the first-party packages do.

   Everything here is PURE (path-string → content-string maps, no IO —
   the CLI owns the filesystem) and BYTE-DETERMINISTIC: files sort their
   defs by name, the top-level def keys print in a canonical reading
   order (nested maps in seq order — see the printer note), and the
   printer's output is a pure function of the bundle. Re-exporting an
   unchanged graph must produce byte-identical files — a git diff is a
   graph diff, never printer noise. The output format is versioned by the
   manifest's `:format`; changing the printer's layout is a format bump,
   not a patch.

   Unspellable refs (`@`-versioned / root namespaces) are encoded as
   `#graphden/ref` tagged literals before printing (`records.wire`), and
   `files->bundle` reads them back with `wire-readers` — the same wire
   contract the HTTP bundle uses."
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    [graphden.packages.records.wire :as wire]))


(def format-version
  "Bumped when the on-disk layout or printer output changes shape —
   `files->bundle` refuses a directory written by a NEWER format than it
   understands."
  1)


;; =============================================================================
;; Canonical printer
;; =============================================================================

(def ^:private fn-def-key-order
  "Top-level fn-def keys in reading order; unlisted keys follow,
   alphabetically."
  [:name :description :parent :parents :args :input :type :refine :list
   :variants :tuple :fn-type :marker :return-type :expects-effects
   :lambda-params :branch-local?])


(defn- key-rank
  [order k]
  (let [i (java.util.List/.indexOf ^java.util.List order k)]
    (if (neg? i) [1 (str k)] [0 i])))


(defn- sorted-entries
  [m order]
  (sort-by (fn [[k _]] (key-rank order k)) m))


(declare print-value)


;; NESTED maps print in their NATURAL seq order, NOT a canonical one.
;; This is load-bearing, not laziness: the parser derives slot POSITIONS
;; (and anonymous-def use-site ids) from the `:args` / record-shape map
;; ITERATION order, so re-ordering keys in the text would change the
;; imported GRAPH. Seq order is deterministic for a given map content
;; (array-maps keep insertion order, hash-maps order by key hash), and the
;; EDN reader reproduces it on the way back — so the round-trip is
;; faithful AND the bytes are stable. Only the top-level fn-def map, whose
;; key order the parser never depends on, gets the canonical reading
;; order.


(defn- inline-str
  "One-line rendering of `v` — used when it fits."
  [v]
  (cond
    (map? v) (str "{" (str/join " "
                                (map (fn [[k val]] (str (pr-str k) " " (inline-str val)))
                                     (seq v)))
                  "}")
    (vector? v) (str "[" (str/join " " (map inline-str v)) "]")
    :else (pr-str v)))


(def ^:private inline-width
  "A value whose one-line form fits in this many chars prints inline;
   longer maps/vectors break one entry per line."
  72)


(defn- print-value
  "Render `v` at `indent` columns. Deterministic: map entries in their
   seq order (load-bearing — see the note above), leaves via `pr-str`."
  [v indent]
  (let [one-line (inline-str v)]
    (if (or (<= (count one-line) inline-width)
            (not (coll? v)))
      one-line
      (let [pad (str/join (repeat (inc indent) " "))]
        (cond
          (map? v)
          (str "{"
               (str/join (str "\n" pad)
                         (map (fn [[k val]]
                                (str (pr-str k) " "
                                     (print-value val (+ indent 1 (count (pr-str k)) 1))))
                              (seq v)))
               "}")
          (vector? v)
          (str "["
               (str/join (str "\n" pad)
                         (map #(print-value % (inc indent)) v))
               "]")
          :else one-line)))))


(defn- print-fn-def
  "One fn-def as a top-level EDN map: canonical key order, one key per
   line, 2-space continuation."
  [fd]
  (str "  {"
       (str/join "\n   "
                 (map (fn [[k v]]
                        (str (pr-str k) " "
                             (print-value v (+ 3 (count (pr-str k)) 1))))
                      (sorted-entries fd fn-def-key-order)))
       "}"))


;; =============================================================================
;; bundle → files
;; =============================================================================

(defn- ns->filename
  [ns-path]
  (str "fns/" (or ns-path "_root") ".edn"))


(defn- module-file
  "One namespace's defs as a MODULE map file: `:namespace` at the module
   level (dropped per-def), defs sorted by name."
  [ns-path defs]
  (let [defs (->> defs
                  (map #(dissoc % :namespace))
                  (sort-by #(str (:name %))))]
    (str ";; Exported graphden graph — namespace "
         (or ns-path "<root>") ".\n"
         ";; Generated file (graphden.packages.git-format, format "
         format-version "); byte-stable — a git diff here is a graph diff.\n"
         (if ns-path
           (str "{:namespace " (pr-str ns-path) "\n :fns\n [")
           "{:fns\n [")
         (str/join "\n\n" (map #(str/triml (print-fn-def %)) defs))
         "]}\n")))


(defn bundle->files
  "Lay `fn-defs` (an export bundle's `:fns` vector) out as a
   `{relative-path → content-string}` map: one module file per namespace
   plus the `graphden.edn` manifest. `meta-map` (optional) is merged into
   the manifest — the CLI records `:branch` and the export's `:secrets`
   manifest there."
  ([fn-defs] (bundle->files fn-defs {}))
  ([fn-defs meta-map]
   (let [encoded (wire/encode-unreadable-kws (vec fn-defs))
         by-ns (group-by :namespace encoded)
         files (into (sorted-map)
                     (map (fn [[ns-path defs]]
                            [(ns->filename ns-path) (module-file ns-path defs)]))
                     by-ns)
         manifest (into (sorted-map)
                        (merge {:format format-version
                                :files (vec (keys files))}
                               meta-map))]
     (assoc files "graphden.edn"
            (str ";; graphden graph snapshot — see docs/PACKAGE_DISTRIBUTION.md.\n"
                 (print-value manifest 0) "\n")))))


;; =============================================================================
;; files → bundle
;; =============================================================================

(defn- read-wire-edn
  [s]
  (edn/read-string {:readers wire/wire-readers} s))


(defn files->bundle
  "Read a `{relative-path → content-string}` map (as `bundle->files` lays
   out) back into the flat fn-def vector. Validates the manifest's
   `:format` and each module file's `:namespace` against its path, and
   re-attaches the module namespace to every def. Throws
   `:git-format/unsupported-format` / `:git-format/namespace-mismatch`."
  [files]
  (let [manifest (some-> (get files "graphden.edn") read-wire-edn)]
    (when (and manifest (> (:format manifest 1) format-version))
      (throw (ex-info (str "graph snapshot written by a newer format "
                           (:format manifest) " — upgrade this install")
                      {:type :git-format/unsupported-format
                       :format (:format manifest)})))
    (->> files
         (filter (fn [[path _]]
                   (and (str/starts-with? path "fns/")
                        (str/ends-with? path ".edn"))))
         (map (fn [[path content]]
                (let [module (read-wire-edn content)
                      ns-path (:namespace module)]
                  (when (not= path (ns->filename ns-path))
                    (throw (ex-info (str "module namespace " (pr-str ns-path)
                                         " does not match its file " path)
                                    {:type :git-format/namespace-mismatch
                                     :path path :namespace ns-path})))
                  [ns-path (:fns module)])))
         ;; Sort by NAMESPACE, not by file path — the ".edn" suffix makes
         ;; "app.routes.edn" sort AFTER "app.routes.auth.edn", which would
         ;; reorder the bundle vs the exporter's [ns name] order. Parse is
         ;; order-sensitive at the margins (anonymous-def use-site ids), so
         ;; the round-trip must preserve the exact def order.
         (sort-by (fn [[ns-path _]] (str ns-path)))
         (into []
               (mapcat (fn [[ns-path defs]]
                         (mapv #(if ns-path (assoc % :namespace ns-path) %)
                               defs)))))))
