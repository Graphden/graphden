(ns graphden.packages.core.strings.impls
  "Implementations for core/strings base functions.

   Migrated to `defbase` — arg symbols resolve at use site."
  (:require
    [clojure.string :as str]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.storage.protocol.core :as sp]))


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


(defbase str-upper-fn [string]
  (str/upper-case string))


(defbase str-lower-fn [string]
  (str/lower-case string))


(defbase str-trim-fn [string]
  (str/trim string))


(defbase str-split-fn [string separator]
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
      (vec (str/split string pattern)))))


(defbase str-join-fn [coll separator]
  (str/join separator coll))


(defbase str-to-keyword-fn [string]
  (keyword string))


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


(defbase parse-query-string-fn
  "Parses a URL query string or form-urlencoded body into a map.
   Splits by & then = and URL-decodes values."
  [string]
  (when (and string (not (str/blank? string)))
    (into {}
          (for [pair (str/split string #"&")
                :let [[k v] (str/split pair #"=" 2)]
                :when k]
            [k (java.net.URLDecoder/decode (or v "") "UTF-8")]))))


(defbase blank?-fn [string]
  (str/blank? string))


(defbase url-decode-fn [string]
  (java.net.URLDecoder/decode string "UTF-8"))


(defbase str-contains?-fn [string substring]
  (boolean (and string (str/includes? string substring))))


(defbase str-starts-with?-fn [string prefix]
  (boolean (and string (str/starts-with? string prefix))))


(defbase str-replace-fn [s match replacement]
  (when s (str/replace s match replacement)))


;; === Registry ===

(def impls
  {:str str-fn
   :subs subs-fn
   :str-len str-len-fn
   :str-upper str-upper-fn
   :str-lower str-lower-fn
   :str-trim str-trim-fn
   :str-split str-split-fn
   :str-join str-join-fn
   :str-to-keyword str-to-keyword-fn
   :keyword-to-str keyword-to-str-fn
   :pr-str pr-str-fn
   :to-str to-str-fn
   :parse-query-string parse-query-string-fn
   :blank? blank?-fn
   :url-decode url-decode-fn
   :str-contains? str-contains?-fn
   :str-starts-with? str-starts-with?-fn
   :str-replace str-replace-fn})
