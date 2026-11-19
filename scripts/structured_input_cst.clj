(ns structured-input-cst
  "Pure, checksum-bound CST edits for a semantic migration planner.
   Operations address the original module, never recursively search literal data.
   No package evaluation, filesystem writes, or semantic classification."
  (:require
    [clojure.string :as str]
    [graphden.input.format :as format]
    [rewrite-clj.zip :as z]
    [structured-input-inventory :as inventory])
  (:import
    (java.security
      MessageDigest)))


(defn- refuse
  [reason data]
  (throw (ex-info (str "CST migration refused: " (name reason)) (assoc data :reason reason))))


(defn- sha256
  [text]
  (let [sha (MessageDigest/getInstance "SHA-256")]
    (str/join (map #(format "%02x" (bit-and % 255))
                   (.digest sha (.getBytes ^String text "UTF-8"))))))


(defn- children
  [loc]
  (let [nodes (vec (take-while some? (iterate z/right (z/down loc))))]
    (when (some #(= :uneval (z/tag %)) nodes)
      (refuse :unsupported-reader-discard {}))
    nodes))


(defn- key-loc
  [loc key]
  (when-not (= :map (z/tag loc))
    (refuse :non-map-path {:key key}))
  (some (fn [[key-node _]] (when (= key (z/sexpr key-node)) key-node))
        (partition 2 (children loc))))


(defn- value-at
  [loc path]
  (reduce (fn [parent key]
            (if-let [found (key-loc parent key)]
              (z/right found)
              (refuse :missing-path {:path path :key key})))
          loc path))


(defn- module-definitions
  [source namespace]
  (let [module (inventory/read-edn "<CST source>" source)]
    (when-not (and (map? module) (or (nil? namespace) (string? namespace)) (= namespace (:namespace module))
                   (vector? (:fns module)) (every? map? (:fns module)))
      (refuse :invalid-module {:namespace namespace}))
    (let [names (map :name (:fns module))]
      (when-not (every? keyword? names)
        (refuse :unnamed-definition {}))
      (when-not (= (count names) (count (set names)))
        (refuse :duplicate-definition {})))
    (let [root (z/of-string source {:track-position? true})]
      (into {} (map (fn [loc] [(z/sexpr (value-at loc [:name])) loc]))
            (children (value-at root [:fns]))))))


(defn- line-starts
  [source]
  (let [matcher (re-matcher #"\r\n|\r|\n" source)]
    (loop [starts [0]]
      (if (.find matcher)
        (recur (conj starts (.end matcher)))
        starts))))


(defn- node-edit
  [source starts loc replacement]
  (let [[[start-row start-col] [end-row end-col]] (z/position-span loc)
        start (+ (nth starts (dec start-row)) (dec start-col))
        end (+ (nth starts (dec end-row)) (dec end-col))
        before (subs source start end)]
    ;; rewrite-clj normalizes CRLF even inside strings. Only use it to locate
    ;; nodes; splice the original text, and verify its coordinates before use.
    (when-not (= (str/replace before #"\r\n|\r" "\n") (z/string loc))
      (refuse :source-position-mismatch {:start start :end end}))
    {:start start :end end :before before :after (replacement before)}))


(defn- argument-loc
  [loc path]
  (when-not (and (= :args (first path)) (<= 2 (count path)))
    (refuse :invalid-argument-path {:path path}))
  (value-at loc path))


(defn- rename-edit
  [source starts loc definition path target]
  (when-not (= [:args] path)
    (refuse :invalid-rename-path {:path path}))
  (when (key-loc loc target)
    (refuse :existing-target {:definition definition :target target}))
  (let [key-node (or (key-loc loc :args) (refuse :missing-path {:path path}))]
    (node-edit source starts key-node (constantly (str target)))))


(declare ^:private leaf-edit)
(declare ^:private patched-text)


(defn- inline-edit
  [source starts loc op path]
  (let [body (z/sexpr loc)
        span (node-edit source starts loc identity)]
    (when-not (and (format/legacy-inline? body) (= (= op :wrap-inline-ref) (format/inline-leaf? body)))
      (refuse :unsupported-inline-declaration {:path path}))
    (let [edits (when-let [[field value] (first (:args body))]
                  (let [leaf (value-at loc [:args field])
                        edit (case op
                               :inline-bare-value
                               (if (coll? value)
                                 (refuse :unsupported-inline-declaration {:path path})
                                 (node-edit source starts leaf identity))
                               :inline-bare-ref (leaf-edit source starts leaf :wrap-ref path)
                               :inline-wrapped-value
                               (if (and (map? value) (= #{:value} (set (keys value))))
                                 (leaf-edit source starts leaf :map-expression-to-vector path)
                                 (refuse :unsupported-inline-declaration {:path path})))]
                    [(rename-edit source starts loc nil [:args] :bind) edit]))
          local (mapv #(-> % (update :start - (:start span)) (update :end - (:start span))) edits)]
      (assoc span :after (str "[:ref " (patched-text (:before span) local) "]")))))


(defn- leaf-edit
  [source starts leaf op path]
  (case op
    :wrap-value (node-edit source starts leaf #(str "[:value " % "]"))
    (:wrap-inline-ref :inline-bare-value :inline-bare-ref :inline-wrapped-value)
    (inline-edit source starts leaf op path)
    :wrap-ref
    (do
      (when-not (keyword? (z/sexpr leaf))
        (refuse :non-keyword-reference {:path path}))
      (node-edit source starts leaf #(str "[:ref " % "]")))
    :map-expression-to-vector
    (let [nodes (when (= :map (z/tag leaf)) (children leaf))]
      (when-not (and (= 2 (count nodes)) (#{:value :ref} (z/sexpr (first nodes)))
                     (or (= :value (z/sexpr (first nodes)))
                         (keyword? (z/sexpr (second nodes)))))
        (refuse :unsupported-expression-map {:path path}))
      (node-edit source starts leaf #(str "[" (subs % 1 (dec (count %))) "]")))
    (refuse :unknown-operation {:op op})))


(defn- field-spans
  [source starts args edit-value]
  (mapv (fn [[k v]]
          (let [field (z/sexpr k)
                key-span (node-edit source starts k identity)
                value-span (edit-value field v)]
            {:field field :start (:start key-span) :end (:end value-span)
             :configuration (when-let [spec (:configuration value-span)]
                              (str "[" (:before key-span) "] " spec))
             :text (str (subs source (:start key-span) (:start value-span)) (:after value-span))}))
        (partition 2 (children args))))


(defn- field-chunks
  "A same-line trailing comment follows its value. Subsequent comment lines
   travel with the next field. Include every original byte of map trivia."
  [source map-span spans]
  (let [cuts (mapv (fn [[left right]]
                     (let [gap (subs source (:end left) (:start right))
                           tail (re-find #"^[\t ,]*;[^\r\n]*(?:\r\n|\r|\n)" gap)]
                       (+ (:end left) (count tail))))
                   (partition 2 1 spans))]
    (mapv (fn [span from to]
            [(:field span) (str (subs source from (:start span)) (:text span) (subs source (:end span) to))])
          spans (cons (inc (:start map-span)) cuts) (conj cuts (dec (:end map-span))))))


(defn- typed-leaf
  [source starts leaf]
  (when-not (= :map (z/tag leaf)) (refuse :unsupported-expression-map {}))
  (let [raw (node-edit source starts leaf identity)
        spans (field-spans source starts leaf (fn [_ value] (node-edit source starts value identity)))
        parts (into {} (field-chunks source raw spans))
        operator (if (contains? parts :ref) :ref :value)]
    (when-not (and (= #{:type operator} (set (keys parts)))
                   (let [type (z/sexpr (value-at leaf [:type]))]
                     (or (keyword? type) (vector? type)))
                   (or (= :value operator) (keyword? (z/sexpr (value-at leaf [:ref])))))
      (refuse :unsupported-expression-map {}))
    (assoc raw :after (str "[" (get parts operator) "]")
           :configuration (str "{" (:type parts) "}"))))


(defn- partition-composed-edit
  [source starts loc {:keys [definition path declarations operators]}]
  (when-not (and (= [:args] path) (or (key-loc loc :parent) (key-loc loc :parents))
                 (not-any? #(key-loc loc %) [:input :bind :configure])
                 (vector? declarations) (= (count declarations) (count (set declarations)))
                 (map? operators) (not-any? (set declarations) (keys operators)))
    (refuse :invalid-field-partition {:definition definition}))
  (let [args (value-at loc path)
        key-edit (rename-edit source starts loc definition path (if (seq declarations) :input :bind))
        map-span (node-edit source starts args identity)
        spans (field-spans source starts args
                           (fn [field value]
                             (case (get operators field)
                               nil (node-edit source starts value identity)
                               :typed-expression (typed-leaf source starts value)
                               (leaf-edit source starts value (get operators field) [:args field]))))
        chunks (field-chunks source map-span spans)
        by-field (into {} chunks)
        owns? (set declarations)
        own-chunks (filter (comp owns? first) chunks)
        ;; A large legacy args map may iterate in hash order. Preserve the
        ;; original slot positions when the smaller :input becomes an array map.
        ordered (if (= declarations (vec (keys (into {} own-chunks))))
                  (map second own-chunks) (map by-field declarations))
        binds (remove (comp owns? first) chunks)
        configs (keep :configuration spans)]
    (when-not (and (= :map (z/tag args)) (= (set (keys by-field)) (into owns? (keys operators))))
      (refuse :invalid-field-partition {:definition definition}))
    {:start (:start key-edit) :end (:end map-span)
     :before (subs source (:start key-edit) (:end map-span))
     :after (str (:after key-edit) (subs source (:end key-edit) (:start map-span))
                 (when (seq declarations) (str "{" (apply str ordered) "}"))
                 (when (and (seq declarations) (seq binds)) " :bind ")
                 (when (seq binds) (str "{" (apply str (map second binds)) "}"))
                 (when (seq configs) (str " :configure {" (str/join " " configs) "}")))}))


(defn- operation-edit
  [source starts definitions {:keys [op definition path] :as operation}]
  (when-not (and (keyword? definition) (vector? path) (seq path))
    (refuse :invalid-operation {:operation operation}))
  (let [loc (or (get definitions definition)
                (refuse :missing-definition {:definition definition}))]
    (case op
      :rename-input
      (do
        (when (some #(key-loc loc %)
                    [:parent :parents :type :refine :list :union :map :tuple :variant :fn-type :marker])
          (refuse :not-base-declaration {:definition definition}))
        (when (key-loc loc :input)
          (refuse :existing-input {:definition definition}))
        (rename-edit source starts loc definition path :input))

      :rename-bind
      (do
        (when-not (some #(some-> (key-loc loc %) z/right z/sexpr) [:parent :parents])
          (refuse :not-composed-definition {:definition definition}))
        (rename-edit source starts loc definition path :bind))

      (:wrap-value :wrap-ref :wrap-inline-ref :inline-bare-value :inline-bare-ref :inline-wrapped-value
                   :map-expression-to-vector)
      (leaf-edit source starts (argument-loc loc path) op path)

      :partition-composed-args
      (partition-composed-edit source starts loc operation)

      :move-args-entry
      (refuse :unsupported-comment-safe-move
              {:recommendation "Define comment ownership and exact source/destination spans before moving entries."})

      (refuse :unknown-operation {:op op}))))


(defn- argument-operation
  [source {:keys [source-path kind expression policy]}]
  (let [[operator payload] expression
        raw (get-in source source-path)
        inline? (and (= :ref operator) (format/legacy-inline? raw) (= raw (format/inline-origin payload)))
        wrapper? (and (map? raw) (some #(contains? raw %) [:value :ref]))
        typed? (and wrapper? (= #{operator :type} (set (keys raw)))
                    (or (keyword? (:type raw)) (vector? (:type raw)))
                    (= #{:type-override-fn-id} (set (keys policy))) (uuid? (:type-override-fn-id policy)))]
    (when-not (and (= :binding kind) (or typed? (empty? policy))
                   (vector? source-path) (= 2 (count source-path)) (= :args (first source-path))
                   (contains? (:args source) (second source-path))
                   (vector? expression) (= 2 (count expression)) (#{:value :ref} operator)
                   (or (= :value operator) (qualified-keyword? payload) inline?))
      (refuse :unsupported-source-argument {:path source-path}))
    (when-not (if wrapper?
                (and (or typed? (= 1 (count raw))) (contains? raw operator)
                     (if (= :value operator) (= payload (get raw operator))
                         (keyword? (get raw operator))))
                (if (= :value operator) (= payload raw) (or (keyword? raw) inline?)))
      (refuse :planner-ledger-mismatch {:path source-path}))
    {:op (cond inline? (if-let [[_ form] (first (:bind payload))]
                         (if (vector? form)
                           (if (= :ref (first form)) :inline-bare-ref :inline-wrapped-value)
                           :inline-bare-value)
                         :wrap-inline-ref)
               typed? :typed-expression
               wrapper? :map-expression-to-vector
               (= :value operator) :wrap-value
               :else :wrap-ref)
     :definition (:name source) :path source-path}))


(defn entry-operations
  "Bridge one SUPPORTED graphden.input.source/plan-module entry to CST operations.
   The semantic planner owns reference resolution. Ref spellings are retained;
   value payloads and the proposed form must agree exactly with its ledger.
   The caller binds this entry to the original file snapshot before calling plan."
  [{:keys [source proposed status kind legacy blockers]}]
  (when-not (and (= :supported status) (empty? blockers) (map? source) (map? proposed)
                 (keyword? (:name source))
                 (not-any? #(contains? source %) [:input :bind :configure]))
    (refuse :unsupported-source-entry {}))
  (let [has-args? (contains? source :args)
        rename (fn [op] (if has-args? [{:op op :definition (:name source) :path [:args]}] []))]
    (case kind
      :base
      (do
        (when-not (= proposed (cond-> (dissoc source :args) has-args? (assoc :input (:args source))))
          (refuse :planner-ledger-mismatch {}))
        (rename :rename-input))

      :type
      (do (when-not (= source proposed) (refuse :planner-ledger-mismatch {})) [])

      :composed
      (let [arguments (:arguments legacy)
            paths (mapv :source-path arguments)
            {fields :declaration binds :binding} (group-by :kind arguments)
            declarations (into (array-map) (map (juxt (comp second :source-path) :spec)) fields)
            bindings (into {} (map (juxt (comp second :source-path) :expression)) binds)
            operations (mapv #(argument-operation source %) binds)
            configuration (into {} (keep (fn [{:keys [op path]}]
                                           (when (= :typed-expression op)
                                             [[(second path)] (select-keys (get-in source path) [:type])])))
                                operations)]
        (when-not (and (or (not has-args?) (map? (:args source)))
                       (vector? arguments) (= (count paths) (count (set paths)))
                       (= (count arguments) (+ (count fields) (count binds)))
                       (= (set paths) (set (map #(vector :args %) (keys (:args source)))))
                       (every? #(= (:spec %) (get-in source (:source-path %))) fields)
                       (= proposed (cond-> (dissoc source :args)
                                     (seq fields) (assoc :input declarations)
                                     (or (seq binds) (and has-args? (empty? fields))) (assoc :bind bindings)
                                     (seq configuration) (assoc :configure configuration))))
          (refuse :planner-ledger-mismatch {}))
        (if (or (seq fields) (seq configuration))
          [{:op :partition-composed-args :definition (:name source) :path [:args]
            :declarations (vec (keys declarations))
            :operators (into {} (map (juxt (comp second :path) :op)) operations)}]
          (into (rename :rename-bind) operations)))

      (refuse :unsupported-source-entry {}))))


(defn- valid-edit?
  [{:keys [start end before after]}]
  (and (integer? start) (integer? end) (<= 0 start end)
       (string? before) (string? after) (= (- end start) (count before))))


(defn- patched-text
  [source edits]
  (loop [remaining edits cursor 0 parts []]
    (if-let [{:keys [start end before after] :as edit} (first remaining)]
      (do
        (when-not (and (valid-edit? edit) (<= end (count source))
                       (= before (subs source start end)))
          (refuse :invalid-plan {}))
        (when (< start cursor)
          (refuse :overlapping-operations {:start start :previous-end cursor}))
        (recur (next remaining) end (conj parts (subs source cursor start) after)))
      (apply str (conj parts (subs source cursor))))))


(defn- reverse-edits
  [edits]
  (second
    (reduce (fn [[offset reversed] {:keys [start before after]}]
              (let [target-start (+ start offset)]
                [(+ offset (- (count after) (count before)))
                 (conj reversed {:start target-start :end (+ target-start (count after))
                                 :before after :after before})]))
            [0 []] edits)))


(defn plan
  "Plan edits against one exact source string. Request: {:namespace STRING-OR-NIL
   :operations [{:op KEYWORD :definition KEYWORD :path VECTOR}]}.
   Operations: :rename-input, :rename-bind, :wrap-value, :wrap-ref,
   :map-expression-to-vector (sole-key legacy :value/:ref maps only).
   Paths contain map keys and address the ORIGINAL definition. Rename requires
   [:args]; wrap requires an explicit path below :args. Overlaps refuse.
   A planner-owned field partition separates composed declarations and bindings.
   Unclassified entry moves refuse."
  [source {:keys [namespace operations]}]
  (when-not (and (string? source) (vector? operations))
    (refuse :invalid-request {}))
  (let [definitions (module-definitions source namespace)
        starts (line-starts source)
        edits (vec (sort-by :start (map #(operation-edit source starts definitions %) operations)))
        target (patched-text source edits)]
    ;; A splice must remain one valid EDN module before it can leave the planner.
    (module-definitions target namespace)
    {:namespace namespace
     :original-sha256 (sha256 source)
     :target-sha256 (sha256 target)
     :target-text target
     :edits edits}))


(defn apply-text
  "Return {:status :applied|:already-applied :text STRING}, or refuse.
   Full-source SHA-256 binds the plan; only exact target bytes are idempotent.
   This function has no filesystem effects."
  [{:keys [original-sha256 target-sha256 target-text edits]} source]
  (when-not (and (string? source) (string? target-text)
                 (string? original-sha256) (= target-sha256 (sha256 target-text))
                 (vector? edits) (every? valid-edit? edits))
    (refuse :invalid-plan {}))
  (cond
    (= source target-text)
    (do
      (when-not (= original-sha256 (sha256 (patched-text source (reverse-edits edits))))
        (refuse :invalid-plan {}))
      {:status :already-applied :text source})

    (= original-sha256 (sha256 source))
    (do
      (when-not (= target-text (patched-text source edits))
        (refuse :invalid-plan {}))
      {:status :applied :text target-text})

    :else (refuse :checksum-mismatch {})))
