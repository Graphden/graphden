(ns graphden.packages.core.strings.impls
  "Implementations for core/strings base functions.

   Arg symbols resolve at use site.

   String fns are CONTENT-PASSING: anything that takes a `:text`
   (or related) input and returns text / int / bool potentially
   exposes the contents downstream. They register the standard
   `:secret`-propagator from `graphden.types.core` so a secret input
   taints the result. The propagator only changes type-level
   metadata; impl bodies are unchanged."
  (:require
    [clojure.string :as str]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.storage.protocol.core :as sp]
    [graphden.types.core :as types]))


;; === Validation Helpers ===

(defn- validate-string-index!
  [idx len field-name]
  (when (or (neg? idx) (> idx len))
    (throw (ex-info (format "%s index %d out of bounds [0, %d]" (name field-name) idx len)
                    {:type :execution-error/string-index-out-of-bounds
                     field-name idx
                     :length len}))))


(defn- validate-start-end-order!
  [start end]
  (when (> start end)
    (throw (ex-info (format "start index %d cannot be greater than end index %d" start end)
                    {:type :execution-error/invalid-args
                     :start start
                     :end end}))))


;; === Regex Safety ===

(defn- safe-compile-regex
  "Compiles regex pattern with safety checks.
   Returns compiled pattern or throws with descriptive error."
  [pattern-str]
  (let [max-len sp/*max-regex-length*
        timeout-ms sp/*regex-compile-timeout-ms*]
    (when (> (count pattern-str) max-len)
      (throw (ex-info "Regex pattern too long"
                      {:type :execution-error/regex-too-complex
                       :pattern-length (count pattern-str)
                       :max-length max-len
                       :hint "Use simpler separator or literal string"})))
    (let [fut (future
                (try
                  {:pattern (re-pattern pattern-str)}
                  (catch java.util.regex.PatternSyntaxException e
                    {:error :syntax :cause (Throwable/.getMessage e)})
                  (catch Exception e
                    {:error :engine :cause (Throwable/.getMessage e)})))
          result (deref fut timeout-ms ::timeout)]
      (cond
        (= result ::timeout)
        (do
          (future-cancel fut)
          (throw (ex-info "Regex pattern too complex (compilation timeout)"
                          {:type :execution-error/regex-too-complex
                           :separator pattern-str
                           :timeout-ms timeout-ms})))

        (:pattern result)
        (:pattern result)

        (= (:error result) :syntax)
        (throw (ex-info "Invalid regex pattern syntax"
                        {:type :execution-error/invalid-regex
                         :separator pattern-str
                         :cause (:cause result)}))

        :else
        (throw (ex-info "Regex engine error"
                        {:type :execution-error/regex-engine-error
                         :separator pattern-str
                         :cause (:cause result)}))))))


;; === Implementations ===

(defbase str-fn [parts]
  (str/join parts))


(defbase subs-fn [string start end]
  (let [len (count string)]
    (validate-string-index! start len :start)
    (if end
      (do
        (validate-start-end-order! start end)
        (validate-string-index! end len :end)
        (subs string start end))
      (subs string start))))


(defbase str-len-fn [string]
  (count string))


(defbase byte-len-fn [string]
  ;; UTF-8 byte length — distinct from :str-len (UTF-16 code units) for
  ;; enforcing byte-sized budgets (jsonb payload caps).
  (alength (String/.getBytes ^String string "UTF-8")))


(defbase str-upper-fn [string]
  (str/upper-case string))


(defbase str-lower-fn [string]
  (str/lower-case string))


(defbase str-trim-fn [string]
  (str/trim string))


(defbase str-split-fn [string separator limit]
  (when (empty? separator)
    (throw (ex-info "separator cannot be empty"
                    {:type :execution-error/invalid-separator
                     :separator separator})))
  (let [max-input-len sp/*max-regex-input-length*]
    (when (> (count string) max-input-len)
      (throw (ex-info "Input string too long for regex split"
                      {:type :execution-error/input-too-large
                       :input-length (count string)
                       :max-length max-input-len
                       :hint "Use streaming or chunked processing for large inputs"})))
    (let [pattern (safe-compile-regex separator)]
      (vec (if (and limit (pos? (long limit)))
             (str/split string pattern (long limit))
             (str/split string pattern))))))


(defbase str-join-fn [coll separator]
  (str/join separator coll))


(defbase str-to-keyword-fn [string]
  (keyword string))


(defn str-to-keyword-return-rule
  "When the input is statically known to be non-null `:text`, the
   result is non-null `:keyword`. The declared `[:union :null
   :keyword]` only kicks in when the input genuinely admits nil
   (the nullable `[:union :null :text]` slot type covers callers
   like `:_qs-pair-k`). Narrowing here means a chain like
   `:str-to-keyword :string {:as :request-method :type :text}`
   surfaces as `:keyword` on the editor's type chip instead of the
   broader nullable union."
  [bindings-info default-ret]
  (let [s-type (get-in bindings-info [:string :type])]
    (cond
      (= s-type :text)    :keyword
      (= s-type :null)    :null
      :else               default-ret)))


(defbase keyword-to-str-fn [keyword]
  (if (keyword? keyword)
    (name keyword)
    (str keyword)))


(defbase pr-str-fn
  "Returns a string representation of value for debugging/display."
  [value]
  (pr-str value))


(defbase to-str-fn
  "Converts any value to string using str."
  [value]
  (str value))


(defbase name-fn
  "Clojure's `(name x)` — strip the leading `:` from a keyword or
   return a string as-is. Used at boundaries that expect a bare
   identifier text (entity-type slot names, SQL identifiers, etc.)
   rather than `(str :kw)`'s `\":kw\"` form."
  [value]
  (cond
    (string? value) value
    (keyword? value) (name value)
    (nil? value) nil
    :else (str value)))


;; `:name`'s declared :return-type is the safe `[:union :null :text]`
;; (because `(name nil) = nil`). When the input type is known to be
;; non-null (`:keyword` / `:text`), narrow the return to plain `:text`
;; — propagates record-field knowledge through `(name (get coll :key))`
;; chains. Mirrors the philosophy of `:get`'s rule: typed input narrows
;; typed output without polluting the contract elsewhere.
(defn name-return-rule
  [bindings-info default-ret]
  (let [vt (get-in bindings-info [:value :type])]
    (cond
      (nil? vt) default-ret
      (or (= vt :keyword) (= vt :text)) :text
      (= vt :null) :null
      (types/union-type? vt)
      (let [mapped (mapv (fn [m]
                           (cond
                             (or (= m :keyword) (= m :text)) :text
                             (= m :null) :null
                             :else default-ret))
                         (types/union-members vt))]
        (if (some #{default-ret} mapped)
          default-ret
          (types/make-union mapped)))
      :else default-ret)))


(defbase blank?-fn [string]
  (str/blank? string))


(defbase non-blank?-fn
  "True iff `:string` is non-blank. Required-slot 1-arg companion to
   `:blank?` so it can serve as a `:filter` / `:some` HOF predicate
   (optional slots aren't bound as HOF lambda-params)."
  [string]
  (not (str/blank? string)))


(defbase url-decode-fn [string]
  ;; `URLDecoder/decode` throws IllegalArgumentException on malformed
  ;; percent-encoding (a lone `%`, `%zz`, …). This runs on UNTRUSTED input
  ;; — `:parse-query-string` / `:parse-form-body` decode public form field
  ;; values (login / registration / contact) — so an uncaught throw would
  ;; 500 those endpoints. Fail soft to the raw string, matching the
  ;; defensive-boundary convention of `parse-uuid` / `str-to-uuid`.
  (try (java.net.URLDecoder/decode string "UTF-8")
       (catch IllegalArgumentException _ string)))


(defbase str-contains?-fn [string substring]
  (boolean (and string (str/includes? string substring))))


(defbase str-starts-with?-fn [string prefix]
  (boolean (and string (str/starts-with? string prefix))))


(defbase str-replace-fn [string match replacement]
  (when string (str/replace string match replacement)))


(defbase re-find?-fn
  "True iff the regex `pattern` matches somewhere inside `string`.
   Returns false for nil / non-string `string`. Compiled regex is
   cached behind the safe-compile boundary (size + complexity caps
   shared with `:str-split`)."
  [string pattern]
  (boolean (and (string? string)
                (re-find (safe-compile-regex pattern) string))))


(defbase re-replace-fn
  "Replace every match of the regex `pattern` in `string` with
   `replacement` — `clojure.string/replace` over a compiled pattern
   (the regex sibling of the literal-only `:str-replace`). Nil
   `string` flows through unchanged. Compilation goes through the
   same safe-compile boundary (size + complexity caps) as
   `:str-split` / `:re-find?`."
  [string pattern replacement]
  (when string (str/replace string (safe-compile-regex pattern) replacement)))


;; === Registry ===

(def impls
  ;; Every entry is a `{:impl … :taint-propagate? true}` map —
  ;; secret-content-passing is the default for the strings package.
  ;; Anything that takes a `:text` (or a record containing one) and
  ;; returns text / int / bool potentially leaks the content via the
  ;; result; the propagator marks the result `[:secret …]` so the
  ;; downstream type-check refuses to drop the marker.
  ;;
  ;; The handful of fns that genuinely DON'T pass content
  ;; (`:keyword-to-str`'s input is a `:keyword`, never a text-secret)
  ;; are still annotated, since the propagator is a no-op for plain
  ;; inputs. `:parse-query-string` is now a pure graph composition
  ;; — its taint flows through `:str-split` + `:url-decode`.
  {:str                {:impl str-fn                :taint-propagate? true}
   :subs               {:impl subs-fn               :taint-propagate? true}
   :str-len            {:impl str-len-fn            :taint-propagate? true}
   :byte-len           {:impl byte-len-fn           :taint-propagate? true}
   :str-upper          {:impl str-upper-fn          :taint-propagate? true}
   :str-lower          {:impl str-lower-fn          :taint-propagate? true}
   :str-trim           {:impl str-trim-fn           :taint-propagate? true}
   :str-split          {:impl str-split-fn          :taint-propagate? true}
   :str-join           {:impl str-join-fn           :taint-propagate? true}
   :str-to-keyword     {:impl str-to-keyword-fn     :return-type-rule str-to-keyword-return-rule :taint-propagate? true}
   :keyword-to-str     {:impl keyword-to-str-fn     :taint-propagate? true}
   :pr-str             {:impl pr-str-fn             :taint-propagate? true}
   :to-str             {:impl to-str-fn             :taint-propagate? true}
   :name               {:impl name-fn               :return-type-rule name-return-rule :taint-propagate? true}
   :blank?             {:impl blank?-fn             :taint-propagate? true}
   :non-blank?         {:impl non-blank?-fn         :taint-propagate? true}
   :url-decode         {:impl url-decode-fn         :taint-propagate? true}
   :str-contains?      {:impl str-contains?-fn      :taint-propagate? true}
   :str-starts-with?   {:impl str-starts-with?-fn   :taint-propagate? true}
   :str-replace        {:impl str-replace-fn        :taint-propagate? true}
   :re-find?           {:impl re-find?-fn           :taint-propagate? true}
   :re-replace         {:impl re-replace-fn         :taint-propagate? true}})
