(ns graphden.graph-storage-age.interface
  "Apache AGE storage implementation - full Storage with graph-optimized queries.

   This storage uses Apache AGE (PostgreSQL graph extension) to store the function
   graph. The key advantage over plain PostgreSQL is that resolve-execution-graph
   can be done in a SINGLE Cypher query instead of O(depth) recursive queries.

   ## Architecture

   Unlike the postgres-storage + cache-postgres stack which requires:
   1. Recursive BFS queries to resolve the execution graph
   2. Caching layer to avoid repeated traversals

   AGE storage resolves the complete graph in one traversal:
   ```cypher
   MATCH path = (root:Fn {id: $fn_id})-[:DEPENDS_ON*0..100]->(dep:Fn)
   MATCH (dep)-[:HAS_SCHEMA]->(schema:FnSchema)
   MATCH (schema)-[:HAS_ARG]->(arg:ArgSchema)
   OPTIONAL MATCH (dep)-[:HAS_VALUE]->(val:ArgValue)
   RETURN ...
   ```

   ## Graph Schema

   Nodes:
   - (:FnSchema {id, name, returned_type, base_fn_name, impl_hash})
   - (:ArgSchema {id, fn_schema_id, name, type, required})
   - (:Fn {id, name, fn_schema_id})
   - (:ArgValue {id, arg_schema_id, value_type, value})
   - (:CallSite {id, fn_id, name})

   Edges:
   - (:Fn)-[:HAS_SCHEMA]->(:FnSchema)
   - (:Fn)-[:HAS_ARG {arg_schema_id}]->(:ArgValue)
   - (:Fn)-[:DEPENDS_ON]->(:Fn) - transitive dependency
   - (:CallSite)-[:CALLS]->(:Fn)
   - (:CallSite)-[:HAS_ARG {arg_schema_id}]->(:ArgValue)

   ## Usage

   ```clojure
   (def storage (create-storage {:jdbc-url \"jdbc:postgresql://localhost:5432/graphden\"
                                 :username \"user\"
                                 :password \"pass\"}))
   (sp/initialize storage schema)
   ;; Use storage...
   (sp/close storage)
   ```"
  (:require
    [graphden.graph-storage-age.core :as core]
    [graphden.storage-protocol.interface :as sp]))


(def ^:dynamic *query-timeout-ms*
  "Timeout for queries in milliseconds."
  sp/*query-timeout-ms*)


(defn create-storage
  "Creates an Apache AGE storage instance.

   Options:
   - :jdbc-url - JDBC connection URL (required)
   - :username - database username
   - :password - database password
   - :pool-size - connection pool size (default 10)
   - :graph-name - AGE graph name (default \"graphden\")

   Returns a storage implementing all Storage protocols."
  [opts]
  (core/create-storage opts))
