(ns graphden.executor.checked-source-data
  "Conservative immutable admission for opt-in checker artifacts. This is not
   source authority or general-purpose serialization. Unknown values miss."
  (:require
    [graphden.types.check.provenance :as provenance])
  (:import
    (clojure.lang
      BigInt
      Ratio)
    (java.lang.reflect
      Modifier)
    (java.math
      BigDecimal
      BigInteger)
    (java.util
      IdentityHashMap)))


(def ^:private containers
  {clojure.lang.PersistentArrayMap :map clojure.lang.PersistentHashMap :map
   clojure.lang.PersistentVector :sequence
   clojure.lang.PersistentList :list clojure.lang.PersistentList$EmptyList :sequence
   clojure.lang.PersistentHashSet :sequence})


(def ^:private scalar-classes
  #{nil String Boolean Character java.util.UUID clojure.lang.Keyword clojure.lang.Symbol
    Long Integer Short Byte Double Float BigDecimal BigInteger BigInt Ratio})


(defn static-callback?
  "Caller must separately approve deterministic checker behavior and ABI.
   Refuse closures with retained instance captures, even if explicitly listed."
  [value]
  (and (fn? value) (nil? (meta value))
       (contains? #{clojure.lang.AFunction clojure.lang.RestFn} (Class/.getSuperclass (class value)))
       (every? #(Modifier/isStatic (java.lang.reflect.Field/.getModifiers %))
               (Class/.getDeclaredFields (class value)))))


(defn- approved-callback?
  [callbacks value]
  (and (fn? value) (some #(identical? value %) callbacks)))


(defn- integer-charge
  [^BigInteger value]
  (+ 96 (quot (+ 7 (BigInteger/.bitLength value)) 8)))


;; Direct fields have no Class/.method equivalent; retain typed field access.
^{:splint/disable [#_{:clj-kondo/ignore [:unresolved-namespace]} lint/prefer-method-values]}
(defn- scalar-charge
  [value]
  (cond
    (string? value) (+ 96 (* 2 (count value)))
    (or (keyword? value) (symbol? value))
    (+ 192 (* 2 (+ (count (name value)) (count (namespace value)))))
    (instance? BigInteger value) (integer-charge value)
    (instance? BigInt value) (+ 96 (integer-charge (BigInt/.toBigInteger value)))
    (instance? BigDecimal value) (+ 96 (integer-charge (BigDecimal/.unscaledValue value)))
    (instance? Ratio value) (+ 96 (integer-charge (.-numerator ^Ratio value))
                               (integer-charge (.-denominator ^Ratio value)))
    :else 96))


(defn charge
  "Conservative immutable admission and retained-data estimate. Summarize only
   fully validated known containers within this call; every occurrence still
   charges its full bytes, nodes and relative depth, including metadata.
   Scratch summaries are identity-keyed and bounded to 1024 containers."
  [value {:keys [max-bytes max-nodes callbacks]}]
  (let [charged-bytes (volatile! 0) nodes (volatile! 0)
        summaries (IdentityHashMap.)]
    (letfn [(add!
              [n]
              (and (<= (vswap! nodes +' 1) max-nodes)
                   (<= (vswap! charged-bytes +' n) max-bytes)))
            (members
              [values depth]
              (loop [remaining (seq values) height 0]
                (if remaining
                  (when-some [child (walk (first remaining) (inc depth))]
                    (recur (next remaining) (max height (inc child))))
                  height)))
            (pairs
              [entries depth]
              (loop [remaining (seq entries) height 0]
                (if remaining
                  (let [[k v] (first remaining)]
                    (when-some [key-height (walk k (inc depth))]
                      (when-some [value-height (walk v (inc depth))]
                        (recur (next remaining) (max height (inc key-height) (inc value-height))))))
                  height)))
            (walk
              [v depth]
              (let [kind (get containers (class v))
                    cached (when kind (IdentityHashMap/.get summaries v))]
                (if cached
                  (let [^objects summary cached]
                    (when (and (<= (+ depth (aget summary 2)) 64)
                               (<= (vswap! nodes +' (aget summary 1)) max-nodes)
                               (<= (vswap! charged-bytes +' (aget summary 0)) max-bytes))
                      (aget summary 2)))
                  (let [before-bytes @charged-bytes before-nodes @nodes]
                    (when (and (<= depth 64) (add! (if kind 256 96))
                               (or kind (contains? scalar-classes (class v))
                                   (approved-callback? callbacks v)))
                      (when-some [metadata-height
                                  (if-let [metadata (meta v)]
                                    ;; Only plain immutable metadata can support summary
                                    ;; reuse. Inspect no user map methods before its class.
                                    (when (and (= :map (get containers (class metadata)))
                                               (nil? (meta metadata))
                                               (<= (count metadata) 16)
                                               (every? #(contains? #{clojure.lang.Keyword Object} (class %))
                                                       (keys metadata)))
                                      (when-let [retained (provenance/retained-checker-metadata v)]
                                        (when-some [child (walk retained (inc depth))] (inc child))))
                                    0)]
                        (when-some [member-height
                                    (if kind
                                      (case kind
                                        :map (pairs v depth)
                                        :list (when-some [head (walk (first v) (inc depth))]
                                                (when-some [tail (walk (rest v) (inc depth))]
                                                  (max (inc head) (inc tail))))
                                        (members v depth))
                                      (when (cond
                                              (contains? scalar-classes (class v)) (add! (scalar-charge v))
                                              (approved-callback? callbacks v) (add! 256)
                                              :else false)
                                        0))]
                          (let [height (max metadata-height member-height)
                                node-count (-' @nodes before-nodes)]
                            (when (and kind (>= node-count 8) (< (IdentityHashMap/.size summaries) 1024))
                              (IdentityHashMap/.put summaries v
                                                    (object-array [(-' @charged-bytes before-bytes) node-count height])))
                            height))))))))]
      (when-some [_ (walk value 0)] @charged-bytes))))


(defn- plain-map?
  [value]
  (= :map (get containers (class value))))


(defn- registry-key?
  [value]
  ;; Compaction precedes admission. Never invoke a user key's hashCode/equals
  ;; while probing a registry; unsupported keys remain for ordinary refusal.
  (contains? #{nil String java.util.UUID clojure.lang.Keyword} (class value)))


(defn compact-registry-state
  "Factor identity-shared tenant signatures out of a checker ARTIFACT only.
   Existing keys remain in place as nil placeholders; nonmirrored values,
   metadata and name indexes stay intact. Ordinary immutable admission must
   still inspect the complete resulting artifact, including its rich registry."
  [state]
  (let [eligible? (and (plain-map? state) (plain-map? (:rich state))
                       (plain-map? (get-in state [:rich :by-id]))
                       (plain-map? (:per-org state)))]
    (if-not eligible?
      {:registry state :mirrors {}}
      (let [global (get-in state [:rich :by-id])
            [per-org mirrors]
            (reduce-kv
              (fn [[per-org mirrors] org registry]
                (let [by-id (when (plain-map? registry) (:by-id registry))]
                  (if-not (and (registry-key? org) (plain-map? by-id))
                    [per-org mirrors]
                    (let [ids (into #{} (keep (fn [[id signature]]
                                                (when (and (registry-key? id) (contains? global id)
                                                           (identical? signature (get global id)))
                                                  id))) by-id)]
                      (if (empty? ids)
                        [per-org mirrors]
                        [(assoc per-org org
                                (assoc registry :by-id (reduce #(assoc %1 %2 nil) by-id ids)))
                         (assoc mirrors org ids)])))))
              [(:per-org state) {}] (:per-org state))]
        {:registry (assoc state :per-org per-org) :mirrors mirrors}))))


(defn- invalid-registry-artifact!
  []
  (throw (ex-info "Invalid compact checker registry" {:type ::invalid-registry-artifact})))


(defn expand-registry-state
  "Restore a privately produced compact artifact. References use THIS artifact's
   immutable rich registry, never the live global registry. Replacing existing
   values preserves map order/class and the signature's identity/provenance."
  [artifact]
  (when-not (and (plain-map? artifact) (= #{:registry :mirrors} (set (keys artifact)))
                 (plain-map? (:registry artifact)) (plain-map? (:mirrors artifact)))
    (invalid-registry-artifact!))
  (let [{:keys [registry mirrors]} artifact
        global (when (plain-map? (:rich registry)) (get-in registry [:rich :by-id]))]
    (reduce-kv
      (fn [state org ids]
        (when-not (registry-key? org) (invalid-registry-artifact!))
        (let [per-org (:per-org state)
              org-registry (when (plain-map? per-org) (get per-org org))
              by-id (when (plain-map? org-registry) (:by-id org-registry))]
          (when-not (and (plain-map? global) (plain-map? by-id)
                         (= clojure.lang.PersistentHashSet (class ids))
                         (every? #(and (registry-key? %) (contains? global %) (contains? by-id %)
                                       (nil? (get by-id %))) ids))
            (invalid-registry-artifact!))
          (assoc state :per-org
                 (assoc per-org org
                        (assoc org-registry :by-id
                               (reduce #(assoc %1 %2 (get global %2)) by-id ids))))))
      registry mirrors)))


;; Direct fields have no Class/.method equivalent; retain typed field access.
^{:splint/disable [lint/prefer-method-values]}
(defn same-input?
  "Only for separately admitted immutable inputs. Preserve concrete numeric
   representation, container kind/iteration order and provenance identity."
  [left right]
  (and (= (class left) (class right))
       (identical? (meta left) (meta right))
       (or (identical? left right)
           (if-let [kind (get containers (class left))]
             (and (= (count left) (count right))
                  (case kind
                    :map (every? true? (map (fn [[ak av] [bk bv]]
                                              (and (same-input? ak bk) (same-input? av bv))) left right))
                    :list (and (same-input? (first left) (first right))
                               (same-input? (rest left) (rest right)))
                    (every? true? (map same-input? left right))))
             (cond
               (fn? left) (identical? left right)
               (instance? Ratio left) (and (= (.-numerator ^Ratio left) (.-numerator ^Ratio right))
                                           (= (.-denominator ^Ratio left) (.-denominator ^Ratio right)))
               (nil? left) true
               :else (Object/.equals left right))))))
