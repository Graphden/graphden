(ns graphden.versioning.storage.field-diff
  "The per-field before/after view of two resolved versions of one
   entity, in the human form the editor shows: fn-typed refs as the
   referenced fn's `:name`, strings bare, everything else `pr-str`,
   all truncated for row display. Shared by the branch diff's display
   model (`diff-view`) and the merge conflict payload (`merge`) — one
   rendering of \"what differs\", whichever surface asks."
  (:require
    [graphden.storage.protocol.core :as sp]
    [graphden.versioning.storage.resolution :as res]))


(def ref-fields
  "Version-map fields whose value is a fn-id — displayed as the
   referenced fn's name instead of a bare uuid."
  #{:ref-fn-id :type-override-fn-id :resolver-fn-id :return-type-fn-id
    :base-fn-id :element-fn-id :type-fn-id})


(defn short-id
  [id]
  (some-> id str (subs 0 8)))


(defn truncate
  [s n]
  (let [s (str s)]
    (if (> (count s) n) (str (subs s 0 (dec n)) "…") s)))


(defn fn-label
  "`:name` when `fn-names` knows the id, else `#<8 hex>`."
  [fn-names id]
  (if-let [n (get fn-names id)]
    (str ":" n)
    (str "#" (short-id id))))


(defn display-value
  "Human form of one field value: fn-refs become `:name`, strings stay
   bare, everything else pr-str — all truncated for row display."
  [fn-names field v]
  (cond
    (nil? v) "∅"
    (and (contains? ref-fields field) (uuid? v)) (fn-label fn-names v)
    (string? v) (truncate v 120)
    ;; Bound the print itself — a page-sized hiccup value must not be
    ;; fully serialized just to keep its first 120 chars.
    :else (truncate (binding [*print-length* 24 *print-level* 4] (pr-str v))
                    120)))


(defn changed-fields
  "The sorted fields whose value differs between two resolved rows;
   `:created-at` differs on every version row and is not content."
  [sv tv]
  (->> (into #{} (concat (keys sv) (keys tv)))
       (remove #{:created-at})
       (filter #(not= (get sv %) (get tv %)))
       (sort)))


(defn field-entries
  "`[{:field \"name\" :source <display> :target <display>} …]` for the
   fields that differ between `sv` and `tv` — the wire shape of a
   per-field diff."
  [fn-names sv tv]
  (vec (for [f (changed-fields sv tv)]
         {:field (name f)
          :source (display-value fn-names f (get sv f))
          :target (display-value fn-names f (get tv f))})))


(defn ref-ids
  "Every fn-id a `ref-fields` field of `rows` points at."
  [rows]
  (set (for [row rows
             [k v] row
             :when (and (contains? ref-fields k) (uuid? v))]
         v)))


(defn- names-on
  "`{fn-id name}` for `ids` resolved on `branch-id` — the identity row
   stands in for a fn with no version there (a base-fn), so its name
   still resolves."
  [base-storage ids branch-id]
  (let [rows (vals (sp/read-entities base-storage :fn (vec ids)))]
    (res/resolve-entities-batch base-storage :fn rows branch-id)))


(defn resolve-fn-names
  "Best-effort `{fn-id name}` for `ids`: resolved on the source branch
   first, then the target for the remainder. Anonymous / vanished fns
   simply stay absent from the map."
  [base-storage ids source-branch-id target-branch-id]
  (if (empty? ids)
    {}
    (let [src (names-on base-storage ids source-branch-id)
          ;; Retry on the target only ids that did not resolve AT ALL —
          ;; an anonymous fn resolved with a nil name stays anonymous on
          ;; every branch; re-querying it buys nothing.
          missing (set (remove #(some? (get src %)) ids))
          tgt (when (seq missing)
                (names-on base-storage missing target-branch-id))]
      (into {}
            (keep (fn [id]
                    (when-let [n (or (:name (get src id))
                                     (:name (get tgt id)))]
                      [id n])))
            ids))))
