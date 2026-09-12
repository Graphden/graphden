#!/usr/bin/env bb
;; devtour — the developer code-tour generator + validator.
;;
;; Source of truth: docs/devtour/tour.edn  (blocks -> ordered steps).
;; Each step anchors on a SYMBOL (ns + defn name), never a line number, so
;; the tour survives edits above it. The generator pulls the anchored form's
;; ACTUAL source out of the file at build time and bakes it into a single
;; self-contained docs/devtour/index.html (no server, opens from file://).
;;
;;   bb devtour        -> regenerate the three baked outputs from tour.edn:
;;                          docs/devtour/index.html  — the standalone page
;;                          docs/devtour/tour.eld    — data for docs/devtour/devtour.el
;;                          docs/devtour/org/*.org   — the emacs/org reading path
;;   bb devtour-check  -> (CI) every anchor still resolves uniquely AND every
;;                        baked output matches a fresh regeneration.
;;                        Fails loudly if source drifted from the baked tour.
;;
;; Because check regenerates and byte-compares, a rename/removal of a toured
;; form, or any edit to its body, turns CI red until someone re-runs `bb devtour`
;; and commits — the tour cannot silently point at code that no longer exists.

(ns devtour
  (:require
    [babashka.fs :as fs]
    [cheshire.core :as json]
    [clojure.math :as math]
    [clojure.string :as str]
    [rewrite-clj.zip :as z]))


;; Both paths are env-overridable so an external repo can bake an alternate
;; tour (same anchors, different prose) against this checkout without forking
;; the generator. Defaults — and `bb devtour` / `bb devtour-check` — unchanged.
(def ^:private tour-edn (or (System/getenv "DEVTOUR_TOUR") "docs/devtour/tour.edn"))
(def ^:private out-html (or (System/getenv "DEVTOUR_OUT") "docs/devtour/index.html"))


;; The emacs-side outputs live next to the page, so an alternate bake
;; (DEVTOUR_OUT into another repo) carries its whole tour with it.
(def ^:private out-dir (str (fs/parent out-html)))
(def ^:private out-eld (or (System/getenv "DEVTOUR_ELD") (str (fs/path out-dir "tour.eld"))))
(def ^:private out-org (or (System/getenv "DEVTOUR_ORG") (str (fs/path out-dir "org"))))


;; Blob base for the "open on GitHub" action. A branch (not a sha): baking the
;; current HEAD would make `devtour-check` fail on every commit.
(def ^:private repo-url
  (or (System/getenv "DEVTOUR_REPO") "https://github.com/Graphden/graphden/blob/develop"))


;; How the org links reach the checkout from the org output directory. Computed
;; (never absolutised — the byte-compare check must be path-independent), so a
;; bake that writes elsewhere still links back here.
(def ^:private src-prefix
  (or (System/getenv "DEVTOUR_SRC_PREFIX")
      (str (fs/relativize (fs/absolutize out-org) (fs/absolutize ".")))))


;; --- anchor resolution -----------------------------------------------------

(def ^:private def-heads
  "Top-level forms whose second token names them."
  '#{def defn defn- defmacro defmethod defmulti defonce
     defprotocol deftype defrecord defbase deftest})


(defn- ns->path
  "graphden.executor.interface -> src/graphden/executor/interface.clj
   (Clojure munging: dot->slash, hyphen->underscore.)"
  [ns-sym]
  (str "src/"
       (-> (name ns-sym)
           (str/replace "-" "_")
           (str/replace "." "/"))
       ".clj"))


(defn- safe-sexpr
  "z/sexpr but nil instead of throwing on unreadable nodes (reader tags etc.)."
  [zloc]
  (try (z/sexpr zloc) (catch Exception _ nil)))


(defn- form-signature
  "For a top-level def-form list zloc, return {:name <sym> :dispatch <val>},
   else nil. `:dispatch` is the defmethod dispatch value (3rd token) or ::none
   for ordinary def-forms — so a defmethod can be anchored by (name, dispatch)."
  [zloc]
  (when (= :list (z/tag zloc))
    (let [head (some-> zloc z/down)
          head-sym (safe-sexpr head)]
      (when (contains? def-heads head-sym)
        (let [nm (safe-sexpr (z/right head))]
          (when (symbol? nm)
            (if (= 'defmethod head-sym)
              {:name nm :dispatch (safe-sexpr (-> head z/right z/right))}
              {:name nm :dispatch ::none})))))))


(defn- find-form
  "Locate the def-form named `sym` (and, for a defmethod, dispatching on
   `dispatch`) among the top-level forms of `src`. Returns {:code :line} or
   throws on 0 / >1 matches. A nil `dispatch` matches on name alone."
  [src sym dispatch file]
  (loop [zloc (z/of-string src {:track-position? true})
         hits []]
    (if (or (nil? zloc) (z/end? zloc))
      (case (count hits)
        1 (first hits)
        0 (throw (ex-info (str "anchor not found: " sym
                               (when dispatch (str " / " dispatch)) " in " file)
                          {:sym sym :file file}))
        (throw (ex-info (str "anchor ambiguous: " sym
                             (when dispatch (str " / " dispatch)) " appears "
                             (count hits) "x in " file
                             " — add :dispatch, split the form, or rename")
                        {:sym sym :file file :count (count hits)})))
      (let [sig (form-signature zloc)
            hit (when (and sig
                           (= sym (:name sig))
                           (or (nil? dispatch) (= dispatch (:dispatch sig))))
                  {:code (z/string zloc)
                   :line (first (z/position zloc))})]
        (recur (z/right zloc) (cond-> hits hit (conj hit)))))))


;; --- JS anchor resolution --------------------------------------------------
;;
;; The editor frontend is ~25k lines of plain ES modules concatenated into one
;; bundle — no build step, no imports. It is half the product a newcomer
;; touches, so it is toured too, with the same symbol-anchored contract as the
;; Clojure side: name a declaration, the generator bakes its real source.

(defn- js-decl-re
  "Regex matching the line that DECLARES `sym` in a JS module: a function
   declaration or a const/let/var binding, at any indentation (several
   editor modules wrap their body in an IIFE)."
  [sym]
  (let [n (java.util.regex.Pattern/quote (str sym))]
    (re-pattern (str "^[ \\t]*(?:(?:async[ \\t]+)?function[ \\t]+" n "[ \\t]*\\("
                     "|(?:const|let|var)[ \\t]+" n "[ \\t]*=)"))))


(defn- regex-position?
  "Heuristic: at `i` (a `/` in code state), does a REGEX literal start here
   rather than a division? True when the previous significant character can
   only precede an expression."
  [^String src ^long i]
  (let [prev (loop [j (dec i)]
               (cond (neg? j) nil
                     (Character/isWhitespace (String/.charAt src j)) (recur (dec j))
                     :else (String/.charAt src j)))]
    (or (nil? prev) (contains? (set "(,=:[!&|?{};+-*%^~<>") prev))))


(defn- js-form-end
  "Index (exclusive) of the end of the JS declaration starting at `start`.
   Scans forward tracking string / template / comment / regex state and
   bracket depth; the form ends at the `;` or newline where depth is back to
   zero. Throws at EOF — a mis-scan fails the build rather than baking a
   truncated form."
  [^String src ^long start]
  (let [n (String/.length src)]
    (loop [i start, state :code, depth 0, opened? false]
      (when (>= i n)
        (throw (ex-info "JS anchor: unbalanced form (hit EOF)" {:start start})))
      (let [c (String/.charAt src i)
            nxt (when (< (inc i) n) (String/.charAt src (inc i)))]
        (case state
          :line-comment (recur (inc i) (if (= c \newline) :code state) depth opened?)
          :block-comment (if (and (= c \*) (= nxt \/))
                           (recur (+ i 2) :code depth opened?)
                           (recur (inc i) state depth opened?))
          (:sq :dq :tpl :regex)
          (cond
            (= c \\) (recur (+ i 2) state depth opened?)
            (or (and (= state :sq) (= c \'))
                (and (= state :dq) (= c \"))
                (and (= state :tpl) (= c \`))
                (and (= state :regex) (= c \/)))
            (recur (inc i) :code depth opened?)
            :else (recur (inc i) state depth opened?))
          :code
          (cond
            (and (= c \/) (= nxt \/)) (recur (+ i 2) :line-comment depth opened?)
            (and (= c \/) (= nxt \*)) (recur (+ i 2) :block-comment depth opened?)
            (and (= c \/) (regex-position? src i)) (recur (inc i) :regex depth opened?)
            (= c \') (recur (inc i) :sq depth opened?)
            (= c \") (recur (inc i) :dq depth opened?)
            (= c \`) (recur (inc i) :tpl depth opened?)
            (contains? #{\( \[ \{} c) (recur (inc i) state (inc depth) true)
            (contains? #{\) \] \}} c) (recur (inc i) state (dec depth) opened?)
            (and (zero? depth) (= c \;)) (inc i)
            (and (zero? depth) opened? (= c \newline)) i
            :else (recur (inc i) state depth opened?)))))))


(defn- find-js-decl
  "Locate the declaration of `sym` in a JS source. Returns {:code :line} or
   throws on 0 / >1 matches — the same uniqueness contract the Clojure
   anchors carry."
  [^String src sym file]
  (let [re (js-decl-re sym)
        lines (str/split-lines src)
        ;; offset of each line's first char
        offsets (reductions + 0 (map #(inc (count %)) lines))
        hits (for [[idx line off] (map vector (range) lines offsets)
                   :when (re-find re line)]
               {:line (inc idx)
                :code (subs src off (js-form-end src off))})]
    (case (count hits)
      1 (first hits)
      0 (throw (ex-info (str "anchor not found: " sym " in " file) {:sym sym :file file}))
      (throw (ex-info (str "anchor ambiguous: " sym " appears " (count hits)
                           "x in " file " — rename or split the form")
                      {:sym sym :file file :count (count hits)})))))


(defn- resolve-anchor
  "Resolve one step's anchor to baked source, or throw. The anchor is
   {:defn sym} plus EITHER :ns (a src/ namespace, munged to a path) OR :file
   (an explicit repo-relative path — used for package impls under
   resources/packages/, which have namespaces but do not live under src/,
   and for the editor's `.js` modules).
   For a defmethod, set :defn to the method symbol and :dispatch to its
   dispatch value; the step is then labelled by the dispatch's name.
   A `.js` :file resolves through the JS declaration scanner instead of
   rewrite-clj; :dispatch is meaningless there."
  [{ns-sym :ns sym :defn file :file dispatch :dispatch}]
  (when-not sym
    (throw (ex-info "step needs :defn" {:ns ns-sym :file file})))
  (let [path (or file (some-> ns-sym ns->path))
        label (cond
                (nil? dispatch) (str sym)
                (keyword? dispatch) (name dispatch)
                :else (str dispatch))]
    (when-not path
      (throw (ex-info "step needs :ns or :file" {:defn sym})))
    (when-not (fs/exists? path)
      (throw (ex-info (str "anchor file missing: " path) {:defn sym})))
    (let [js? (str/ends-with? path ".js")]
      (when (and js? dispatch)
        (throw (ex-info (str ":dispatch is Clojure-only, not valid for " path)
                        {:defn sym})))
      (-> (if js?
            (find-js-decl (slurp path) sym path)
            (find-form (slurp path) sym dispatch path))
          (assoc :file path :ns (str (or ns-sym path)) :defn label
                 :lang (if js? "js" "clj"))))))


;; --- reading-time estimate --------------------------------------------------
;;
;; Displayed per step and summed per block, so a reader can size a session
;; before starting one. Deliberately crude and stated in the docs: prose at
;; ~140 wpm, code slower than prose and Clojure slower than JS (denser lines),
;; plus a fixed per-step cost for orienting on a new form.

(def ^:private prose-wpm 140.0)
(def ^:private code-lpm {"js" 20.0})
(def ^:private code-lpm-default 15.0)
(def ^:private step-overhead-min 0.3)


(defn- estimate-minutes
  [say code lang]
  (let [words (->> (str/replace (or say "") #"[`*\[\]_]" "")
                   (re-seq #"\S+")
                   count)
        loc (->> (str/split-lines (or code "")) (remove str/blank?) count)
        rate (get code-lpm lang code-lpm-default)]
    (/ (math/round (* 10.0 (+ (/ words prose-wpm) (/ loc rate) step-overhead-min)))
       10.0)))


;; --- UI strings -------------------------------------------------------------
;;
;; Every string the page renders that is NOT tour prose. An alternate bake (the
;; Russian tour) overrides them wholesale with `:ui` in its tour.edn, so the
;; chrome speaks the same language as the prose.

(def ^:private ui-strings
  {:min "min"
   :lines "lines"
   :step "step"
   :after "after:"
   :seeAlso "see also"
   :refs "referenced from"
   :sameFile "same file"
   :copyPath "click to copy path:line"
   :copied "copied:"
   :openEmacs "emacs"
   :openGithub "GitHub"
   :emacsHint "open in emacs (devtour.el + org-protocol)"
   :showAll "show all %d lines"
   :collapse "collapse"
   :budget "%s steps across %b blocks — about %t h of reading."
   :introKeys (str "Pick a step on the left, or press <kbd>&rarr;</kbd> to start. "
                   "<kbd>/</kbd> searches names, prose and code; <kbd>?</kbd> lists every key.")
   :stub "Not toured yet — this block is a stub. Code lives under:"
   :stubAdd (str "Add steps to this block in <code class=inl>docs/devtour/tour.edn</code>, "
                 "then run <code class=inl>bb devtour</code>.")
   :resume "Continue where you left off:"
   :noHits "nothing matches"
   :searchHint "search step names, prose and code"
   :cleared "reading progress cleared"
   :clearConfirm "Forget which steps you have read?"
   :find "Search"
   :help "Keys"
   :theme "Theme"
   :clear "Reset"
   :progress "steps read"
   :findTitle "Search the tour"
   :helpTitle "Keyboard"
   :hint "/ search · ? keys"
   :kNext "next step"
   :kPrev "previous step"
   :kBack "back along the path you took"
   :kIntro "back to the intro"
   :kFind "search"
   :kHelp "this list"
   :kExpand "expand / collapse a long form"
   :kCopy "copy path:line of the current form"
   :kEmacs "open the current form in emacs"
   :kEsc "close an overlay"
   :helpNote (str "Progress and theme live in this browser only. "
                  "Every step has its own URL — copy the address bar to share one.")})


;; --- model -----------------------------------------------------------------

(defn- resolve-step
  "Anchor + prose for one step. `:gi` (global spine index), `:n` (1-based
   position within the block) and `:key` (the stable URL/progress id) are
   assigned by the caller; `:see` is resolved in a second pass once every
   step's `:gi` is known."
  [block-id step]
  (let [resolved (resolve-anchor step)]
    (-> resolved
        (assoc :say (:say step)
               :block (name block-id)
               ;; first line of the baked form — what emacs searches for, so
               ;; the editor path survives edits above the anchor exactly like
               ;; the generator's own symbol lookup does.
               :head (first (str/split-lines (:code resolved)))
               :mins (estimate-minutes (:say step) (:code resolved) (:lang resolved)))
        (cond-> (:see step) (assoc :raw-see (:see step))))))


(defn- step-keys
  "Stable per-step ids: `<block>/<defn>`, disambiguated with `~2`, `~3` when a
   block legitimately tours two forms of the same name. Deep links and reading
   progress key on these, so inserting a step must not renumber the others —
   which rules out the global index."
  [steps]
  (let [seen (atom {})]
    (mapv (fn [s]
            (let [base (str (:block s) "/" (:defn s))
                  n (get (swap! seen update base (fnil inc 0)) base)]
              (assoc s :key (if (= 1 n) base (str base "~" n)))))
          steps)))


(defn- link-steps
  "Resolve every step's cross-links, in the order they depend on each other:
   the authored see-also targets first, then the reverse `:refs` backlinks built
   from them (a step should say who points AT it, not only where it points), and
   the same-file `:siblings` — what else the tour covers in this file, the
   question you have while reading one of them. Throws on a see-also target that
   is missing or ambiguous."
  [spine]
  (let [by-gi (into {} (map (juxt :gi identity)) spine)
        by-key (reduce (fn [m s] (update m [(:block s) (:defn s)] (fnil conj []) (:gi s)))
                       {} spine)
        resolve-see
        (fn [owner raw]
          (mapv (fn [pair]
                  (let [k [(name (first pair)) (str (second pair))]
                        hits (get by-key k)]
                    (when-not hits
                      (throw (ex-info (str "see-also target not found: " k
                                           " (from " owner ")") {})))
                    (when (> (count hits) 1)
                      (throw (ex-info (str "see-also ambiguous: " k " (from " owner
                                           ") — target appears " (count hits) "x") {})))
                    {:gi (first hits)
                     :key (:key (by-gi (first hits)))
                     :label (second k)}))
                raw))
        linked (mapv (fn [s]
                       (cond-> s
                         (:raw-see s) (assoc :see (resolve-see (:defn s) (:raw-see s)))))
                     spine)
        by-file (group-by :file linked)
        siblings (into {}
                       (for [s linked
                             :let [others (remove #(= (:gi %) (:gi s)) (by-file (:file s)))]
                             :when (seq others)]
                         [(:gi s) (mapv (fn [o]
                                          {:gi (:gi o) :key (:key o)
                                           :label (str (:defn o))})
                                        (sort-by :gi others))]))
        refs (reduce (fn [m s]
                       (reduce (fn [m {:keys [gi]}]
                                 (update m gi (fnil conj [])
                                         {:gi (:gi s) :key (:key s) :label (:defn s)}))
                               m (:see s)))
                     {} linked)]
    (mapv (fn [s]
            (-> s
                (cond-> (seq (refs (:gi s))) (assoc :refs (vec (refs (:gi s)))))
                (cond-> (seq (siblings (:gi s))) (assoc :siblings (siblings (:gi s))))
                (dissoc :raw-see :block)))
          linked)))


(defn- build-model
  "Resolve every toured block's anchors; validate stubs + :after edges; assign
   each toured step a stable global index and a stable key, then hand the spine
   to `link-steps` for its cross-links. Steps are
   identified by index, NOT by (block, defn) — a block may legitimately tour
   two forms of the same name (e.g. the executor's two `execute`s). Throws with
   block/step context on any bad anchor, and on a see-also target that is
   missing or ambiguous."
  [tour]
  (let [blocks (:blocks tour)
        ids (set (map :id blocks))]
    (doseq [b blocks, a (:after b)]
      (when-not (ids a)
        (throw (ex-info (str "block " (:id b) " :after unknown block " a) {}))))
    (let [gi (atom -1)
          base (vec (for [b blocks]
                      (cond-> {:id (name (:id b))
                               :title (:title b)
                               :status (name (:status b :stub))
                               :summary (:summary b)
                               :paths (:paths b)
                               :after (mapv name (:after b))}
                        (= :toured (:status b))
                        (assoc :steps
                               (step-keys
                                 (vec (for [[i step] (map-indexed vector (:steps b))]
                                        (try (assoc (resolve-step (:id b) step)
                                                    :gi (swap! gi inc) :n (inc i))
                                             (catch Exception e
                                               (throw (ex-info
                                                        (str "block " (:id b) " step " i ": "
                                                             (ex-message e))
                                                        (ex-data e) e)))))))))))
          spine (mapcat #(or (:steps %) []) base)
          finalized (into {} (map (juxt :gi identity)) (link-steps spine))
          blocks' (mapv (fn [b]
                          (cond-> b
                            (:steps b)
                            (as-> b'
                              (let [ss (mapv #(finalized (:gi %)) (:steps b'))]
                                (assoc b' :steps ss
                                       :mins (reduce + 0.0 (map :mins ss)))))))
                        base)]
      {:title (:title tour)
       :intro (:intro tour)
       :repo (:repo tour repo-url)
       :ui (merge ui-strings (:ui tour))
       :mins (reduce + 0.0 (map :mins (mapcat :steps blocks')))
       :blocks blocks'})))


;; --- HTML render -----------------------------------------------------------

(def ^:private css (delay (slurp "scripts/devtour/tour.css")))
(def ^:private js (delay (slurp "scripts/devtour/tour.js")))


(defn- key-rows
  [ui]
  (->> [["&rarr; / j / n" (:kNext ui)]
        ["&larr; / k / p" (:kPrev ui)]
        ["b" (:kBack ui)]
        ["g" (:kIntro ui)]
        ["/" (:kFind ui)]
        ["?" (:kHelp ui)]
        ["e" (:kExpand ui)]
        ["c" (:kCopy ui)]
        ["o" (:kEmacs ui)]
        ["Esc" (:kEsc ui)]]
       (map (fn [[k d]] (str "<tr><td><kbd>" k "</kbd></td><td>" d "</td></tr>")))
       (str/join)))


(defn- page
  ^String [model]
  (let [data (-> (json/generate-string model)
                 ;; keep the JSON safe inside a <script> element
                 (str/replace "</" "<\\/"))
        ui (:ui model)]
    (str "<!doctype html>
<html lang=\"en\">
<head>
<meta charset=\"utf-8\">
<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">
<title>" (:title model) "</title>
<style>" @css "</style>
</head>
<body>
<header id=\"top\"><h1>" (:title model) "</h1>
<div id=\"crumb\"></div>
<div id=\"tools\">
  <span id=\"prog\" title=\"" (:progress ui) "\"><span id=\"pnum\"></span><span id=\"bar\"><i></i></span></span>
  <button class=\"tbtn\" id=\"btn-find\">" (:find ui) "</button>
  <button class=\"tbtn\" id=\"btn-help\">" (:help ui) "</button>
  <button class=\"tbtn\" id=\"btn-theme\">" (:theme ui) "</button>
  <button class=\"tbtn\" id=\"btn-clear\">" (:clear ui) "</button>
</div></header>
<div id=\"shell\">
  <nav id=\"map\" aria-label=\"tour map\"></nav>
  <main id=\"stage\"></main>
</div>
<footer id=\"nav\">
  <button id=\"back\" title=\"" (:kBack ui) "\">&larr; Back</button>
  <button id=\"prev\">&lsaquo; Prev</button>
  <span id=\"pos\"></span>
  <button id=\"next\">Next &rsaquo;</button>
  <span id=\"hint\">" (:hint ui) "</span>
</footer>
<div class=\"ovl\" id=\"find\" hidden><div class=\"box\" role=\"dialog\" aria-modal=\"true\" aria-label=\"" (:findTitle ui) "\">
  <h3>" (:findTitle ui) "</h3>
  <input id=\"q\" autocomplete=\"off\" spellcheck=\"false\" placeholder=\"" (:searchHint ui) "\">
  <ul id=\"res\"></ul>
</div></div>
<div class=\"ovl\" id=\"help\" hidden><div class=\"box\" role=\"dialog\" aria-modal=\"true\" aria-label=\"" (:helpTitle ui) "\">
  <h3>" (:helpTitle ui) "</h3>
  <div class=\"keys\"><table>" (key-rows ui) "</table><p>" (:helpNote ui) "</p></div>
</div></div>
<div id=\"toast\" hidden></div>
<script id=\"tour-data\" type=\"application/json\">" data "</script>
<script>" @js "</script>
</body>
</html>
")))


;; --- org render -------------------------------------------------------------
;;
;; The reading path for an editor rather than a browser: prose in org, and a
;; link that opens the REAL file at the anchored form (`::<first line>` — org's
;; literal search, so the link survives edits above it just like the bake).
;; No elisp required; `C-c C-o` is enough.

(defn- org-inline
  "The tour's tiny markdown -> org markup."
  [s]
  (-> (or s "")
      (str/replace #"\*\*([^*]+)\*\*" "*$1*")
      (str/replace #"`([^`]+)`" "~$1~")
      (str/replace #"\[([^\]]+)\]\(([^)]+)\)" "[[$2][$1]]")))


(defn- org-prose
  "Prose paragraphs, unwrapped (EDN strings carry the source indentation) and
   never able to open an org heading."
  [s]
  (->> (str/split (or s "") #"\n\s*\n")
       (map #(-> (org-inline %)
                 (str/replace #"\s*\n\s*" " ")
                 str/trim
                 (str/replace #"^\*" " *")))
       (remove str/blank?)
       (str/join "\n\n")))


(defn- plain-prose
  "Prose unwrapped into paragraphs with the tour's markdown left intact — what
   devtour.el fontifies itself."
  [s]
  (->> (str/split (or s "") #"\n\s*\n")
       (map #(-> % (str/replace #"\s*\n\s*" " ") str/trim))
       (remove str/blank?)
       (str/join "\n\n")))


(defn- org-search-target
  "Org file-link search string for the anchored form: the head line up to the
   first square bracket (org link syntax cannot carry one), keeping the
   trailing space so `(defn foo ` cannot match `(defn foobar`. Falls back to
   the line number when nothing usable is left."
  [{:keys [head line]}]
  (let [cut (first (str/split head #"[\[\]]" 2))]
    (if (< (count (str/trim cut)) 5) (str line) cut)))


(defn- org-block
  ^String [model block]
  (let [ui (:ui model)]
    (str "#+title: " (:title block) "\n"
         "#+startup: showall\n"
         "#+options: toc:nil num:nil\n"
         "# generated by `bb devtour` from docs/devtour/tour.edn — do not edit\n\n"
         (org-prose (:summary block)) "\n\n"
         (when (seq (:after block))
           (str (:after ui) " " (str/join ", " (:after block)) "\n\n"))
         "[[file:index.org][index]]"
         (when (:mins block) (str " · ~" (math/round ^double (:mins block)) " " (:min ui)))
         "\n\n"
         (->> (:steps block)
              (map (fn [s]
                     (str "* " (:n s) ". " (:defn s) "\n"
                          ":PROPERTIES:\n"
                          ":CUSTOM_ID: " (:key s) "\n"
                          ":FILE: " (:file s) "\n"
                          ":LINE: " (:line s) "\n"
                          ":MINS: " (:mins s) "\n"
                          ":END:\n\n"
                          (org-prose (:say s)) "\n\n"
                          "- source :: [[file:" src-prefix "/" (:file s)
                          "::" (org-search-target s) "][" (:file s) ":" (:line s) "]]\n"
                          (when (seq (:see s))
                            (str "- " (:seeAlso ui) " :: "
                                 (->> (:see s)
                                      (map (fn [{:keys [key label]}]
                                             (str "[[file:" (first (str/split key #"/"))
                                                  ".org::#" key "][" label "]]")))
                                      (str/join ", "))
                                 "\n"))
                          (when (seq (:siblings s))
                            (str "- " (:sameFile ui) " :: "
                                 (->> (:siblings s)
                                      (map (fn [{:keys [key label]}]
                                             (str "[[file:" (first (str/split key #"/"))
                                                  ".org::#" key "][" label "]]")))
                                      (str/join ", "))
                                 "\n"))
                          (when (seq (:refs s))
                            (str "- " (:refs ui) " :: "
                                 (->> (:refs s)
                                      (map (fn [{:keys [key label]}]
                                             (str "[[file:" (first (str/split key #"/"))
                                                  ".org::#" key "][" label "]]")))
                                      (str/join ", "))
                                 "\n")))))
              (str/join "\n")))))


(defn- org-index
  ^String [model]
  (let [ui (:ui model)]
    (str "#+title: " (:title model) "\n"
         "#+startup: showall\n"
         "#+options: toc:nil num:nil\n"
         "# generated by `bb devtour` from docs/devtour/tour.edn — do not edit\n\n"
         (org-prose (:intro model)) "\n\n"
         "* Blocks\n\n"
         (->> (:blocks model)
              (map (fn [b]
                     (str "- " (if (= "toured" (:status b))
                                 (str "[[file:" (:id b) ".org][" (:title b) "]]"
                                      " (" (count (:steps b)) " · ~"
                                      (math/round ^double (:mins b)) " " (:min ui) ")")
                                 (str (:title b) " — " (:stub ui)))
                          " :: " (org-prose (:summary b)))))
              (str/join "\n"))
         "\n")))


(defn- org-files
  "Relative filename -> content for the whole org tree."
  [model]
  (into {"index.org" (org-index model)}
        (for [b (:blocks model) :when (= "toured" (:status b))]
          [(str (:id b) ".org") (org-block model b)])))


;; --- eld render -------------------------------------------------------------
;;
;; The same model as elisp data, for docs/devtour/devtour.el: the tour driving
;; a live buffer instead of a baked snapshot of one. Code is deliberately NOT
;; emitted — emacs reads the file itself, so it can never show stale source.

(defn- el-str
  ^String [s]
  (str \" (-> (str s) (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")) \"))


(defn- el-links
  [links]
  (if (seq links)
    (str "(" (str/join " " (map #(str "(" (el-str (:key %)) " . " (el-str (:label %)) ")")
                                links)) ")")
    "nil"))


(defn- eld
  ^String [model]
  (str ";; -*- lisp-data -*-\n"
       ";; generated by `bb devtour` from docs/devtour/tour.edn — do not edit.\n"
       ";; Consumed by docs/devtour/devtour.el; every step carries the anchor\n"
       ";; (:file + :head) rather than baked source, so emacs shows live code.\n"
       "(:title " (el-str (:title model))
       "\n :mins " (format "%.1f" (:mins model))
       "\n :blocks\n ("
       (->> (:blocks model)
            (map (fn [b]
                   (str "(:id " (el-str (:id b))
                        " :title " (el-str (:title b))
                        " :status " (el-str (:status b))
                        " :mins " (format "%.1f" (or (:mins b) 0.0))
                        " :summary " (el-str (:summary b))
                        "\n   :steps\n   ("
                        (->> (:steps b)
                             (map (fn [s]
                                    (str "(:key " (el-str (:key s))
                                         " :n " (:n s) " :gi " (:gi s)
                                         " :defn " (el-str (:defn s))
                                         " :ns " (el-str (:ns s))
                                         " :file " (el-str (:file s))
                                         " :line " (:line s)
                                         " :lang " (el-str (:lang s))
                                         " :mins " (format "%.1f" (:mins s))
                                         " :head " (el-str (:head s))
                                         " :see " (el-links (:see s))
                                         " :refs " (el-links (:refs s))
                                         " :siblings " (el-links (:siblings s))
                                         "\n     :say " (el-str (plain-prose (:say s))) ")")))
                             (str/join "\n    "))
                        "))")))
            (str/join "\n  "))
       "))\n"))


;; --- entry -----------------------------------------------------------------

(defn- outputs
  "Every baked artefact: path -> content. One map, so build and check cannot
   drift apart."
  [model]
  (into {out-html (page model)
         out-eld (eld model)}
        (for [[f content] (org-files model)]
          [(str (fs/path out-org f)) content])))


(defn- build!
  []
  (let [tour (-> tour-edn slurp read-string)
        model (build-model tour)
        files (outputs model)]
    (fs/create-dirs out-org)
    ;; a renamed/removed block must not leave a stale .org behind
    (doseq [f (fs/glob out-org "*.org")
            :let [p (str f)]
            :when (not (contains? files p))]
      (fs/delete f))
    (doseq [[path content] files]
      (fs/create-dirs (fs/parent path))
      (spit path content))
    (println "devtour: wrote" (count files) "files"
             (str "(" (count (mapcat :steps (:blocks model))) " steps, "
                  (count (:blocks model)) " blocks, ~"
                  (math/round ^double (/ (:mins model) 60.0)) "h)")
             (str "— " out-html))))


(defn- check!
  []
  (let [tour (-> tour-edn slurp read-string)
        model (build-model tour)          ; throws on any broken anchor
        files (outputs model)
        stale (concat
                (for [[path content] files
                      :when (not= content (when (fs/exists? path) (slurp path)))]
                  path)
                (for [f (fs/glob out-org "*.org")
                      :when (not (contains? files (str f)))]
                  (str f " (orphan)")))]
    (when (seq stale)
      (binding [*out* *err*]
        (println "devtour-check FAILED: baked output is stale"
                 "(source drifted from the tour):")
        (run! #(println "  -" %) stale)
        (println "  Run `bb devtour` and commit the regenerated files."))
      (System/exit 1))
    (println "devtour-check OK:"
             (count (mapcat :steps (:blocks model))) "anchors resolve;"
             (count files) "baked files up to date")))


(let [cmd (first *command-line-args*)]
  (try
    (case cmd
      "check" (check!)
      (build!))
    (catch clojure.lang.ExceptionInfo e
      (binding [*out* *err*]
        (println "devtour FAILED:" (ex-message e)))
      (System/exit 1))))
