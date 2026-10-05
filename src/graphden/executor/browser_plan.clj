(ns graphden.executor.browser-plan
  "A bounded browser execution plan derived from ordinary stored fn rows.
   Authorization and the branch-consistent visibility policy belong to the
   caller. This module performs no execution, storage reads or registry writes."
  (:require
    [graphden.executor.compile.bindings :as b]
    [graphden.executor.compile.lookups :as l]
    [graphden.executor.compile.renames :as r]
    [graphden.executor.compile.surface :as surface]
    [graphden.packages.records.ids :as ids]))


(def ^:private max-safe-integer 9007199254740991)
(def ^:private max-value-depth 64)
(def ^:private max-value-nodes 50000)
(def ^:private max-string-length 1048576)
(def ^:private max-functions 1024)
(def ^:private max-call-depth 128)


(defn- reject!
  [reason context]
  (throw (ex-info (str "Browser plan rejected: " (name reason))
                  (assoc (select-keys context [:fn-id :slot-id :item-id :path])
                         :type :browser-plan/unsupported :reason reason))))


(defn- derive!
  "Do not expose compiler/policy exception messages, values or causes."
  [context f]
  (try (f)
       (catch Exception e
         (if (= :browser-plan/unsupported (:type (ex-data e)))
           (throw e)
           (reject! :normalization-failed context)))))


(defn- safe-integer?
  [v]
  (and (integer? v) (<= (- max-safe-integer) v max-safe-integer)))


(defn- value-budget!
  [depth remaining]
  (when (or (> depth max-value-depth) (neg? (vswap! remaining dec)))
    (reject! :value-limit {})))


(defn- checked-string
  [s]
  (when-not (and (string? s) (<= (count s) max-string-length))
    (reject! :invalid-string {}))
  s)


(declare encode* decode*)


(defn- encode-map
  [v depth remaining sequences?]
  ["map" (mapv (fn [[k value]]
                 (when-not (or (keyword? k) (string? k))
                   (reject! :unsupported-map-key {}))
                 [(encode* k depth remaining sequences?) (encode* value depth remaining sequences?)])
               v)])


