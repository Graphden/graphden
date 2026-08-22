(ns graphden.test-hygiene-guard-test
  "Mechanical rules about the TESTS themselves, enforced by reading them.

   Two rules today, both from the 2026-08-22 test audit.

   ## 1. An asserting `catch` must be insured against a silent no-throw

   ```clojure
   (try
     (validate! :user {} {})               ; ← stops throwing after a refactor
     (catch clojure.lang.ExceptionInfo e
       (is (= :required-field-missing (:type (ex-data e))))))
   ```

   When the expression stops throwing, the `catch` never runs, the `is`
   inside it never executes, and `clojure.test` reports a PASS. The test
   whose entire job is to notice that regression is the one thing that
   cannot. A 2026-08-22 audit found 31 of these, all in error-path tests.

   The fix is one line — an assertion in the `try` BODY that only runs
   when nothing was thrown:

   ```clojure
   (try
     (validate! :user {} {})
     (is false \"expected validate! to throw\")   ; ← the insurance
     (catch clojure.lang.ExceptionInfo e
       (is (= :required-field-missing (:type (ex-data e))))))
   ```

   …or the capture idiom, which needs no insurance because the assertion
   sits outside the `try` and a non-throw fails it:

   ```clojure
   (let [ex (try (validate! :user {} {})
                 ::no-throw
                 (catch clojure.lang.ExceptionInfo e e))]
     (is (not= ::no-throw ex))
     (is (= :required-field-missing (:type (ex-data ex)))))
   ```

   Reader-based, not line-based: a `(is` inside a docstring or a comment
   must not count either way.

   ## 2. A `deftest` name is unique across the suite

   `clojure.test` allows two namespaces to define the same test name, and
   the audit found 36 groups that did — mostly a helper pinned once where
   it is DEFINED and again through a facade that re-exports it. The cost is
   not the duplicated seconds; it is that a failure report names the test,
   the reader opens the wrong file, and a semantic change has to be found
   in two or three places. The rule that removed them: pin a function in
   the test file of the namespace that DEFINES it, and let a re-export get
   an identity assertion rather than a copy of the behaviour.

   Where two tests genuinely have different subjects and collided by
   coincidence (`entities-test` over three different schema builders), the
   fix was a name that says which. There is deliberately NO allowlist here:
   an exception ledger for this rule would be longer than the renames it
   would save."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest is]]
    [clojure.walk :as walk]))


(defn- test-sources
  []
  (->> (file-seq (io/file "test"))
       (filter #(and (java.io.File/.isFile %)
                     (str/ends-with? (java.io.File/.getName %) ".clj")))
       (sort-by java.io.File/.getPath)))


(defn- readable-source
  "File text with alias-qualified auto-resolved keywords (`::cr/thing`)
   flattened to `::thing`. `read` resolves those against the CURRENT ns's
   aliases and throws when the alias is unknown — which it always is here,
   since we never load the file. Only the STRUCTURE matters to this guard,
   so dropping the alias is lossless for our purposes."
  [f]
  (str/replace (slurp f) #"::[A-Za-z][\w.*+!?<>-]*/" "::"))


(defn- forms
  [f]
  (let [rdr (java.io.PushbackReader. (java.io.StringReader. (readable-source f)))]
    (binding [*read-eval* false]
      (loop [acc []]
        (let [form (read {:eof ::eof :read-cond :allow} rdr)]
          (if (= form ::eof) acc (recur (conj acc form))))))))


(defn- insured?
  "True when `try-body` contains an assertion that runs only if nothing
   was thrown — either an explicit `(is false …)` / `(is nil …)`, or a
   re-`throw`, or the `::no-throw`-style sentinel of the capture idiom."
  [body]
  (let [s (pr-str body)]
    (or (str/includes? s "(is false")
        (str/includes? s "(is nil")
        (str/includes? s "(throw ")
        (str/includes? s "no-throw"))))


(defn- uninsured-catch-asserts
  "`[\"<file>\" …]` for every `try` whose catch clauses assert but whose
   body cannot fail when the expression returns normally."
  [f]
  (let [hits (atom [])]
    (doseq [form (forms f)]
      (walk/postwalk
        (fn [x]
          (when (and (seq? x) (= 'try (first x)))
            (let [clause? (fn [sym] #(and (seq? %) (= sym (first %))))
                  catches (filter (clause? 'catch) x)
                  body (remove #(or ((clause? 'catch) %) ((clause? 'finally) %))
                               (rest x))]
              (when (and (str/includes? (pr-str catches) "(is ")
                         (not (insured? body)))
                (swap! hits conj (str (java.io.File/.getPath f) " → "
                                      (let [head (first body)]
                                        (if (seq? head) (str (first head)) (pr-str head))))))))
          x)
        form))
    @hits))


(deftest every-asserting-catch-is-insured-against-a-silent-no-throw-test
  (let [offenders (mapcat uninsured-catch-asserts (test-sources))]
    (is (empty? offenders)
        (str "`(is …)` inside a `catch` with nothing in the `try` body that "
             "fails when the expression does NOT throw — the test passes "
             "silently the day the code stops throwing. Add "
             "`(is false \"expected … to throw\")` after the call, or switch "
             "to the capture idiom (see this namespace's docstring): "
             (pr-str (vec offenders))))))


(defn- deftest-names
  "`{name [file …]}` over every `(deftest …)` in the suite. Line-based on
   purpose: a name is what a failure report prints, and that is what the
   line says. Metadata in the name position (`^:integration`) is skipped."
  []
  (reduce
    (fn [acc f]
      (reduce (fn [acc' line]
                (if-let [[_ nm] (re-find #"^\(deftest\s+(?:\^[^\s]+\s+)*([^\s]+)" line)]
                  (update acc' nm (fnil conj []) (java.io.File/.getPath f))
                  acc'))
              acc
              (str/split-lines (slurp f))))
    {}
    (test-sources)))


(deftest every-deftest-name-is-unique-test
  (let [dups (into (sorted-map) (filter (comp #(> % 1) count val)) (deftest-names))]
    (is (empty? dups)
        (str "deftest name(s) defined in more than one namespace. A failure "
             "report names the test, so a duplicate sends the reader to the "
             "wrong file. Pin a function where it is DEFINED and give the "
             "re-export an identity assertion; if the subjects genuinely "
             "differ, name them so: " (pr-str dups)))))


(deftest the-guard-actually-reads-the-suite-test
  ;; A guard that silently scanned nothing would report a clean suite
  ;; forever. Pin both halves: files are found, and their forms parse.
  (let [files (test-sources)]
    (is (< 200 (count files))
        "the test tree has ~300 .clj files — finding far fewer means the scan root moved")
    (is (some #(str/ends-with? (java.io.File/.getPath %)
                               "storage/protocol/crud_validation_test.clj")
              files))
    (is (< 5 (count (forms (io/file "test/graphden/test_hygiene_guard_test.clj"))))
        "this very file must parse through the same reader path")
    (is (< 1500 (count (deftest-names)))
        "the suite has ~2400 deftests — finding far fewer means the name scan broke")))
