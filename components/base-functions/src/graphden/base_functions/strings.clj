(ns graphden.base-functions.strings
  "String manipulation base functions.

   Functions: str, subs, str-len, str-upper, str-lower, str-trim, str-split, str-join"
  (:require
    [clojure.string :as str]
    [graphden.fn-registry.macros :refer [defbase]]))


(defbase str-fn
  {:args {:args :jsonb}
   :return-type :text}
  (str/join args))


(defbase subs-fn
  {:args {:s :text, :start :int, :end {:type :int :required false}}
   :return-type :text}
  (let [len (count s)]
    (when (neg? start)
      (throw (ex-info "start index cannot be negative"
                      {:type :execution-error/invalid-index
                       :start start :string-length len})))
    (when (> start len)
      (throw (ex-info "start index out of bounds"
                      {:type :execution-error/index-out-of-bounds
                       :start start :string-length len})))
    (if end
      (do
        (when (< end start)
          (throw (ex-info "end index cannot be less than start"
                          {:type :execution-error/invalid-index
                           :start start :end end})))
        (when (> end len)
          (throw (ex-info "end index out of bounds"
                          {:type :execution-error/index-out-of-bounds
                           :end end :string-length len})))
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


(defbase str-split-fn
  {:args {:s :text, :sep :text}
   :return-type :jsonb}
  (when (empty? sep)
    (throw (ex-info "separator cannot be empty"
                    {:type :execution-error/invalid-separator
                     :separator sep
                     :string s})))
  (try
    (vec (str/split s (re-pattern sep)))
    (catch java.util.regex.PatternSyntaxException e
      (throw (ex-info "Invalid regex pattern in separator"
                      {:type :execution-error/invalid-regex
                       :separator sep
                       :cause (Throwable/.getMessage e)})))))


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
