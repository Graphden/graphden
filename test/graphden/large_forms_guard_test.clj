(ns graphden.large-forms-guard-test
  "Mechanical half of the project's decomposition rule:

     A top-level form of 100 lines or more is either SPLIT, or its
     reason for staying whole is written down.

   The rule predates this test and was followed unevenly — audits kept
   re-discovering the same 40-odd forms and re-deciding them one at a
   time. `tools/large-forms.edn` holds the decisions in one readable
   table and this test pins it from both sides:

   - a form that grows past the threshold and is NOT in the table fails
     the build, so the next one gets decided when it is written rather
     than at the next audit;
   - a table entry whose form is gone (split, renamed, deleted) also
     fails, so the ledger can only shrink as the code improves.

   This is a SIZE check, not a quality one: being listed says the size
   was considered and defended, nothing more. The reasons themselves are
   the interesting part — read the table.

   Scope: `src/`, the package `impls.clj` files, and the build/dev
   scripts. Test files are excluded — a long `deftest` is a table of
   cases, and §9 governs those instead. Only NAMED definitions are
   measured: a bare top-level `(doseq …)` seeding a registry (see
   `storage.protocol.errors`) has no name to defend and is data by
   construction."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest is]]))


(def ^:private ledger-path "tools/large-forms.edn")


(def ^:private scan-roots
  ["src" "resources/packages" "scripts" "tools" "development/src"])


(defn- clj-files
  []
  (->> scan-roots
       (mapcat (fn [r] (file-seq (io/file r))))
       (filter #(and (java.io.File/.isFile %)
                     (str/ends-with? (java.io.File/.getName %) ".clj")))
       (remove #(str/includes? (java.io.File/.getPath %) "/target/"))))


(defn- form-name
  "`ns/symbol` for a top-level form, or nil when the head isn't a
   definition. Metadata in the name position (`^:private`, `^:dynamic`,
   a tag) is skipped; a `defmethod`'s dispatch value is appended so two
   methods of one multimethod stay distinguishable."
  [ns-name head rest-of-line]
  (let [toks (->> (str/split (str/trim rest-of-line) #"\s+")
                  (remove #(str/starts-with? % "^"))
                  (remove str/blank?))
        sym (first toks)]
    (when (and sym (re-matches #"[^\s\(\[\{\"]+" sym))
      (str ns-name "/" sym
           (when (and (= head "defmethod") (second toks))
             (str " " (second toks)))))))


(defn- large-forms
  "`{\"ns/name\" line-count}` for every top-level definition at or past
   `threshold` lines. Line-based on purpose: it is the same thing a
   reader scrolling the file experiences, and it needs no reader."
  [threshold]
  (into {}
        (mapcat
          (fn [f]
            (let [lines (str/split-lines (slurp f))
                  ns-name (or (some->> lines
                                       (some #(second (re-find #"^\(ns\s+([\w.-]+)" %))))
                              (java.io.File/.getPath f))
                  starts (keep-indexed
                           (fn [i l]
                             (when-let [[_ head rst] (re-find #"^\((\S+)(\s.*)?$" l)]
                               [i head (or rst "")]))
                           lines)
                  bounds (map vector starts (concat (map first (rest starts))
                                                    [(count lines)]))]
              (keep (fn [[[i head rst] end]]
                      (let [n (- end i)
                            nm (form-name ns-name head rst)]
                        (when (and nm (>= n threshold)) [nm n])))
                    bounds))))
        (clj-files)))


(deftest every-large-form-is-split-or-defended-test
  (let [{:keys [threshold acknowledged]} (edn/read-string (slurp ledger-path))
        found (large-forms threshold)
        undefended (sort (remove #(contains? acknowledged %) (keys found)))
        stale (sort (remove #(contains? found %) (keys acknowledged)))]
    (is (empty? undefended)
        (str "top-level form(s) of >= " threshold " lines with no entry in "
             ledger-path ". Split it, or add it with a one-line reason "
             "that says what holds it together: "
             (pr-str (mapv (fn [k] [k (get found k)]) undefended))))
    (is (empty? stale)
        (str ledger-path " defends form(s) that are no longer large — "
             "split, renamed or deleted. Drop the entries: " (pr-str stale)))))
