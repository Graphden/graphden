(ns graphden.packages.owned
  "Process-wide registry of fn-ids the PACKAGE SYNC wrote this boot.

   Populated by `packages.sync/register-base-fns-from-packages!` (the
   deterministic name→id map covering base-fns + composed fn-defs,
   plus the 14 primitives) on every boot — exactly the set that the
   declarative sync would restore on the next restart. Consulted by
   `crud.package-guard` to refuse editor-API writes against those fns
   (the 2026-08-20 :add poisoning class).

   Deliberately in-memory, not a DB column: membership is a property
   of THIS deployment's bundled packages, re-derived each boot; a
   fresh test storage without a package bootstrap correctly has no
   protected fns."
  (:refer-clojure :exclude []))


(defonce ^:private owned-ids
  (atom #{}))


(defn record-owned-ids!
  "Add `ids` (fn-ids the package sync just wrote) to the registry."
  [ids]
  (swap! owned-ids into (filter some? ids)))


(defn owned-fn-id?
  "True iff `fn-id` was written by the package sync this boot."
  [fn-id]
  (contains? @owned-ids fn-id))


(defn reset-owned-ids!
  "Test hygiene — empty the registry."
  []
  (reset! owned-ids #{}))
