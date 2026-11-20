(ns structured-input-cst
  "Pure, checksum-bound CST edits for a semantic migration planner.
   Operations address the original module, never recursively search literal data.
   No package evaluation, filesystem writes, or semantic classification."
  (:require
    [clojure.string :as str]
    [graphden.input.format :as format]
    [graphden.input.migration :as migration]
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
                   (MessageDigest/.digest sha (String/.getBytes text "UTF-8"))))))


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
  [source module-namespace]
  (let [module (inventory/read-edn "<CST source>" source)]
    (when-not (and (map? module) (or (nil? module-namespace) (string? module-namespace)) (= module-namespace (:namespace module))
                   (vector? (:fns module)) (every? map? (:fns module)))
      (refuse :invalid-module {:namespace module-namespace}))
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
      (if (java.util.regex.Matcher/.find matcher)
        (recur (conj starts (java.util.regex.Matcher/.end matcher)))
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
(declare ^:private projection-leaf ^:private typed-leaf)
(declare ^:private patched-text)


(declare ^:private inline-edit)


(defn- inline-binding-edit
  [source starts loc form spelling configured? path]
  (cond
    (not (vector? form)) nil
    (= :project (first form)) (projection-leaf source starts loc)
    configured? (typed-leaf source starts loc)
    (and (= :ref (first form)) (map? (second form)))
    (inline-edit source starts loc (second form) path)
    (= spelling :bare-value) (leaf-edit source starts loc :wrap-value path)
    (or (= spelling :ref-wrapper) (= :value (first form)))
    (leaf-edit source starts loc :map-expression-to-vector path)
    :else (leaf-edit source starts loc :wrap-ref path)))


(defn- inline-field-edits
  [source starts loc {:keys [bind input configure legacy-forms legacy-order]} path]
  (let [fields (mapv (fn [[field form]]
                       (let [arg-path [:args field]]
                         [field (inline-binding-edit source starts (value-at loc arg-path) form
                                                     (get legacy-forms field) (contains? configure [field])
                                                     (into path arg-path))]))
                     bind)
        configurations (keep (fn [[field edit]]
                               (when-let [spec (:configuration edit)]
                                 (str (migration/stable-edn [field]) " " spec)))
                             fields)
        key (rename-edit source starts loc nil [:args] :bind)]
    (cons (assoc key :after (str (when (seq input) (str ":input " (migration/stable-edn input) " "))
                                 (when (seq configurations) (str ":configure {" (str/join " " configurations) "} "))
                                 (when (seq legacy-forms) (str ":legacy-forms " (migration/stable-edn legacy-forms) " "))
                                 (when (seq legacy-order) (str ":legacy-order " (migration/stable-edn legacy-order) " "))
                                 ":bind"))
          (keep second fields))))


(defn- inline-edit
  [source starts loc body path]
  (let [span (node-edit source starts loc identity)
        original (inventory/read-edn path (:before span))]
    (when-not (and (format/legacy-inline? original)
                   (migration/same-edn? original (format/inline-origin body)))
      (refuse :unsupported-inline-declaration {:path path}))
    (let [edits (when (contains? original :args) (inline-field-edits source starts loc body path))
          local (into [] (map #(-> % (update :start - (:start span)) (update :end - (:start span))))
                      (sort-by :start edits))]
      (assoc span :after (str "[:ref " (patched-text (:before span) local) "]")))))


(defn- leaf-edit
  [source starts leaf op path]
  (case op
    :wrap-value (node-edit source starts leaf #(str "[:value " % "]"))
    :local-projection (projection-leaf source starts leaf)
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


(defn- projection-leaf
  [source starts leaf]
  (when-not (= :map (z/tag leaf)) (refuse :unsupported-projection {}))
  (let [raw (node-edit source starts leaf identity)
        form (z/sexpr leaf)
        spans (field-spans source starts leaf
                           (fn [key v]
                             (node-edit source starts v (if (= :as key) #(str "[" % "]") identity))))
        ;; Every span starts at its actual key token. Keep comments and
        ;; delimiters between that token and the original alias spelling.
        spans (mapv #(if (= :as (:field %))
                       (update % :text (fn [text] (str ":project" (subs text (count ":as")))))
                       %) spans)
        parts (into {} (field-chunks source raw spans))]
    (when-not (and (contains? #{#{:as} #{:as :type}} (set (keys parts))) (simple-keyword? (:as form))
                   (or (not (contains? form :type)) (simple-keyword? (:type form))))
      (refuse :unsupported-projection {}))
    (cond-> (assoc raw :after (str "[" (:as parts) "]"))
      (:type parts) (assoc :configuration (str "{" (:type parts) "}")))))


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
                             (let [operation (get operators field)
                                   op (if (map? operation) (:op operation) operation)]
                               (case op
                                 nil (node-edit source starts value identity)
                                 :typed-expression (typed-leaf source starts value)
                                 :inline-ref (inline-edit source starts value (:body operation) [:args field])
                                 (leaf-edit source starts value op [:args field])))))
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
                 (when (seq declarations) (str "{" (str/join ordered) "}"))
                 (when (and (seq declarations) (seq binds)) " :bind ")
                 (when (seq binds) (str "{" (str/join (map second binds)) "}"))
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

      :declare-projection-input
      (let [input (:input operation)
            [exposed spec] (first input)
            field (:field operation)
            target (value-at loc [:args field])
            pin (:configuration (projection-leaf source starts target))]
        (when-not (and (= 1 (count input)) (simple-keyword? exposed)
                       (map? spec) (= #{:type :required} (set (keys spec))) (simple-keyword? (:type spec))
                       (false? (:required spec)) (= (cond-> {:as exposed} pin (assoc :type (:type spec))) (z/sexpr target))
                       (or (key-loc loc :parent) (key-loc loc :parents)) (not (key-loc loc :input)))
          (refuse :invalid-projection-declaration {:definition definition}))
        (assoc (rename-edit source starts loc definition path :bind) :after (str ":input {" exposed " {:type " (:type spec) " :required false}}"
                                                                                 (when pin (str " :configure {[" field "] " pin "}")) " :bind")))

      :rename-bind
      (do
        (when-not (some #(some-> (key-loc loc %) z/right z/sexpr) [:parent :parents])
          (refuse :not-composed-definition {:definition definition}))
        (rename-edit source starts loc definition path :bind))

      (:wrap-value :wrap-ref :map-expression-to-vector :local-projection)
      (leaf-edit source starts (argument-loc loc path) op path)

      :inline-ref
      (inline-edit source starts (argument-loc loc path) (:body operation) path)

      :partition-composed-args
      (partition-composed-edit source starts loc operation)

      :move-args-entry
      (refuse :unsupported-comment-safe-move
              {:recommendation "Define comment ownership and exact source/destination spans before moving entries."})

      (refuse :unknown-operation {:op op}))))


(defn- projection-operation
  [source {:keys [source-path origin expression rename policy]}]
  (let [{exposed :alias :keys [alias-id field-id pin-id declaration]} rename]
    (when-not (and (simple-keyword? exposed) (= (cond-> {:as exposed} pin-id (assoc :type (:type declaration))) (get-in source source-path))
                   (= [:project [exposed]] expression) (= (cond-> {} pin-id (assoc :type-override-fn-id pin-id)) policy)
                   (uuid? alias-id) (= field-id (:slot-id origin)) (uuid? field-id))
      (refuse :planner-ledger-mismatch {:path source-path}))
    {:op :local-projection :definition (:name source) :path source-path}))


(defn- argument-operation
  [source {:keys [source-path kind expression policy]}]
  (let [[operator payload] expression
        raw (get-in source source-path)
        inline? (and (= :ref operator) (format/legacy-inline? raw)
                     (migration/same-edn? raw (format/inline-origin payload)))
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
    (cond-> {:op (cond inline? :inline-ref
                       typed? :typed-expression
                       wrapper? :map-expression-to-vector
                       (= :value operator) :wrap-value
                       :else :wrap-ref)
             :definition (:name source) :path source-path}
      inline? (assoc :body payload))))


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
            alias-entries (filterv :rename binds)
            alias-input (into {} (map (comp (juxt :alias :declaration) :rename)) alias-entries)
            declarations (merge declarations alias-input)
            bindings (into {} (map (juxt (comp second :source-path) :expression)) binds)
            operations (mapv #((if (:rename %) projection-operation argument-operation) source %) binds)
            configuration (into {} (keep (fn [{:keys [op path]}]
                                           (when (or (= :typed-expression op) (and (= :local-projection op) (contains? (get-in source path) :type)))
                                             [[(second path)] (select-keys (get-in source path) [:type])])))
                                operations)]
        (when-not (and (or (not has-args?) (map? (:args source)))
                       (vector? arguments) (= (count paths) (count (set paths)))
                       (= (count arguments) (+ (count fields) (count binds)))
                       (= (set paths) (set (for [field (keys (:args source))] [:args field])))
                       (every? #(= (:spec %) (get-in source (:source-path %))) fields)
                       (= proposed (cond-> (dissoc source :args)
                                     (seq declarations) (assoc :input declarations)
                                     (or (seq binds) (and has-args? (empty? fields))) (assoc :bind bindings)
                                     (seq configuration) (assoc :configure configuration))))
          (refuse :planner-ledger-mismatch {}))
        (cond
          (seq alias-entries)
          (do
            (when-not (and (= 1 (count alias-entries)) (empty? fields) (not-any? #(= :typed-expression (:op %)) operations))
              (refuse :unsupported-projection-mix {}))
            (into [{:op :declare-projection-input :definition (:name source) :path [:args]
                    :input alias-input :field (second (:source-path (first alias-entries)))}] operations))

          (or (seq fields) (seq configuration))
          [{:op :partition-composed-args :definition (:name source) :path [:args]
            :declarations (vec (keys declarations))
            :operators (into {} (map (juxt (comp second :path) identity)) operations)}]
          :else (into (rename :rename-bind) operations)))

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
      (str/join (conj parts (subs source cursor))))))


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
  [source {module-namespace :namespace :keys [operations]}]
  (when-not (and (string? source) (vector? operations))
    (refuse :invalid-request {}))
  (let [definitions (module-definitions source module-namespace)
        starts (line-starts source)
        edits (vec (sort-by :start (map #(operation-edit source starts definitions %) operations)))
        target (patched-text source edits)]
    ;; A splice must remain one valid EDN module before it can leave the planner.
    (module-definitions target module-namespace)
    {:namespace module-namespace
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
