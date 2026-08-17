(ns graphden.id-resolution-guard-test
  "Mechanical guard for the project's core identity rule:

     'Internal mechanisms resolve/match/dispatch by stable ID (uuid),
      NOT by the human-facing NAME. Names are for readability only.'
      (see docs/adr/AUDIT-name-vs-id-resolution.md)

   This test makes ONE precise regression a red build: **dispatching on
   the NAME of a value you already hold by id**. Concretely, an entity
   dereferenced by its id — `(get fns-by-id some-id)`, `(get-in ctx
   [:fns id])`, `(read-entity storage :fn id)` — whose `:name` is then
   fed into a comparison (`=` / `not=` / `case` / `condp`). That is the
   C2/B1 anti-pattern: you have the id, so branch on the id.

   WHY the shape is drawn this narrowly:

     - Name EXTRACTION for display / serialization —
       `{:fn-name (:name (get fn-map fid))}`, `(keyword (:name (get-in
       ctx [:fns rid])))` in the package exporter — is CORRECT (id is
       the key; the name is a human/serialised projection) and is NOT
       inside a comparison, so it is not flagged.
     - Finding an entity BY name — `(some #(when (= \"sequence\"
       (:name %)) (:id %)) fns)` — is a legitimate name→id boundary
       resolution: `%` is a loop var, not an id-deref, so it is not
       flagged.

   Only 'I dereffed an id, then compared its name' trips it. The guard
   starts GREEN (the sole prior occurrence — the `:sequence` type-slot
   dispatch in crud/entities/seq.clj — was fixed to compare ids in the
   Tier-4 commit).

   SCOPE / honest limits: scans `src/` (the mechanism layer the audit
   covered), not package `impls.clj` — impls legitimately resolve a
   username / base-fn name → id at boundaries (see create-token /
   create-grant), which is the intended pattern, not a violation. Uses
   the same tolerant top-level reader as `base-fn-isolation-test`; a
   file that hits an unreadable form (rare `::alias/kw`) stops early
   there, so this is a best-effort forward guard, not a proof of
   absence."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest is]]
    [clojure.walk :as walk]))


(defn- src-files
  "Every .clj under src/, as File objects."
  []
  (->> (io/file "src")
       file-seq
       (filter #(and (java.io.File/.isFile %)
                     (str/ends-with? (java.io.File/.getName %) ".clj")))))


(defn- read-all-forms
  "Read every top-level form from a source file (tolerant — stops at the
   first unreadable form, mirroring base-fn-isolation-test)."
  [^java.io.File f]
  (with-open [r (java.io.PushbackReader. (io/reader f))]
    (let [eof (Object.)]
      (loop [acc []]
        (let [form (try (read {:eof eof :read-cond :allow} r)
                        (catch Exception _ eof))]
          (if (identical? form eof)
            acc
            (recur (conj acc form))))))))


(defn- id-deref?
  "Is `x` a lookup of an entity BY its id — `(get m id)`, `(get-in m
   [.. id])`, or `(…/read-entity …)`? These yield an entity we hold by
   id; taking their `:name` to branch is the anti-pattern."
  [x]
  (and (seq? x)
       (symbol? (first x))
       (contains? #{"get" "get-in" "read-entity"} (name (first x)))))


(defn- name-of-id?
  "`(:name <id-deref>)` — the name of an id-resolved entity."
  [x]
  (and (seq? x)
       (= :name (first x))
       (id-deref? (second x))))


(defn- dispatch-on-name-of-id?
  "Does `form` compare / case-dispatch on the name of an id-resolved
   entity?"
  [form]
  (when (seq? form)
    (let [[h & args] form]
      (cond
        (contains? #{'= 'not=} h)   (boolean (some name-of-id? args))
        (= 'case h)                 (name-of-id? (first args))
        (= 'condp h)                (name-of-id? (second args))  ; (condp pred expr …)
        :else                       false))))


(defn- violations-in
  [^java.io.File f]
  (let [hits (volatile! [])]
    (doseq [top (read-all-forms f)]
      (walk/postwalk
        (fn [x]
          (when (dispatch-on-name-of-id? x)
            (vswap! hits conj {:file (java.io.File/.getPath f)
                               :form (let [s (pr-str x)]
                                       (if (> (count s) 160)
                                         (str (subs s 0 160) " …")
                                         s))}))
          x)
        top))
    @hits))


(deftest no-dispatch-on-name-of-an-id-resolved-entity
  (let [files (src-files)
        violations (mapcat violations-in files)]
    ;; Sanity: `src-files` walks the relative "src" dir, so a wrong cwd
    ;; yields an EMPTY file list → zero violations → a green no-op that
    ;; stops enforcing the id-not-name rule. Assert the tree loaded.
    (is (< 100 (count files))
        (str "src corpus looks empty (" (count files) " files) — this "
             "guard is a no-op. Check the cwd."))
    (is (empty? violations)
        (str "Dispatching on the NAME of a value held by id violates the "
             "id-not-name rule — branch on the id instead (see "
             "docs/adr/AUDIT-name-vs-id-resolution.md). Offenders:\n"
             (str/join "\n"
                       (map #(format "  %s\n    %s" (:file %) (:form %))
                            violations))))))
