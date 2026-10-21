(ns graphden.packages.owned
  "Process-wide registry of fn-ids the PACKAGE SYNC wrote this boot.

   Populated by `packages.sync/register-base-fns-from-packages!` (the
   deterministic name→id map covering base-fns + composed fn-defs,
   plus the primitives, `records.ids/primitive-names`) and successful trusted
   package writer receipts (including anonymous type rows) on every boot —
   exactly the set that the
   declarative sync would restore on the next restart. Consulted by
   `crud.package-guard` to refuse editor-API writes against those fns
   (the :add poisoning class).

   Deliberately in-memory, not a DB column: membership is a property
   of THIS deployment's bundled packages, re-derived each boot; a
   fresh test storage without a package bootstrap correctly has no
   protected fns."
  (:refer-clojure :exclude []))


(defonce ^:private owned-ids
  (atom #{}))


;; Package reloads replace Vars while existing executor contexts keep their
;; original implementations. Weak references retain the trusted identities
;; without keeping obsolete contexts alive. Index by fn-id, then compare the
;; referent itself: WeakHashMap would use user-defined equals/hashCode on a Fn.
(defonce ^:private owned-impls
  (atom {}))


(defn- referent
  [reference]
  (java.lang.ref.Reference/.get reference))


(defn record-owned-ids!
  "Add `ids` (fn-ids the package sync just wrote) to the registry."
  [ids]
  (swap! owned-ids into (filter some? ids)))


(defn owned-fn-id?
  "True iff `fn-id` was written by the package sync this boot."
  [fn-id]
  (contains? @owned-ids fn-id))


(defn record-owned-impls!
  "Remember trusted package implementations as {fn-id impl} pairs."
  [id->impl]
  (swap! owned-impls
         (fn [by-id]
           (reduce-kv
             (fn [registered id impl]
               (if-not (fn? impl)
                 registered
                 (update registered id
                         (fn [references]
                           (let [live (filterv (comp some? referent) references)]
                             (if (some #(identical? impl (referent %)) live)
                               live
                               (conj live (java.lang.ref.WeakReference. impl))))))))
             by-id id->impl))))


(defn owned-impl?
  "True iff this exact implementation was loaded for `fn-id` by package sync."
  [fn-id impl]
  (and (fn? impl)
       (boolean (some #(identical? impl (referent %))
                      (get @owned-impls fn-id)))))
