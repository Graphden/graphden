(ns graphden.base-functions.strings
  "String manipulation base functions.

   Functions: str, subs, str-len, str-upper, str-lower, str-trim, str-split, str-join"
  (:require
    [clojure.string :as str]
    [graphden.base-functions.validation :as v]
    [graphden.fn-registry.macros :refer [defbase]]
    [graphden.storage-protocol.config :as config]))


(defbase str-fn
  {:args {:args :jsonb}
   :return-type :text}
  (str/join args))


(defbase subs-fn
  {:args {:s :text, :start :int, :end {:type :int :required false}}
   :return-type :text}
  (let [len (count s)]
    (v/validate-string-index! start len :start)
    (if end
      (do
        (v/validate-start-end-order! start end)
        (v/validate-string-index! end len :end)
        (subs s start end))
      (subs s start))))


(defbase str-len-fn
  {:args {:s :text}
   :return-type :int}
  (count s))


(defbase str-upper-fn
  {:args {:s :text}
   :return-type :text}
  (str/upper-case s))


(defbase str-lower-fn
  {:args {:s :text}
   :return-type :text}
  (str/lower-case s))


(defbase str-trim-fn
  {:args {:s :text}
   :return-type :text}
  (str/trim s))


;; === Regex safety ===
;; Prevent ReDoS (Regular Expression Denial of Service) attacks
;; Limits are configurable via config/*max-regex-length*, etc.

(defn- safe-compile-regex
  "Compiles regex pattern with safety checks.
   Returns compiled pattern or throws with descriptive error.

   Safety measures:
   - Pattern length limit (prevents complex patterns)
   - Compilation timeout (catches slow compilation)
   - Catches all regex engine exceptions

   Limits are configurable via config/*max-regex-length* and
   config/*regex-compile-timeout-ms* dynamic vars.

   Note: On timeout, we call future-cancel to signal cancellation.
   While regex compilation doesn't respond to thread interruption,
   cancelling prevents result delivery and allows GC of the future."
  [pattern-str]
  (let [max-len config/*max-regex-length*
        timeout-ms config/*regex-compile-timeout-ms*]
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
          ;; Cancel future to prevent result delivery and allow GC.
          ;; Note: regex compilation won't actually stop, but the future
          ;; will be marked as cancelled and won't hold references.
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


(defbase str-split-fn
  {:args {:s :text, :sep :text}
   :return-type :jsonb}
  (when (empty? sep)
    (throw (ex-info "separator cannot be empty"
                    {:type :execution-error/invalid-separator
                     :separator sep})))
  (let [max-input-len config/*max-regex-input-length*]
    (when (> (count s) max-input-len)
      (throw (ex-info "Input string too long for regex split"
                      {:type :execution-error/input-too-large
                       :input-length (count s)
                       :max-length max-input-len
                       :hint "Use streaming or chunked processing for large inputs"})))
    (let [pattern (safe-compile-regex sep)]
      (vec (str/split s pattern)))))


(defbase str-join-fn
  {:args {:coll :jsonb, :sep {:type :text :required false}}
   :return-type :text}
  (str/join (or sep "") coll))


;; === Exports ===

(def string-defs
  {:str str-fn
   :subs subs-fn
   :str-len str-len-fn
   :str-upper str-upper-fn
   :str-lower str-lower-fn
   :str-trim str-trim-fn
   :str-split str-split-fn
   :str-join str-join-fn})
