(ns graphden.storage-protocol.credential-validation
  "Credential validation utilities for database connections.

   Security-focused validation for database credentials to prevent:
   - Buffer overflow attacks via extremely long strings
   - DoS attacks via memory exhaustion
   - Injection attacks via control characters")


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
   - Log injection via newlines
   - Binary data mistakenly passed as string

   ALL control characters are rejected including tabs, newlines, and carriage
   returns. While these may appear in some formats, they are security risks in
   credentials (can be used for log injection, header splitting, etc.)."
  [value param-name]
  (when (string? value)
    (when-let [_dangerous-chars (re-find #"[\x00-\x1F\x7F]" value)]
      (throw (ex-info (str param-name " contains invalid control characters")
                      {:type :config-error/invalid-credential
                       :param param-name
                       :reason "contains control characters (including tabs, newlines, carriage returns)"})))))


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
