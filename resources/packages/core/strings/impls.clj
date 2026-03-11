(ns graphden.packages.core.strings.impls
  "Implementations for core/strings base functions.

   All functions receive already-dereferenced arguments.
   The loader handles deref before calling these implementations."
  (:require
    [clojure.string :as str]
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

(defn str-fn
  [{:keys [args]}]
  (str/join args))


(defn subs-fn
  [{:keys [s start end]}]
  (let [len (count s)]
    (validate-string-index! start len :start)
    (if end
      (do
        (validate-start-end-order! start end)
        (validate-string-index! end len :end)
        (subs s start end))
      (subs s start))))


(defn str-len-fn
  [{:keys [s]}]
  (count s))


(defn str-upper-fn
  [{:keys [s]}]
  (str/upper-case s))


(defn str-lower-fn
  [{:keys [s]}]
  (str/lower-case s))


(defn str-trim-fn
  [{:keys [s]}]
  (str/trim s))


(defn str-split-fn
  [{:keys [s sep]}]
  (when (empty? sep)
    (throw (ex-info "separator cannot be empty"
                    {:type :execution-error/invalid-separator
                     :separator sep})))
  (let [max-input-len sp/*max-regex-input-length*]
    (when (> (count s) max-input-len)
      (throw (ex-info "Input string too long for regex split"
                      {:type :execution-error/input-too-large
                       :input-length (count s)
                       :max-length max-input-len
                       :hint "Use streaming or chunked processing for large inputs"})))
    (let [pattern (safe-compile-regex sep)]
      (vec (str/split s pattern)))))


(defn str-join-fn
  [{:keys [coll sep]}]
  (str/join (or sep "") coll))


(defn str-to-keyword-fn
  [{:keys [s]}]
  (keyword s))


(defn keyword-to-str-fn
  [{:keys [k]}]
  (if (keyword? k)
    (name k)
    (str k)))


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
   :keyword-to-str keyword-to-str-fn})
