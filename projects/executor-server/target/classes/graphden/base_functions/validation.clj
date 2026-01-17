(ns graphden.base-functions.validation
  "Shared validation helpers for base functions.

   Provides consistent error handling for common validation patterns:
   - Index bounds checking for string/collection operations
   - Size limits for generated collections
   - Input validation with descriptive error messages")


;; === Index Validation ===

(defn validate-index-non-negative!
  "Validates that an index is non-negative.
   Throws :execution-error/invalid-index if negative."
  [index index-name context]
  (when (neg? index)
    (throw (ex-info (str index-name " index cannot be negative")
                    (merge {:type :execution-error/invalid-index
                            index-name index}
                           context)))))


(defn validate-index-in-bounds!
  "Validates that an index is within bounds [0, max-value].
   Throws :execution-error/index-out-of-bounds if out of bounds."
  [index max-value index-name context]
  (when (> index max-value)
    (throw (ex-info (str index-name " index out of bounds")
                    (merge {:type :execution-error/index-out-of-bounds
                            index-name index
                            :max-value max-value}
                           context)))))


(defn validate-start-end-order!
  "Validates that end >= start for range operations.
   Throws :execution-error/invalid-index if end < start."
  [start end]
  (when (< end start)
    (throw (ex-info "end index cannot be less than start"
                    {:type :execution-error/invalid-index
                     :start start
                     :end end}))))


(defn validate-string-index!
  "Validates an index for string operations.
   Checks: non-negative and within string length."
  [index string-length index-name]
  (validate-index-non-negative! index index-name {:string-length string-length})
  (validate-index-in-bounds! index string-length index-name {:string-length string-length}))


;; === Size Validation ===

(defn validate-collection-size!
  "Validates that a collection size is within allowed limits.
   Throws with specified error-type if exceeded.

   Parameters:
   - size: the requested size
   - max-size: maximum allowed size
   - error-type: keyword for :type in ex-data
   - context: additional data to include in ex-data
   - message (optional): custom error message, defaults to generic format"
  ([size max-size error-type context]
   (validate-collection-size! size max-size error-type context
                              (str "size " size " exceeds max allowed " max-size)))
  ([size max-size error-type context message]
   (when (> size max-size)
     (throw (ex-info message
                     (merge {:type error-type
                             :size size
                             :max-size max-size}
                            context))))))


(defn validate-non-negative-count!
  "Validates that a count/size is non-negative.
   Throws :execution-error/invalid-count if negative.

   Parameters:
   - n: the count to validate
   - param-name: keyword name of the parameter (for ex-data)
   - message (optional): custom error message, defaults to 'param-name cannot be negative'"
  ([n param-name]
   (validate-non-negative-count! n param-name (str (name param-name) " cannot be negative")))
  ([n param-name message]
   (when (neg? n)
     (throw (ex-info message
                     {:type :execution-error/invalid-count
                      param-name n})))))


(defn validate-non-zero!
  "Validates that a value is non-zero.
   Throws :execution-error/invalid-value if zero."
  [value param-name message]
  (when (zero? value)
    (throw (ex-info message
                    {:type :execution-error/invalid-value
                     param-name value}))))
