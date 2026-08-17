(ns graphden.packages.base-fn-isolation-test
  "Mechanical guard for the CLAUDE.md rule:

     'base-fn MUST NOT call another base-fn — that's hidden composition,
      it must live in the graph as a fn-def.'

   Until now this was enforced only by human review + the
   `graphden-packages-quality` skill. This test makes a violation a red
   build.

   HOW IT WORKS (static, no container / DB — just reads the source):
   `(defbase foo [..] body)` expands to `(defn foo [__args ctx] body)`,
   so a base-fn calling another base-fn shows up as a call to a
   `defbase`-defined symbol. We detect the two forms that are
   unambiguous in source:

     1. UNQUALIFIED call `(bar ...)` where `bar` is `defbase`'d in the
        SAME namespace. A defbase shadows any clojure.core var of the
        same name (the ns `:refer-clojure :exclude`s it), so `(map ...)`
        inside a ns that `(defbase map ...)` genuinely means the base-fn
        — no clojure.core false positives.
     2. QUALIFIED call `(alias/bar ...)` where `alias` resolves (via the
        ns `:require`) to another impls namespace that `defbase`s `bar`.

   SCOPE / honest limits: this catches DIRECT base-fn→base-fn calls in a
   defbase body — the hard rule. Composition hidden one hop away inside a
   private `defn-` helper is deliberately OUT of scope here (a helper is
   not itself registered, so it's not mechanically a 'base-fn calling a
   base-fn'); that softer smell stays with the `graphden-packages-quality`
   skill's human judgment. Self-recursion (a base-fn calling ITSELF) is
   allowed — the rule is about calling *another* base-fn."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest is]]
    [clojure.walk :as walk]))


(defn- impls-files
  "All impls.clj under the package trees, as File objects."
  []
  (->> ["resources/packages" "external-packages"]
       (map io/file)
       (filter #(java.io.File/.exists %))
       (mapcat file-seq)
       (filter #(= "impls.clj" (java.io.File/.getName %)))))


(defn- read-all-forms
  "Read every top-level form from a source file."
  [^java.io.File f]
  (with-open [r (java.io.PushbackReader. (io/reader f))]
    (let [eof (Object.)]
      (loop [acc []]
        (let [form (try (read {:eof eof :read-cond :allow} r)
                        (catch Exception _ eof))]
          (if (identical? form eof)
            acc
            (recur (conj acc form))))))))


(defn- ns-form->info
  "From a top-level `(ns …)` form, extract {:ns sym :aliases {alias → ns}}.
   Only `:require` `[ns :as alias]` shapes are needed (that's how impls
   reference each other)."
  [ns-form]
  (let [ns-sym (second ns-form)
        requires (->> ns-form
                      (filter #(and (seq? %) (= :require (first %))))
                      (mapcat rest))
        aliases (into {}
                      (keep (fn [spec]
                              (when (vector? spec)
                                (let [[nm & opts] spec
                                      m (apply hash-map opts)]
                                  (when-let [a (:as m)] [a nm])))))
                      requires)]
    {:ns ns-sym :aliases aliases}))


(defn- defbase-name
  [form]
  (when (and (seq? form) (= 'defbase (first form)))
    (second form)))


(defn- analyze-file
  "Return {:ns :aliases :defbases #{names} :bodies {name → body-forms}}."
  [f]
  (let [forms (read-all-forms f)
        ns-form (first (filter #(and (seq? %) (= 'ns (first %))) forms))
        {:keys [ns aliases]} (ns-form->info ns-form)
        defbases (->> forms (keep defbase-name) set)
        bodies (into {}
                     (keep (fn [form]
                             (when-let [nm (defbase-name form)]
                               ;; `(defbase name docstring? [args] & body)`:
                               ;; drop defbase+name, then the optional
                               ;; docstring, then the arg vector.
                               (let [after (nnext form)
                                     after (cond-> after (string? (first after)) rest)
                                     body (rest after)]
                                 [nm body]))))
                     forms)]
    {:file f :ns ns :aliases aliases :defbases defbases :bodies bodies}))


(defn- called-symbols
  "Every symbol appearing in call position within `body-forms`."
  [body-forms]
  (let [calls (volatile! #{})]
    (walk/postwalk
      (fn [x]
        (when (and (seq? x) (symbol? (first x)))
          (vswap! calls conj (first x)))
        x)
      body-forms)
    @calls))


(defn- violations-for
  "Base-fn→base-fn calls in one file, given the global {ns → #{defbases}}."
  [{:keys [file ns aliases defbases bodies]} defbases-by-ns]
  (for [[caller body] bodies
        called (called-symbols body)
        :let [nm (name called)
              qual (namespace called)
              hit (cond
                    ;; qualified: alias/bar → resolve alias to a target ns
                    qual (let [target (get aliases (symbol qual) (symbol qual))]
                           (when (contains? (get defbases-by-ns target) (symbol nm))
                             target))
                    ;; unqualified: same-ns defbase (shadows core), not self
                    (and (contains? defbases (symbol nm))
                         (not= (symbol nm) caller))
                    ns)]
        :when hit]
    {:file (java.io.File/.getPath file)
     :base-fn caller
     :calls (symbol nm)
     :in-ns hit}))


(deftest no-base-fn-calls-another-base-fn
  (let [files    (impls-files)
        analyses (map analyze-file files)
        defbases-by-ns (into {} (map (juxt :ns :defbases)) analyses)
        violations (mapcat #(violations-for % defbases-by-ns) analyses)]
    ;; Sanity: `impls-files` resolves relative paths and filters on
    ;; `.exists`, so a wrong cwd or a renamed package tree yields an
    ;; EMPTY corpus → zero violations → a green no-op that silently
    ;; stops enforcing the rule. Assert the corpus actually loaded.
    (is (some (fn [a] (seq (:defbases a))) analyses)
        (str "empty base-fn corpus — this guard is a no-op. impls.clj "
             "files found: " (count files) ". Check the cwd / package tree."))
    (is (empty? violations)
        (str "Base-fn calling another base-fn is hidden composition — "
             "move it into a fn-def (see CLAUDE.md § Base Function "
             "Philosophy). Offenders:\n"
             (str/join
               "\n"
               (map #(format "  %s/%s calls base-fn `%s` (%s)"
                             (:file %) (:base-fn %) (:calls %) (:in-ns %))
                    violations))))))