(defn- encode*
  [v depth remaining sequences?]
  (value-budget! depth remaining)
  (cond
    (nil? v) ["nil"]
    (boolean? v) ["bool" v]
    (safe-integer? v) ["int" (long v)]
    (string? v) ["string" (checked-string v)]
    (keyword? v) (do
                   (when (or (empty? (name v)) (= "" (namespace v)))
                     (reject! :invalid-keyword {}))
                   ["keyword" (some-> (namespace v) checked-string)
                    (checked-string (name v))])
    (or (vector? v) (and sequences? (sequential? v)))
    ["vector" (mapv #(encode* % (inc depth) remaining sequences?) v)]
    (map? v) (encode-map v (inc depth) remaining sequences?)
    :else (reject! :unsupported-value {})))


(defn encode-value
  "Encode bounded data without conflating keywords, strings or carrier-shaped
   user vectors/maps. Only string/keyword map keys and safe integers are allowed."
  [v]
  (encode* v 0 (volatile! max-value-nodes) false))


(defn encode-result
  "Encode an evaluated result, materializing finite sequences under the same
   node/depth limits. Unlike literal export, this accepts a lazy :list result."
  [v]
  (encode* v 0 (volatile! max-value-nodes) true))


(defn- decode-map
  [pairs depth remaining]
  (when-not (vector? pairs) (reject! :invalid-map {}))
  (reduce (fn [acc pair]
            (when-not (and (vector? pair) (= 2 (count pair)))
              (reject! :invalid-map-entry {}))
            (let [[k v] (mapv #(decode* % depth remaining) pair)]
              (when-not (or (keyword? k) (string? k))
                (reject! :unsupported-map-key {}))
              (when (contains? acc k) (reject! :duplicate-map-key {}))
              (assoc acc k v)))
          {}
          pairs))


(defn- decode*
  [wire depth remaining]
  (value-budget! depth remaining)
  (when-not (and (vector? wire) (string? (first wire)))
    (reject! :invalid-wire-value {}))
  (let [[tag v n] wire
        arity (case tag "nil" 1 "keyword" 3 2)]
    (when-not (= arity (count wire)) (reject! :invalid-wire-arity {}))
    (case tag
      "nil" nil
      "bool" (if (boolean? v) v (reject! :invalid-boolean {}))
      "int" (if (safe-integer? v) (long v) (reject! :invalid-integer {}))
      "string" (checked-string v)
      "keyword" (do
                  (when-not (and (or (nil? v) (string? v)) (string? n)
                                 (seq n) (or (nil? v) (seq v)))
                    (reject! :invalid-keyword {}))
                  (when v (checked-string v))
                  (keyword v (checked-string n)))
      "vector" (if (vector? v)
                 (mapv #(decode* % (inc depth) remaining) v)
                 (reject! :invalid-vector {}))
      "map" (decode-map v (inc depth) remaining)
      (reject! :unknown-value-tag {}))))


(defn decode-value
  "Decode the tagged browser wire value, rejecting ambiguous or unbounded data."
  [wire]
  (decode* wire 0 (volatile! max-value-nodes)))


(def ^:private primitive-ops
  (into {}
        (for [[ns-path names] [["core.logic" [:const :if :equal?]]
                               ["core.collections" [:list :get :assoc :zipmap :count]]
                               ["core.arithmetic" [:add :mod]]
                               ["core.hof" [:map]]
                               ["web.html" [:hiccup]]]
              n names]
          [(ids/fn-id ns-path n) (name n)])))


(defn supported-primitive-ids
  "Canonical package identities implemented by this bounded backend."
  []
  (set (keys primitive-ops)))


(defn- wire-name
  [k]
  (if (keyword? k) (subs (str k) 1) k))


(defn- literal-expr
  [v context]
  (try
    {:kind "literal" :value (encode* v 0 (:value-budget context) false)}
    (catch clojure.lang.ExceptionInfo e
      (reject! (:reason (ex-data e)) context))))


(defn- read-expr
  [sid n]
  {:kind "read" :slot (some-> sid str) :name (wire-name n)})


(defn- call-expr
  [target fid lookups]
  {:kind "call" :fn (str target)
   :renames (mapv (fn [[callee caller]]
                    {:callee (wire-name callee) :caller (wire-name caller)})
                  (sort-by (comp str key) (r/build-ref-renames target fid lookups)))})


(defn- item-expr
  [item owner lookups context]
  (let [context (assoc context :item-id (:id item))
        v (:value item)]
    (assoc
      (cond
        (and (map? v) (:as v) (not (:literal item)))
        (let [k (keyword (:as v))
              sid (get-in lookups [:slot-by-fn-name [owner k] :id])]
          (read-expr sid k))

        (some? v) (literal-expr v context)
        ;; JVM sequence-item calls deliberately do not apply ref renames.
        (:ref-fn-id item) {:kind "call" :fn (str (:ref-fn-id item)) :renames []}
        :else (literal-expr nil context))
      :item (str (:id item)))))


(defn- closure-expr
  "Only map's statically bound callback is supported. Reuse the JVM's
   parameter/capture decisions; callable producers and env HOFs stay closed."
  [{:keys [kind slot-id ref-id] :as binding} fid lookups context]
  (when-not (and (= :ref kind)
                 (= slot-id (ids/slot-id (ids/fn-id "core.hof" :map) :func))
                 (not (:env-binding? context)))
    (reject! :callable-binding context))
  (let [params (r/hof-lambda-params ref-id slot-id binding fid lookups)
        translation (r/build-hof-translation ref-id params lookups)]
    (when (> (count params) 1) (reject! :callable-arity context))
    {:kind "closure" :fn (str ref-id)
     :lambdaParams (mapv wire-name params)
     :translation (mapv (fn [[sid n]] {:slot (str sid) :name (wire-name n)})
                        (sort-by (comp str key) translation))}))


(defn- binding-expr
  [binding fid lookups context]
  (let [{:keys [kind slot-id ext-name env-name ref-id items binder-fn-id]} binding
        context (assoc context :slot-id slot-id)]
    (when (:produces-callable? binding)
      (reject! :callable-binding context))
    (if (:is-fn binding)
      (closure-expr binding fid lookups context)
      (case kind
        :value (literal-expr (:value binding) context)
        :free (read-expr (l/effective-reader-slot-id fid slot-id lookups)
                         (or ext-name env-name))
        :ref (call-expr ref-id fid lookups)
        :seq (do
               (when (:lazy-seq? binding) (reject! :lazy-sequence-slot context))
               {:kind "seq"
                :items (mapv #(item-expr % (or binder-fn-id fid) lookups context) items)})
        (reject! :binding-kind context)))))


(defn- binding-deps
  [binding]
  (case (:kind binding)
    :ref [(:ref-id binding)]
    :seq (keep :ref-fn-id (:items binding))
    []))


(defn- check-fn!
  [fid lookups allow-fn? context]
  (doseq [ancestor (l/inheritance-chain* fid lookups)]
    (let [row (get-in lookups [:fn-map ancestor])]
      (when-not row (reject! :missing-function (assoc context :fn-id ancestor)))
      (when (:concealed? row) (reject! :concealed-function (assoc context :fn-id ancestor)))
      (when-not (true? (allow-fn? ancestor))
        (reject! :visibility-denied (assoc context :fn-id ancestor))))))


(defn- function-plan
  [fid lookups context]
  (let [root (l/root-fn fid (:fn-map lookups) lookups)
        primitive (:id root)
        bindings (b/collect-bindings fid lookups)
        env (b/collect-env-bindings fid lookups)]
    (when-not (contains? primitive-ops primitive)
      (reject! :unsupported-primitive context))
    {:plan {:id (str fid) :primitive (str primitive)
            :aliases (mapv (fn [{:keys [rename-name chain-name]}]
                             {:fromName (wire-name rename-name) :toName (wire-name chain-name)})
                           (r/compute-rename-aliases fid lookups))
            :env (mapv (fn [entry]
                         {:name (wire-name (:env-name entry))
                          :slot (str (:slot-id entry))
                          :expr (binding-expr entry fid lookups (assoc context :env-binding? true))})
                       env)
            :args (mapv (fn [entry]
                          {:slot (str (:slot-id entry))
                           :name (wire-name (:base-name entry))
                           :expr (binding-expr entry fid lookups context)})
                        bindings)}
     :deps (vec (distinct (mapcat binding-deps (concat bindings env))))
     :primitive primitive}))


(defn- input-plan
  [fid lookups]
  (let [entries (surface/surface-entries fid lookups)
        destinations (reduce (fn [acc {:keys [ext-name source-name slot-id]}]
                               (reduce #(update %1 %2 (fnil conj #{}) (str slot-id))
                                       acc (distinct (remove nil? [ext-name source-name]))))
                             {} entries)]
    {:accepted (mapv wire-name (sort (:accepted (surface/surface-names fid lookups))))
     :destinations (mapv (fn [[n slots]] {:name (wire-name n) :slots (vec (sort slots))})
                         (sort-by (comp str key) destinations))
     :required (into [] (comp (remove :optional?) (map (comp wire-name :ext-name)) (distinct))
                     (surface/public-free-entries fid lookups))}))


(defn- collect-plans
  "Postorder walk; local state shares completed plans between entry roots."
  [roots lookups allow-fn?]
  (let [done (volatile! {})
        started (volatile! #{})
        order (volatile! [])
        primitives (volatile! #{})
        value-budget (volatile! max-value-nodes)]
    (letfn [(visit
              [fid path]
              (when (some #{fid} path)
                (reject! :cycle {:fn-id fid :path (conj path fid)}))
              (when-not (contains? @done fid)
                (let [path (conj path fid)
                      context {:fn-id fid :path path :value-budget value-budget}]
                  (when (> (count path) max-call-depth) (reject! :call-depth context))
                  (when (>= (count @started) max-functions) (reject! :function-limit context))
                  (vswap! started conj fid)
                  (let [{:keys [plan deps primitive]}
                        (derive! context #(do (check-fn! fid lookups allow-fn? context)
                                              (function-plan fid lookups context)))]
                    (doseq [dep deps] (visit dep path))
                    (vswap! done assoc fid plan)
                    (vswap! order conj plan)
                    (vswap! primitives conj primitive)))))]
      (doseq [fid roots] (visit fid []))
      {:functions @order
       :primitives (mapv (fn [id] {:id (str id) :op (get primitive-ops id)})
                         (sort @primitives))})))


(defn export-plan
  "Export entry roots from one stored graph snapshot. opts MUST supply
   :allow-fn? (id -> boolean), normally true only for the branch's :plain
   trace-capture-class. Missing/unknown visibility fails closed. The caller
   must authenticate, reject tenancy, and capture graph + types consistently.
   Bind registry/*rich-types-override* to an isolated snapshot registry: the
   shared binding classifier reads this registry for callable/lazy metadata.
   No literal values or graph snapshots are included in exception data."
  ([graph base-fns entry-ids]
   (export-plan graph base-fns entry-ids {}))
  ([graph base-fns entry-ids {:keys [allow-fn?]}]
   (when-not (fn? allow-fn?) (reject! :missing-visibility-policy {}))
   (when-not (and (map? entry-ids) (seq entry-ids)
                  (every? uuid? (vals entry-ids)))
     (reject! :invalid-entries {}))
   (let [lookups (assoc (derive! {} #(l/build-lookups graph)) :base-fns base-fns)
         entries (into (sorted-map) (map (fn [[k v]] [(wire-name k) (str v)])) entry-ids)
         roots (mapv val (sort-by (comp str key) entry-ids))
         plans (collect-plans roots lookups allow-fn?)]
     (merge {:format 1 :primitiveAbi 1 :entries entries
             :inputs (into {} (map (fn [fid]
                                     [(str fid) (derive! {:fn-id fid :path [fid]}
                                                         #(input-plan fid lookups))])) roots)}
            plans))))
