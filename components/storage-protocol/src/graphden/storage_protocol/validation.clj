(ns graphden.storage-protocol.validation
  "CRUD and credential validation utilities.

   Contains:
   - Required field validation
   - Duplicate ID detection
   - Entity name validation
   - Credential security validation
   - Canonical field types")


;; === CRUD validation utilities ===


(defn validate-required-fields!
  "Validates that all required (non-nullable) fields are present and not nil.
   Throws ExceptionInfo if validation fails.

   This is SHAPE-ONLY validation - it checks presence/nil but NOT types.
   Type validation happens at the storage backend level during actual
   insert/update operations. This separation allows for:
   - Fast presence checks before hitting the database
   - Backend-specific type coercion (e.g., string→UUID in PostgreSQL)

   Arguments:
   - entity-name: keyword name of the entity
   - fields: map of {field-name {:type ... :nullable? ...}}
   - data: the data map being validated

   Throws ExceptionInfo with :type :validation-error/required-field-missing
   if a required field is missing or nil."
  [entity-name fields data]
  (doseq [[field-name field-spec] fields]
    (when (and (not= field-name :id)  ; :id is auto-generated
               (not (:nullable? field-spec))
               (or (not (contains? data field-name))
                   (nil? (get data field-name))))
      (throw (ex-info (str "Required field '" (name field-name) "' is missing or nil")
                      {:type :validation-error/required-field-missing
                       :entity entity-name
                       :field field-name})))))


(defn validate-no-duplicate-ids!
  "Validates that there are no duplicate IDs in a batch of records.
   Throws ExceptionInfo if duplicate IDs are found.

   Arguments:
   - entity-name: keyword name of the entity
   - data-seq: sequence of data maps, each may have an :id field

   Throws ExceptionInfo with :type :validation-error/duplicate-ids
   if duplicate IDs are found in the batch."
  [entity-name data-seq]
  (let [explicit-ids (->> data-seq
                          (map :id)
                          (filter some?))
        id-counts (frequencies explicit-ids)
        duplicates (->> id-counts
                        (filter (fn [[_ cnt]] (> cnt 1)))
                        (map first))]
    (when (seq duplicates)
      (throw (ex-info (str "Duplicate IDs found in batch: " (pr-str duplicates))
                      {:type :validation-error/duplicate-ids
                       :entity entity-name
                       :duplicate-ids (vec duplicates)})))))


(defn validate-data-is-map!
  "Validates that data is a map for CRUD operations.
   Throws ExceptionInfo if data is not a map.

   Arguments:
   - entity-name: keyword name of the entity
   - data: the data to validate

   Throws ExceptionInfo with :type :invalid-data if data is not a map."
  [entity-name data]
  (when-not (map? data)
    (throw (ex-info "data must be a map"
                    {:type :invalid-data
                     :entity-name entity-name
                     :data data
                     :data-type (type data)}))))


(defn validate-where-clause!
  "Validates that where clause is nil or a map for query operations.
   Throws ExceptionInfo if where is not nil or a map.

   Arguments:
   - where: the where clause to validate

   Throws ExceptionInfo with :type :invalid-where-clause if invalid."
  [where]
  (when (and (some? where) (not (map? where)))
    (throw (ex-info "where clause must be nil or a map"
                    {:type :invalid-where-clause
                     :where where
                     :where-type (type where)}))))


(defn validate-where-clause-fields!
  "Validates that all keys in where clause are known fields for the entity.
   Prevents queries against non-existent fields which could indicate
   programming errors or injection attempts.

   Arguments:
   - entity-name: keyword name of the entity
   - fields: map of {field-name field-spec} for the entity
   - where: the where clause map to validate

   Throws ExceptionInfo with :type :validation-error/unknown-field if
   a where clause key doesn't match a known field."
  [entity-name fields where]
  (when (and (some? where) (map? where))
    (let [known-fields (set (keys fields))
          ;; :id is always valid even if not in fields
          valid-fields (conj known-fields :id)]
      (doseq [k (keys where)]
        (when-not (contains? valid-fields k)
          (throw (ex-info (str "Unknown field '" (name k) "' in where clause for entity '" (name entity-name) "'")
                          {:type :validation-error/unknown-field
                           :entity entity-name
                           :field k
                           :known-fields (vec (sort known-fields))})))))))


(defn- check-type-match
  "Checks if a value matches the expected field type.
   Returns nil if valid, or error-map {:expected ... :actual ...} if invalid.

   This is a soft check for common types - exotic types pass through
   to allow backend-specific handling."
  [value field-type]
  (let [actual-type (cond
                      (nil? value) :nil
                      (uuid? value) :uuid
                      (string? value) :text
                      (integer? value) :int
                      (boolean? value) :bool
                      (or (float? value) (decimal? value)) :numeric
                      (inst? value) :timestamptz
                      (bytes? value) :bytes
                      (keyword? value) :enum
                      (or (map? value) (vector? value) (sequential? value)) :jsonb
                      :else :unknown)]
    ;; nil is valid for any nullable field - caller handles nullability
    (when (and (not= actual-type :nil)
               (not= actual-type :unknown)
               (not= actual-type field-type)
               ;; Allow common compatible types
               (not (and (= field-type :jsonb)
                         (#{:text} actual-type)))
               (not (and (= field-type :union)
                         true))  ; union accepts anything
               (not (and (= field-type :ref)
                         (= actual-type :uuid)))
               (not (and (= field-type :numeric)
                         (= actual-type :int))))
      {:expected field-type :actual actual-type})))


(defn validate-where-clause-types!
  "Validates that values in where clause match the expected field types.
   This catches type mismatches early before hitting the database.

   Arguments:
   - entity-name: keyword name of the entity
   - fields: map of {field-name {:type ... :nullable? ...}} for the entity
   - where: the where clause map to validate

   Throws ExceptionInfo with :type :validation-error/type-mismatch if
   a where clause value doesn't match the field's expected type.

   Note: This is a soft validation for common types. Exotic types and
   backend-specific coercion are allowed to pass through."
  [entity-name fields where]
  (when (and (some? where) (map? where))
    (doseq [[k v] where]
      (when-let [field-spec (or (get fields k)
                                (when (= k :id) {:type :uuid}))]
        (when-let [error (check-type-match v (:type field-spec))]
          (throw (ex-info (str "Type mismatch for field '" (name k) "' in where clause: "
                               "expected " (name (:expected error)) ", got " (name (:actual error)))
                          {:type :validation-error/type-mismatch
                           :entity entity-name
                           :field k
                           :expected-type (:expected error)
                           :actual-type (:actual error)
                           :value-type (type v)})))))))


(defn validate-entity-name!
  "Validates that entity-name is a safe keyword for storage operations.
   Prevents SQL injection and other attacks via malicious entity names.

   Entity names must be:
   - A keyword (not nil, not a string)
   - Start with a letter
   - Contain only lowercase letters, digits, hyphens, and underscores
   - Not exceed 64 characters

   Arguments:
   - entity-name: the entity name to validate
   - operation: string describing the operation (for error messages)

   Throws ExceptionInfo with :type :invalid-entity-name if validation fails."
  [entity-name operation]
  (when-not (keyword? entity-name)
    (throw (ex-info (str "entity-name must be a keyword for " operation)
                    {:type :invalid-entity-name
                     :entity-name entity-name
                     :entity-name-type (type entity-name)
                     :operation operation})))
  (let [name-str (name entity-name)]
    (when (> (count name-str) 64)
      (throw (ex-info (str "entity-name exceeds maximum length (64) for " operation)
                      {:type :invalid-entity-name
                       :entity-name entity-name
                       :length (count name-str)
                       :operation operation})))
    (when-not (re-matches #"^[a-z][a-z0-9_-]*$" name-str)
      (throw (ex-info (str "entity-name contains invalid characters for " operation
                           ". Must start with a letter and contain only lowercase letters, digits, hyphens, and underscores.")
                      {:type :invalid-entity-name
                       :entity-name entity-name
                       :operation operation})))))


;; === Credential validation utilities ===
;;
;; Security-focused validation for database credentials to prevent:
;; - Buffer overflow attacks via extremely long strings
;; - DoS attacks via memory exhaustion
;; - Injection attacks via control characters

(def ^:const max-username-length
  "Maximum allowed length for database username.
   PostgreSQL supports up to 63 characters for identifiers.
   We use 128 to allow for potential domain prefixes (e.g., AD\\user)."
  128)


(def ^:const max-password-length
  "Maximum allowed length for database password.
   Most password managers support up to 128 characters.
   We use 1024 as a generous limit for certificates/tokens."
  1024)


(def ^:const max-jdbc-url-length
  "Maximum allowed length for JDBC URL.
   Standard URLs shouldn't exceed 2048 characters.
   We use 4096 to allow for complex connection strings."
  4096)


(defn validate-credential-length!
  "Validates that a credential string doesn't exceed maximum length.
   Throws ex-info with :type :config-error/credential-too-long on failure.

   Arguments:
   - value: the string to validate
   - param-name: name of the parameter for error messages
   - max-length: maximum allowed length

   This is a security measure to prevent:
   - Buffer overflow attacks
   - DoS via memory exhaustion
   - Log pollution from huge strings"
  [value param-name max-length]
  (when (and (string? value) (> (count value) max-length))
    (throw (ex-info (str param-name " exceeds maximum length of " max-length " characters")
                    {:type :config-error/credential-too-long
                     :param param-name
                     :max-length max-length
                     :actual-length (count value)}))))


(defn validate-no-control-chars!
  "Validates that a string doesn't contain control characters.
   Throws ex-info with :type :config-error/invalid-credential on failure.

   Control characters (ASCII 0-31, 127) in credentials can indicate:
   - Null byte injection attempts
   - Escape sequence attacks
   - Binary data mistakenly passed as string

   Excludes tabs (9), newlines (10), and carriage returns (13) as they
   may appear legitimately in some credential formats."
  [value param-name]
  (when (string? value)
    (when-let [_dangerous-chars (re-find #"[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]" value)]
      (throw (ex-info (str param-name " contains invalid control characters")
                      {:type :config-error/invalid-credential
                       :param param-name
                       :reason "contains control characters"})))))


(defn validate-credentials!
  "Validates database credentials for security.
   Checks both username and password for:
   - Maximum length limits
   - Control character injection

   Arguments:
   - username: database username string
   - password: database password string

   Throws ex-info with :type :config-error/* on validation failure.

   Example:
     (validate-credentials! \"myuser\" \"mypass\") ; => nil (valid)
     (validate-credentials! (apply str (repeat 200 \"x\")) \"pass\")
     ; => throws :config-error/credential-too-long"
  [username password]
  (validate-credential-length! username "username" max-username-length)
  (validate-credential-length! password "password" max-password-length)
  (validate-no-control-chars! username "username")
  (validate-no-control-chars! password "password"))


(defn validate-jdbc-url!
  "Validates JDBC URL for security.
   Checks for:
   - Maximum length limits
   - Control character injection

   Arguments:
   - jdbc-url: JDBC connection URL string

   Throws ex-info with :type :config-error/* on validation failure."
  [jdbc-url]
  (validate-credential-length! jdbc-url "jdbc-url" max-jdbc-url-length)
  (validate-no-control-chars! jdbc-url "jdbc-url"))


;; === Canonical Field Types ===
;;
;; Standard field types supported by all storage backends.
;; Each backend maps these to their native types.

(def canonical-field-types
  "Set of canonical field types supported by all storage backends.
   - :uuid - UUID/GUID values
   - :text - Variable-length text strings
   - :int - 64-bit integers (BIGINT)
   - :bool - Boolean true/false
   - :numeric - Arbitrary precision decimal numbers
   - :timestamptz - Timestamp with timezone
   - :jsonb - JSON data (stored as native JSON or EDN string)
   - :bytes - Binary data
   - :ref - Reference to another entity (stored as UUID)
   - :enum - Enumerated value (backend-specific storage)
   - :union - Union type (stored as JSON/EDN)"
  #{:uuid :text :int :bool :numeric :timestamptz :jsonb :bytes :ref :enum :union})


(defn canonical-type?
  "Returns true if the type keyword is a canonical field type."
  [type-kw]
  (contains? canonical-field-types type-kw))


(def type-category
  "Categorizes field types for common handling patterns.
   - :primitive - Simple scalar values
   - :reference - References to other entities
   - :complex - Structured data types"
  {:uuid        :primitive
   :text        :primitive
   :int         :primitive
   :bool        :primitive
   :numeric     :primitive
   :timestamptz :primitive
   :bytes       :primitive
   :jsonb       :complex
   :ref         :reference
   :enum        :primitive
   :union       :complex})


(defn reference-type?
  "Returns true if the field type is a reference type (:ref)."
  [type-kw]
  (= :reference (get type-category type-kw)))


(defn complex-type?
  "Returns true if the field type is a complex type (:jsonb, :union)."
  [type-kw]
  (= :complex (get type-category type-kw)))
