(ns graphden.executor-runtime.fn-definitions
  "Function definitions for the executor runtime.

   This namespace defines fn entities as data that will be created in storage.
   These are 'regular' functions (not base-fns) that compose base functions
   through the graph.

   ## Architecture

   The web server is built as a graph:

   web-server-fn (fn entity)
     ├── inherits from: http-server (base-fn schema)
     ├── handler arg -> default-router-handler-fn (fn ref)
     └── port arg -> 8080 (literal)

   default-router-handler-fn (fn entity)
     └── inherits from: default-router-handler (base-fn schema)
     (no args needed - routes are hardcoded in base-fn for MVP)

   ## Why fn entities?

   Even though default-router-handler and http-server are base-fns,
   we create fn entities for them so they can be:
   1. Referenced from other fns (web-server-fn -> default-router-handler-fn)
   2. Extended by users (create child fn with different port)
   3. Queried from storage (find all web servers, etc.)

   The fn entity is the 'instance' that binds args, while fn-schema
   (created from base-fn) defines the signature."
  (:require
    [graphden.fn-registry.interface :as registry]
    [graphden.storage-protocol.interface :as sp]))


;; === Function Definition Helpers ===

(defn- get-fn-schema-id
  "Gets the fn-schema id for a base function by name."
  [base-fn-name]
  (registry/fn-schema-uuid base-fn-name))


(defn- get-arg-schema-id
  "Gets the arg-schema id for a base function's argument."
  [base-fn-name arg-name]
  (registry/arg-schema-uuid base-fn-name arg-name))


;; === Create Functions in Storage ===

(defn create-router-handler-fn!
  "Creates the default-router-handler fn entity in storage.
   This fn has no args (routes are hardcoded in the base-fn).

   Returns the created fn entity."
  [storage]
  (let [fn-schema-id (get-fn-schema-id :default-router-handler)]
    (sp/create-entity storage :fn
                      {:name "default-router-handler-fn"
                       :fn-schema-id fn-schema-id})))


(defn create-web-server-fn!
  "Creates the web-server fn entity in storage.
   This fn inherits from http-server base-fn and binds:
   - handler -> reference to default-router-handler-fn
   - port -> 8080 (literal)

   Arguments:
   - storage: storage instance
   - router-handler-fn: the router handler fn entity (for reference)
   - port: port number (default 8080)

   Returns the created fn entity."
  [storage router-handler-fn port]
  (let [fn-schema-id (get-fn-schema-id :http-server)
        handler-arg-id (get-arg-schema-id :http-server :handler)
        port-arg-id (get-arg-schema-id :http-server :port)
        ;; Create the fn entity
        web-server-fn (sp/create-entity storage :fn
                                        {:name "web-server-fn"
                                         :fn-schema-id fn-schema-id})]
    ;; Create arg-values
    ;; handler -> ref to router-handler-fn (type :fn, so passed as callable)
    ;; Value is just the fn-id (UUID), storage/executor handle the reference semantics
    (sp/create-entity storage :arg-value
                      {:owner-fn-id (:id web-server-fn)
                       :arg-schema-id handler-arg-id
                       :value (:id router-handler-fn)})
    ;; port -> literal value
    (sp/create-entity storage :arg-value
                      {:owner-fn-id (:id web-server-fn)
                       :arg-schema-id port-arg-id
                       :value port})
    web-server-fn))


(defn create-all-fns!
  "Creates all fn entities for the executor runtime.

   Arguments:
   - storage: initialized storage with base-fn schemas synced
   - port: port for the web server (default 8080)

   Returns a map of created fn entities:
   {:router-handler-fn fn-entity
    :web-server-fn fn-entity}"
  [storage port]
  (let [router-handler-fn (create-router-handler-fn! storage)
        web-server-fn (create-web-server-fn! storage router-handler-fn port)]
    {:router-handler-fn router-handler-fn
     :web-server-fn web-server-fn}))


;; === Startup Configuration ===

(def startup-fn-name
  "Name of the function to execute at startup."
  "web-server-fn")
