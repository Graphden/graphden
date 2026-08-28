(ns graphden.packages.app.tour-content-test
  "Contract tests for the interactive tutorial's lesson scripts
   (`:_tour-lessons` in `resources/packages/app/tour/fns.edn`).

   209 steps across 25 lessons are DATA, served verbatim to the editor
   at `GET /api/tour` — and until this file existed nothing checked
   them. A `:check {:kind …}` typo, a `:creates {:type …}` the client
   cannot clean up, a `:requires` naming neither a capability nor a
   known signal, a chapter that appears twice in the reading order:
   each ships silently and shows up as a dead step in front of a
   reader. The browser guards walk 19 of the lessons, take minutes,
   and run only on the gate; the six organization lessons they cannot
   walk are exactly the ones this file still covers.

   Pure EDN — no loader, no HTTP, no browser. The vocabularies below
   mirror `editor-tour-checks.js` (`_tourCheckPasses`),
   `editor-tour-cleanup.js` (`_tourSurvivors` / the delete pass) and
   `editor-tour-picker.js` (`REQUIRE_SIGNALS`); adding a kind means
   adding it in BOTH places, which is the point of the test."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.set :as set]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]))


(def ^:private check-kinds
  "Every `:check :kind` `_tourCheckPasses` implements. `manual` is the
   reader's own Next button — no predicate."
  #{"manual" "fn-exists" "fn-parent" "ns-exists" "binding-bound" "binding-value"
    "bindings-count" "selected" "on-branch" "arg-named" "dom" "dom-absent"})


