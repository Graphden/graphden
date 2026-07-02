(ns graphden.packages.records
  "Pure conversion of fns.edn entries into records of the new model
   (`fn`, `slot`, `fn-slot`, `binding`, `binding-list-item`).

   Each top-level fn-def in a fns.edn is one of these forms:

     ;; base-fn (impl in impls.clj):
     {:name :http-server
      :args {:handler {:type :fn} :port {:type :port}}
      :return-type :any}

     ;; record-type (no impl, slots define the shape):
     {:name :ring-response-shape
      :type {:status :http-status :headers :jsonb :body :text}}

     ;; refinement-type:
     {:name :positive-int
      :refine {:base :int :constraint [:> 0]}}

     ;; list-type:
     {:name :int-list
      :list :int}

     ;; composed fn-def (parent → bindings):
     {:name :default-auth-fail-response
      :parent :ring-response-shape
      :args {:status 401 :headers {} :body \"Unauthorized\"}}

   This module ONLY parses syntactic shapes and produces records. It
   does NOT touch storage, type-checker, or executor — those live in
   composition layer (rewrite forthcoming). Records are tagged maps:

     {:kind :fn …}
     {:kind :slot …}
     {:kind :fn-slot …}
     {:kind :binding …}
     {:kind :binding-list-item …}

   Each record has `:id` deterministic from its identity-tuple, so
   re-running the parser on the same EDN gives the same UUIDs (allowing
   idempotent upserts).

   ## Roles (matches schema.clj table)

   | parent-ids | return-type-fn-id | base-fn-id | element-fn-id | constraint | fn-slot rows | Role |
   | empty      | NOT NULL          | NULL       | NULL          | NULL       | *            | base-fn |
   | empty      | NULL              | NULL       | NULL          | NULL       | NOT empty    | record-type |
   | empty      | NULL              | NOT NULL   | NULL          | NOT NULL   | empty        | refinement-type |
   | empty      | NULL              | NULL       | NOT NULL      | NULL       | empty        | list-type |
   | NOT empty  | *                 | *          | *             | *          | *            | composed fn-def |

   ## Structure

   The implementation is split across four focused sub-namespaces; this
   namespace re-exports the public surface so external callers stay
   unchanged:

   - `records.ids`            — deterministic UUID derivation, hashing,
                                primitive boot-data
   - `records.types`          — type-reference resolution, inline
                                fn-type synthesis
   - `records.slot-resolution`— slot resolution through the
                                inheritance + rename chain
   - `records.parse`          — per-form parsers + module entry points
  "
  (:require
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]))


;; -----------------------------------------------------------------------------
;; Re-exports — keep the historical public surface of this namespace.
;; -----------------------------------------------------------------------------

(def fn-id ids/fn-id)
(def anonymous-fn-id ids/anonymous-fn-id)
(def slot-id ids/slot-id)
(def fn-slot-id ids/fn-slot-id)
(def binding-id ids/binding-id)
(def binding-list-item-id ids/binding-list-item-id)
(def digest-hex ids/digest-hex)
(def primitive-names ids/primitive-names)
(def primitive-fn-id ids/primitive-fn-id)
(def primitive-fn-ids ids/primitive-fn-ids)
(def boot-primitive-records ids/boot-primitive-records)
(def parse-fn-def parse/parse-fn-def)
(def parse-module parse/parse-module)
