(ns graphden.packages.corpus-seals-test
  "No first-party fn-def breaks a seal the API would refuse. The boot
   package sync writes without `write-rej`, so a corpus fn-def that
   re-binds an ancestor's value, binds a `:terminal` slot or appends to a
   `:closed` list would land silently and teach every reader of that fn
   the opposite of lesson 07.

   No DB: the corpus is parsed into the exact records the boot sync
   writes (`composition/fn-defs->records`, base-fns included) and the
   three seal rules run over that in-memory view through the same
   `validation/ancestor-seal-rej` the bundle path uses — every binding
   and list item, EVERY violation reported (the rules the API applies
   one row at a time; the other write-time rules — cycles, MI, route
   shape — have their own guards)."
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.crud.validation :as v]
    [graphden.executor.composition.core :as composition]
    [graphden.packages.loader :as loader]
    [graphden.packages.records :as records]
    [graphden.packages.sync :as sync]))


(def ^:private package-set
  "The shipped first-party packages — the prod `:package-names` list."
  ["core" "storage" "web" "app-base" "app" "registry" "mcp"])


(defn corpus-records
  "Every record the boot sync of `package-names` writes — base-fns
   (`registry/sync-defs-to-storage!`'s parse) then the composed fn-defs
   (`sync-fns-to-storage!`'s), with the same cross-set name→id map."
  [package-names]
  (let [packages (loader/load-packages package-names)
        all-name->id (sync/compute-all-fn-name-ids packages)
        base-defs (into {}
                        (keep (fn [[n fd]] (when n [n (assoc fd :name n)])))
                        (:base-fn-defs packages))]
    (concat (records/parse-module (vec (vals base-defs)) all-name->id)
            (composition/fn-defs->records (:fn-defs packages) all-name->id base-defs))))


(defn seal-violations
  "Every `:binding` / `:binding-list-item` record that breaks an
   ancestor's seal, as `{:rule :fn :slot …}` — the rules
   `value-override-rej` / `terminal-rej` / `list-closed-rej` apply on
   the API path: an ancestor's `:terminal` or value/ref seals the slot
   for any binding; `:list-closed` seals it for an append or an item."
  [recs]
  (let [by-kind (group-by :kind recs)
        fns (into {} (map (juxt :id identity)) (:fn by-kind))
        slots (into {} (map (juxt :id identity)) (:slot by-kind))
        bindings (into {} (map (juxt (juxt :fn-id :slot-id) identity)) (:binding by-kind))
        binding-by-id (into {} (map (juxt :id identity)) (:binding by-kind))
        view {:parents-of (comp :parent-ids fns)
              :binding-of (fn [fid sid] (get bindings [fid sid]))}
        rej (fn [b list-write?]
              (v/ancestor-seal-rej view (:fn-id b) (:slot-id b) {:list-write? list-write?}))
        describe (fn [b r what]
                   {:rule (:type r)
                    :what what
                    :fn (:name (fns (:fn-id b)))
                    :parents (mapv (comp :name fns) (:parent-ids (fns (:fn-id b))))
                    :slot (:name (slots (:slot-id b)))
                    :value (:value b)
                    :ref (some-> (:ref-fn-id b) fns :name)})]
    (vec
      (concat
        (keep (fn [b]
                (when-let [r (or (rej b false)
                                 (when (true? (:list-append b)) (rej b true)))]
                  (describe b r :binding)))
              (:binding by-kind))
        (keep (fn [item]
                (when-let [b (binding-by-id (:binding-id item))]
                  (when-let [r (rej b true)]
                    (describe b r :list-item))))
              (:binding-list-item by-kind))))))


(deftest every-corpus-binding-respects-the-seals
  (let [recs (corpus-records package-set)]
    (is (seq (filter #(= :binding (:kind %)) recs)) "the corpus parsed to no bindings")
    (is (empty? (seal-violations recs))
        (str "corpus bindings / list items that break a seal: "
             (pr-str (seal-violations recs))))))