(def ^:private creates-types
  "Every `:creates :type` the cleanup pass can both REPORT and delete."
  #{"fn" "ns" "branch" "package-version"})


(def ^:private require-signals
  "The `:requires` values that are named conditions rather than
   capabilities (REQUIRE_SIGNALS in the picker)."
  #{"services" "assets" "org"})


(def ^:private known-capabilities
  "Capabilities the tenancy addon grants; a `:requires` outside the
   signal set is looked up through `window.graphdenHasCap`."
  #{"manage-users" "manage-grants" "manage-roles" "manage-apps"
    "publish-packages"})


(defn- payload
  []
  (let [content (-> (io/resource "packages/app/tour/fns.edn") slurp edn/read-string)
        defs (if (vector? content) content (:fns content))
        tour (some #(when (= :_tour-lessons (:name %)) %) defs)]
    (get-in tour [:args :value])))


(defn- lessons
  []
  (:lessons (payload)))


(deftest lesson-scripts-are-well-formed
  (let [ls (lessons)]
    (testing "the payload is there at all"
      (is (seq ls) "`:_tour-lessons` carries lessons")
      (is (every? :id ls) "every lesson has an id")
      (is (= (count ls) (count (distinct (map :id ls)))) "ids are unique"))

    (testing "every lesson has a title, a chapter and at least one step"
      (doseq [l ls]
        (is (not (str/blank? (:title l))) (str "lesson " (:id l) " has a title"))
        (is (not (str/blank? (:chapter l))) (str "lesson " (:id l) " has a chapter"))
        (is (seq (:steps l)) (str "lesson " (:id l) " has steps"))))

    (testing "every step has prose — an empty popover is a dead end"
      (doseq [l ls, s (:steps l)]
        (is (not (str/blank? (:title s)))
            (str "lesson " (:id l) " step titles are non-blank"))
        (is (not (str/blank? (:body s)))
            (str "lesson " (:id l) " step bodies are non-blank"))))))


(deftest step-checks-use-the-implemented-vocabulary
  (doseq [l (lessons), s (:steps l)]
    (let [kind (get-in s [:check :kind])]
      (is (contains? check-kinds kind)
          (str "lesson " (:id l) " / “" (:title s) "”: :check kind " (pr-str kind)
               " is not implemented by _tourCheckPasses " (pr-str check-kinds)))
      ;; A check that needs an argument and doesn't get one can never pass.
      (case kind
        ("fn-exists" "selected" "on-branch")
        (is (some? (get-in s [:check :name]))
            (str "lesson " (:id l) " / “" (:title s) "”: " kind " needs :name"))
        "fn-parent"
        (is (and (some? (get-in s [:check :name])) (some? (get-in s [:check :parent])))
            (str "lesson " (:id l) " / “" (:title s) "”: fn-parent needs :name + :parent"))
        ("binding-bound" "binding-value")
        (is (and (some? (get-in s [:check :name])) (some? (get-in s [:check :slot])))
            (str "lesson " (:id l) " / “" (:title s) "”: " kind " needs :name + :slot"))
        ("dom" "dom-absent")
        (is (not (str/blank? (get-in s [:check :selector])))
            (str "lesson " (:id l) " / “" (:title s) "”: " kind " needs :selector"))
        "arg-named"
        (is (some? (get-in s [:check :arg]))
            (str "lesson " (:id l) " / “" (:title s) "”: arg-named needs :arg"))
        nil))))


(deftest created-rows-are-of-a-type-cleanup-handles
  ;; The end-of-tour offer lists what still exists and deletes it. A type the
  ;; deleter knows and the reporter does not is a row the reader is never told
  ;; about — and, if it is a lesson's only creation, never offered to delete.
  (doseq [l (lessons), s (:steps l)
          :let [c (:creates s)]
          :when c]
    (is (contains? creates-types (:type c))
        (str "lesson " (:id l) " / “" (:title s) "”: :creates type " (pr-str (:type c))
             " is outside " (pr-str creates-types)))
    (is (not (str/blank? (:name c)))
        (str "lesson " (:id l) " / “" (:title s) "”: :creates needs a :name"))))


(deftest requires-names-a-signal-or-a-capability
  (doseq [l (lessons)
          :let [need (:requires l)]
          :when need]
    (is (or (contains? require-signals need) (contains? known-capabilities need))
        (str "lesson " (:id l) ": :requires " (pr-str need)
             " is neither a named signal " (pr-str require-signals)
             " nor a known capability " (pr-str known-capabilities)
             " — the picker would render it as “needs " need "” and gate it on"
             " a capability nobody grants"))))


(deftest chapters-are-contiguous-in-reading-order
  ;; The picker emits a heading whenever the chapter CHANGES, so a lesson
  ;; filed under an earlier chapter but placed later prints that heading a
  ;; second time. That shipped once (lesson 21 sat after “Your organization”
  ;; carrying “The editor”).
  (let [order (map :chapter (lessons))
        runs (map first (partition-by identity order))]
    (is (= (count runs) (count (distinct runs)))
        (str "each chapter appears as ONE contiguous run; got " (pr-str runs)))))


(defn- selector-tokens
  "Class / id tokens named by a lesson's DOM selectors and spotlight
   targets — `.gd-asset-row`, `#gd-pkg-chip`, `[data-section=…]` is
   skipped (attribute selectors are checked by their own literal)."
  [l]
  (let [strings (concat (keep :target (:steps l))
                        (keep #(get-in % [:check :selector]) (:steps l)))]
    (mapcat #(map second (re-seq #"[.#]([a-zA-Z][\w-]+)" %)) strings)))


(deftest every-selector-a-lesson-points-at-still-exists
  ;; The six organization lessons are never walked by a browser guard (they
  ;; need a tenancy addon the e2e stack doesn't have), so a renamed class in
  ;; the members / grants / apps panels would rot there unnoticed. Grep the
  ;; shipped sources instead: cheap, and it covers every lesson equally.
  (let [sources (->> (concat (file-seq (io/file "resources/packages/app"))
                             (file-seq (io/file "resources/packages/registry")))
                     (filter java.io.File/.isFile)
                     (filter #(re-find #"\.(js|edn|css)$" (java.io.File/.getName %)))
                     (map slurp)
                     (str/join "\n"))]
    (doseq [l (lessons)
            token (distinct (selector-tokens l))]
      (is (str/includes? sources token)
          (str "lesson " (:id l) " points at “" token
               "”, which no longer appears in app/ or registry/ sources")))))


(def ^:private copy-keys
  "Every key `_tourCopy` asks the payload for. The client keeps an
   English fallback for each, so a missing key degrades rather than
   breaks — which is exactly why nothing else would notice that the
   graph copy stopped being the source of truth."
  #{:cleanup-title :cleanup-body :cleanup-confirm :cleanup-keep
    :cleanup-done :cleanup-failed
    :branch-title :branch-body :branch-confirm :branch-keep
    :branch-done :branch-failed
    :paused})


(deftest end-of-tour-copy-lives-in-the-graph
  ;; Step prose is data; the two end-of-tour prompts used to be string
  ;; literals in the client. They say what will be DELETED — the one place a
  ;; deployment is most likely to want different words — so they moved into
  ;; the same payload. The fallbacks in `_tourCopy` mean a drop-out is silent.
  (let [copy (:copy (payload))]
    (is (map? copy) "`:_tour-lessons` carries a `:copy` map")
    (doseq [k copy-keys]
      (is (not (str/blank? (get copy k)))
          (str "copy key " k " is missing — the client would fall back to its"
               " built-in English and the graph would stop being the source")))
    (is (empty? (set/difference (set (keys copy)) copy-keys))
        (str "copy carries a key no client reads: "
             (pr-str (set/difference (set (keys copy)) copy-keys))))))


(deftest copy-placeholders-are-ones-the-client-fills
  ;; `{branch}` / `{items}` / `{lesson}` are substituted by `_tourCopy`'s
  ;; caller; any other `{…}` reaches the reader verbatim.
  (let [allowed #{"branch" "items" "lesson"}]
    (doseq [[k text] (:copy (payload))
            ph (map second (re-seq #"\{([a-z-]+)\}" (str text)))]
      (is (contains? allowed ph)
          (str "copy key " k " uses placeholder {" ph "}, which no caller"
               " fills — it would print literally")))))


;; ============================================================================
;; The tour and the WRITTEN lessons are two halves of one thing
;; ============================================================================

(defn- written-lesson-ids
  "Lesson ids with a file in `docs/tutorial/` (`NN-slug.md`)."
  []
  (into #{}
        (keep #(second (re-matches #"(\d{2}[a-z]?)-.*\.md" (java.io.File/.getName %))))
        (file-seq (io/file "docs/tutorial"))))


(defn- index-rows
  "`{id → status-cell}` from the index table in docs/tutorial/README.md."
  []
  (into {}
        (map (fn [[_ id status]] [id status]))
        (re-seq #"(?m)^\|\s*(\d{2}[a-z]?)\s*\|[^|]*\|\s*([^|]*?)\s*\|"
                (slurp (io/file "docs/tutorial/README.md")))))


(deftest every-tour-has-a-written-lesson
  ;; The tour is the SHOWN half; the file is the read half, and each lesson's
  ;; own last step points at the other one ("the written lesson NN covers…").
  ;; A tour with no file leaves that pointer dangling, and a reader who
  ;; prefers text with nothing to read.
  (let [written (written-lesson-ids)]
    (doseq [l (lessons)]
      (is (contains? written (:id l))
          (str "tour lesson " (:id l) " (\"" (:title l) "\") has no"
               " docs/tutorial/" (:id l) "-*.md")))))


(deftest the-index-marks-exactly-the-lessons-that-have-a-tour
  ;; The ▶ column is how a reader decides whether to open the editor. It is
  ;; hand-maintained, so it drifts silently in both directions: a new tour
  ;; nobody advertises, or a ▶ pointing at a lesson whose tour was removed.
  (let [toured (into #{} (map :id) (lessons))
        rows (index-rows)]
    (is (seq rows) "the index table parsed")
    (doseq [[id status] rows]
      (if (contains? toured id)
        (is (str/includes? status "interactive")
            (str "lesson " id " HAS a tour but the index does not say"
                 " ▶ interactive (status: " (pr-str status) ")"))
        (is (not (str/includes? status "interactive"))
            (str "lesson " id " has NO tour but the index advertises one"
                 " (status: " (pr-str status) ")"))))
    (doseq [id toured]
      (is (contains? rows id)
          (str "tour lesson " id " is missing from the index table entirely")))))


(deftest a-lesson-that-needs-a-capability-says-so-in-both-places
  ;; `:requires` gates the tour; the written lesson has to warn too, or a
  ;; reader on the wrong plan follows a walkthrough of a panel they cannot
  ;; open. Cheap check: the file mentions the requirement.
  (doseq [l (lessons)
          :let [need (:requires l)]
          :when need]
    (let [f (first (filter #(re-matches (re-pattern (str (:id l) "-.*\\.md"))
                                        (java.io.File/.getName %))
                           (file-seq (io/file "docs/tutorial"))))
          text (some-> f slurp)]
      (is text (str "lesson " (:id l) " has a written file"))
      (when text
        (is (or (str/includes? text need)
                (str/includes? text "organization")
                (str/includes? text "tenancy")
                (str/includes? text "self-host"))
            (str "lesson " (:id l) " requires " (pr-str need)
                 " but its written lesson never says what it needs"))))))
