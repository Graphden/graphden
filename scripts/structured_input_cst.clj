(ns structured-input-cst
  "Pure, checksum-bound CST edits for a semantic migration planner.
   Operations address the original module, never recursively search literal data.
   No package evaluation, filesystem writes, or semantic classification."
  (:require
    [clojure.set :as set]
    [clojure.string :as str]
    [graphden.input.format :as format]
    [graphden.input.list-builder :as list-builder]
    [graphden.input.list-positions :as positions]
    [graphden.input.migration :as migration]
    [graphden.input.source-annotations :as annotations]
    [graphden.input.source-list :as source-list]
    [graphden.input.source-rich-types :as rich]
    [graphden.packages.records.ids :as ids]
    [rewrite-clj.node :as node]
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


(declare ^:private inline-edit ^:private list-builder-edit ^:private annotation-map-edit)
(declare ^:private annotation-spec-edit ^:private field-chunks)


(defn- inline-binding-edit
  [source starts loc form spelling configured? path]
  (cond
    (not (vector? form)) nil
    (= :list-builder (first form))
    (let [{:keys [before]} (node-edit source starts loc identity)]
      (list-builder-edit source starts loc {:original (inventory/read-edn path before) :form form :path path}))
    (= :project (first form)) (projection-leaf source starts loc)
    (and (= :ref (first form)) (map? (second form)))
    (inline-edit source starts loc (second form) path)
    configured? (typed-leaf source starts loc)
    (= spelling :bare-value) (leaf-edit source starts loc :wrap-value path)
    (or (= spelling :ref-wrapper) (= :value (first form)))
    (leaf-edit source starts loc :map-expression-to-vector path)
    :else (leaf-edit source starts loc :wrap-ref path)))


(defn- inline-ledger-text
  [body]
  (str/join (map (fn [key]
                   (when (contains? body key)
                     (str key " " (migration/stable-edn (get body key)) " ")))
                 [:legacy-forms :legacy-args-order :legacy-scope-names :legacy-scope-copies :legacy-order])))


(defn- binding-insertions
  [bindings]
  (apply str (map (fn [[path form]]
                    (str " " (migration/stable-edn path) " " (migration/stable-edn form)))
                  (sort-by (comp pr-str key) bindings))))


(defn- scoped-path?
  [path]
  (and (vector? path) (< 1 (count path)) (every? simple-keyword? path)))


(defn- copied-bindings?
  [bindings]
  (and (map? bindings)
       (every? (fn [[path expression]]
                 (when (and (scoped-path? path) (vector? expression) (= 2 (count expression)))
                   (let [[op origin] expression]
                     (case op
                       :value true
                       :project (and (scoped-path? origin) (not= origin path) (= (peek origin) (peek path)))
                       false))))
               bindings)))


(defn- append-bindings-edit
  [source starts args bindings]
  (when-not (and (= :map (z/tag args)) (seq bindings) (copied-bindings? bindings)
                 (not-any? #(key-loc args %) (keys bindings)))
    (refuse :invalid-scoped-copies {}))
  (let [{:keys [end]} (node-edit source starts args identity)
        insertion (dec end)]
    {:start insertion :end insertion :before "" :after (binding-insertions bindings)}))


(defn- mixed-inline-field
  [source starts {:keys [bind configure legacy-forms]} targets path [key-node value-node]]
  (let [field (z/sexpr key-node)
        target (get targets field field)
        config-path (if (vector? target) target [field])
        binding? (contains? bind target)
        key (node-edit source starts key-node #(if (and binding? (keyword? target))
                                                 %
                                                 (migration/stable-edn config-path)))
        value (if binding?
                (or (inline-binding-edit source starts value-node (get bind target)
                                         (get legacy-forms field) (contains? configure [field])
                                         (into path [:args field]))
                    (node-edit source starts value-node identity))
                (annotation-spec-edit source starts value-node field (get configure config-path)))]
    {:field field :start (:start key) :end (:end value) :binding? binding?
     :text (str (:after key) (subs source (:end key) (:start value)) (:after value))
     :configuration (when-let [spec (:configuration value)]
                      (str (migration/stable-edn [field]) " " spec))}))


(defn- mixed-inline-edit
  [source starts loc {:keys [input bind legacy-scope-names legacy-scope-copies] :as body} path]
  (let [args (value-at loc [:args])
        targets (set/map-invert legacy-scope-names)
        key (rename-edit source starts loc nil [:args] :bind)
        span (node-edit source starts args identity)
        fields (mapv (partial mixed-inline-field source starts body targets path)
                     (partition 2 (children args)))
        chunks (into {} (field-chunks source span fields))
        binding-text (str (str/join (map (comp chunks :field) (filter :binding? fields)))
                          (binding-insertions (select-keys bind (keys legacy-scope-copies))))
        configs (concat (map (comp chunks :field) (remove :binding? fields))
                        (keep :configuration fields))]
    {:start (:start key) :end (:end span)
     :before (subs source (:start key) (:end span))
     :after (str (inline-ledger-text body)
                 (when (seq input) (str ":input " (migration/stable-edn input) " "))
                 ":bind" (subs source (:end key) (:start span)) "{" binding-text "}"
                 " :configure {" (str/join " " configs) "}")}))


(defn- inline-field-edits
  [source starts loc {:keys [bind input configure legacy-forms legacy-order legacy-args-order legacy-scope-names legacy-scope-copies] :as body} path]
  (if legacy-args-order
    (if (and (empty? bind) (empty? input))
      [(annotation-map-edit source starts loc configure (str ":bind {} " (inline-ledger-text body)) legacy-scope-names)]
      [(mixed-inline-edit source starts loc body path)])
    (let [source-fields (format/inline-source-fields body)
          args (value-at loc [:args])
          fields (mapv (fn [[target form]]
                         (let [field (get source-fields target)
                               arg-path [:args field]
                               edit (inline-binding-edit source starts (value-at loc arg-path) form
                                                         (get legacy-forms field) (contains? configure [field])
                                                         (into path arg-path))
                               key-edit (when (vector? target)
                                          (node-edit source starts (key-loc args field)
                                                     (constantly (migration/stable-edn target))))]
                           [field edit key-edit]))
                       (apply dissoc bind (keys legacy-scope-copies)))
          configurations (keep (fn [[field edit]]
                                 (when-let [spec (:configuration edit)]
                                   (str (migration/stable-edn [field]) " " spec)))
                               fields)
          key (rename-edit source starts loc nil [:args] :bind)]
      (cons (assoc key :after (str (when (seq input) (str ":input " (migration/stable-edn input) " "))
                                   (when (seq configurations) (str ":configure {" (str/join " " configurations) "} "))
                                   (when (seq legacy-forms) (str ":legacy-forms " (migration/stable-edn legacy-forms) " "))
                                   (when (seq legacy-scope-names) (str ":legacy-scope-names " (migration/stable-edn legacy-scope-names) " "))
                                   (when (seq legacy-scope-copies) (str ":legacy-scope-copies " (migration/stable-edn legacy-scope-copies) " "))
                                   (when (seq legacy-order) (str ":legacy-order " (migration/stable-edn legacy-order) " "))
                                   ":bind"))
            (cond-> (into [] (comp (mapcat rest) (remove nil?)) fields)
              (seq legacy-scope-copies)
              (conj (append-bindings-edit source starts args (select-keys bind (keys legacy-scope-copies)))))))))


(defn- inline-type-edit
  [source starts loc]
  (when-let [key (key-loc loc :type)]
    (let [start (:start (node-edit source starts key identity))
          end (:end (node-edit source starts (z/right key) identity))
          before (subs source start end)]
      {:start start :end end :before before :after ""
       :configuration (str "{" before "}")})))


(defn- inline-edit
  [source starts loc body path]
  (let [span (node-edit source starts loc identity)
        original (format/inline-binding-body (inventory/read-edn path (:before span)))
        type-edit (inline-type-edit source starts loc)]
    (when-not (and original (migration/same-edn? original (format/inline-origin body)))
      (refuse :unsupported-inline-declaration {:path path}))
    (let [edits (when (contains? original :args) (inline-field-edits source starts loc body path))
          edits (cond-> edits type-edit (conj type-edit))
          local (into [] (map #(-> % (update :start - (:start span)) (update :end - (:start span))))
                      (sort-by :start edits))
          after (str "[:ref " (patched-text (:before span) local) "]")]
      (when (and (:legacy-args-order body)
                 (not (migration/same-edn? [:ref body] (inventory/read-edn path after))))
        (refuse :planner-ledger-mismatch {:path path}))
      (cond-> (assoc span :after after)
        type-edit (assoc :configuration (:configuration type-edit))))))


(defn- remove-marker-to-vector
  [source starts loc marker]
  (let [{:keys [start before] :as span} (node-edit source starts loc identity)
        key (key-loc loc marker)
        remove-marker (mapv #(-> (node-edit source starts % (constantly ""))
                                 (update :start - start)
                                 (update :end - start))
                            [key (z/right key)])
        after (patched-text before remove-marker)]
    (assoc span :after (str "[" (subs after 1 (dec (count after))) "]"))))


(defn- leaf-edit
  [source starts leaf op path]
  (case op
    :occupied-reference
    (let [form (z/sexpr leaf)]
      (when-not (and (map? form) (= #{:as :ref} (set (keys form)))
                     (simple-keyword? (:as form)) (keyword? (:ref form)))
        (refuse :unsupported-expression-map {:path path}))
      (remove-marker-to-vector source starts leaf :as))
    :wrap-value (node-edit source starts leaf #(str "[:value " % "]"))
    :local-projection (projection-leaf source starts leaf)
    :wrap-ref
    (do
      (when-not (keyword? (z/sexpr leaf))
        (refuse :non-keyword-reference {:path path}))
      (node-edit source starts leaf #(str "[:ref " % "]")))
    :map-expression-to-vector
    (let [nodes (when (= :map (z/tag leaf)) (children leaf))]
      (if (and nodes (key-loc leaf :literal?) (format/legacy-literal-marker? (z/sexpr leaf)))
        (remove-marker-to-vector source starts leaf :literal?)
        (do
          (when-not (and (= 2 (count nodes)) (#{:value :ref} (z/sexpr (first nodes)))
                         (or (= :value (z/sexpr (first nodes)))
                             (keyword? (z/sexpr (second nodes)))))
            (refuse :unsupported-expression-map {:path path}))
          (node-edit source starts leaf #(str "[" (subs % 1 (dec (count %))) "]")))))
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
        chunks (field-chunks source raw spans)
        parts (into {} chunks)
        configuration (remove #(= :as (first %)) chunks)]
    (when-not (and (contains? parts :as) (every? #{:as :type :description} (keys parts)) (simple-keyword? (:as form))
                   (or (not (contains? form :type)) (format/configuration-type? (:type form)))
                   (or (not (contains? form :description)) (string? (:description form))))
      (refuse :unsupported-projection {}))
    (cond-> (assoc raw :after (str "[" (:as parts) "]"))
      (seq configuration) (assoc :configuration (str "{" (str/join (map second configuration)) "}")))))


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


(defn- annotation-spec-edit
  [source starts leaf field configuration]
  (let [span (node-edit source starts leaf identity)
        spec (inventory/read-edn [:args field] (:before span))]
    (when-not (and (annotations/source-form? field spec)
                   (= configuration (select-keys spec [:type :description])))
      (refuse :annotation-origin-mismatch {:path [:args field]}))
    (let [edits [(node-edit source starts (key-loc leaf :as) (constantly ""))
                 (node-edit source starts (value-at leaf [:as]) (constantly ""))]
          local (map #(-> % (update :start - (:start span)) (update :end - (:start span))) edits)]
      (assoc span :after (patched-text (:before span) local)))))


(defn- partition-composed-edit
  [source starts loc {:keys [definition path declarations position-inputs operators forwarded-bindings scoped-paths copied-bindings]}]
  (when-not (and (= [:args] path) (or (key-loc loc :parent) (key-loc loc :parents))
                 (not-any? #(key-loc loc %) [:input :bind :configure])
                 (vector? declarations) (= (count declarations) (count (set declarations)))
                 (or (nil? forwarded-bindings) (map? forwarded-bindings))
                 (every? (fn [[target expression]]
                           (and (vector? target) (<= 2 (count target)) (every? simple-keyword? target)
                                (= [:project [(peek target)]] expression)
                                (some #{(peek target)} declarations))) forwarded-bindings)
                 (map? operators) (not-any? (set declarations) (keys operators))
                 (or (nil? scoped-paths)
                     (and (map? scoped-paths)
                          (every? (fn [[field target]] (and (contains? operators field) (scoped-path? target) (= field (peek target))))
                                  scoped-paths)))
                 (or (nil? copied-bindings) (copied-bindings? copied-bindings)))
    (refuse :invalid-field-partition {:definition definition}))
  (let [args (value-at loc path)
        input? (or (seq declarations) (seq position-inputs))
        extra-fields (map (fn [[field spec]] (str (pr-str field) " " (pr-str spec))) position-inputs)
        key-edit (rename-edit source starts loc definition path (if input? :input :bind))
        map-span (node-edit source starts args identity)
        annotation-fields (into #{} (keep (fn [[field {:keys [op]}]]
                                            (when (= :field-annotation op) field))) operators)
        spans (field-spans source starts args
                           (fn [field value]
                             (let [operation (get operators field)
                                   op (if (map? operation) (:op operation) operation)]
                               (case op
                                 nil (node-edit source starts value identity)
                                 :typed-expression (typed-leaf source starts value)
                                 :field-annotation (annotation-spec-edit source starts value field (:configuration operation))
                                 :list-builder (do
                                                 (when-not (and (= definition (:definition operation)) (= [:args field] (:path operation)))
                                                   (refuse :invalid-field-partition {:definition definition}))
                                                 (list-builder-edit source starts value operation))
                                 :inline-ref (inline-edit source starts value (:body operation) [:args field])
                                 (leaf-edit source starts value op [:args field])))))
        spans (mapv (fn [{:keys [field] :as span}]
                      (if-let [key (or (get scoped-paths field) (when (annotation-fields field) [field]))]
                        (update span :text #(str (pr-str key) (subs % (count (str field)))))
                        span))
                    spans)
        chunks (field-chunks source map-span spans)
        by-field (into {} chunks)
        owns? (set declarations)
        own-chunks (filter (comp owns? first) chunks)
        ;; A large legacy args map may iterate in hash order. Preserve the
        ;; original slot positions when the smaller :input becomes an array map.
        ordered (if (= declarations (vec (keys (into {} own-chunks))))
                  (map second own-chunks) (map by-field declarations))
        bindable? (complement (into owns? annotation-fields))
        binds (concat (map second (filter (comp bindable? first) chunks))
                      (keep #(when (seq %) (binding-insertions %)) [forwarded-bindings copied-bindings]))
        configs (concat (map second (filter (comp annotation-fields first) chunks))
                        (keep :configuration spans))]
    (when-not (and (= :map (z/tag args)) (= (set (keys by-field)) (into owns? (keys operators))))
      (refuse :invalid-field-partition {:definition definition}))
    {:start (:start key-edit) :end (:end map-span)
     :before (subs source (:start key-edit) (:end map-span))
     :after (str (:after key-edit) (subs source (:end key-edit) (:start map-span))
                 (when input? (str "{" (str/join ordered) (when (and (seq declarations) (seq position-inputs)) " ")
                                   (str/join " " extra-fields) "}"))
                 (when (and input? (seq binds)) " :bind ")
                 (when (seq binds) (str "{" (str/join binds) "}"))
                 (when (seq configs) (str " :configure {" (str/join " " configs) "}")))}))


(defn list-builder-operation
  "Build a checksum-bound edit for a single independently verified list field.
   Its enclosing definition can still have other migration blockers."
  ([source field fragment name->id] (list-builder-operation source field fragment name->id {}))
  ([source field fragment name->id inline-items]
   (let [original (get-in source [:args field])
         names (source-list/field-names name->id (:namespace source) original)
         form (source-list/source-form original names inline-items)
         checked (list-builder/parse-fragment (:binding-id fragment) form names (:owner-id fragment) inline-items)]
     (when-not (and (= (:owner-id fragment) (ids/fn-id (:namespace source) (:name source)))
                    (= (:binding-id fragment) (ids/binding-id (:owner-id fragment) (:target-id fragment)))
                    (migration/same-edn? checked (dissoc fragment :owner-id :target-id)))
       (refuse :list-fragment-drift {}))
     {:op :list-builder :definition (:name source) :path [:args field] :original original :form form})))


(defn- position-type-edit
  [source starts loc]
  ;; Semantic type data moves to the owner declaration. Only keyword leaves
  ;; and grouping delimiters may be dropped at the occurrence.
  (doseq [child (tree-seq #(= :vector (z/tag %)) children loc)]
    (when-not (or (= :vector (z/tag child)) (and (= :token (z/tag child)) (keyword? (z/sexpr child))))
      (refuse :unsupported-positional-type-syntax {})))
  (node-edit source starts loc #(str/join (re-seq #";[^\r\n]*|[\s,]+" %))))


(defn- position-item-edit
  [source starts node]
  (let [span (node-edit source starts node identity)
        spans (mapv (fn [[k v]]
                      (let [key-name (z/sexpr k)
                            key-span (node-edit source starts k (case key-name :as (constantly ":project") :type (constantly "") identity))
                            value (case key-name
                                    :as (node-edit source starts v #(str "[" % "]"))
                                    :type (position-type-edit source starts v)
                                    (node-edit source starts v identity))]
                        {:field key-name :start (:start key-span) :end (:end value)
                         :text (str (:after key-span) (subs source (:end key-span) (:start value)) (:after value))}))
                    (partition 2 (children node)))
        groups (group-by (comp boolean #{:description :required} first) (field-chunks source span spans))
        text (fn [policy?] (str/join (map second (get groups policy?))))]
    (assoc span :after (str "[" (text false) (when (get groups true) (str " {" (text true) "}")) "]"))))


(defn- list-item-edit
  [source starts path node [operator payload :as expression]]
  (cond
    (format/typed-list-reference? expression)
    (let [{:keys [after configuration] :as edit} (typed-leaf source starts node)]
      (assoc (dissoc edit :configuration) :after (str (subs after 0 (dec (count after))) " " configuration "]")))
    (= :project operator) (position-item-edit source starts node)
    (and (= :ref operator) (map? payload)) (inline-edit source starts node payload path)
    :else (let [wrapped? (and (= :map (z/tag node)) (key-loc node operator))
                op (if wrapped? :map-expression-to-vector (if (= :ref operator) :wrap-ref :wrap-value))]
            (leaf-edit source starts node op path))))


(defn- list-builder-edit
  [source starts loc {:keys [original form path]}]
  (let [span (node-edit source starts loc identity)
        bare? (vector? original)
        vector-loc (if bare? loc (value-at loc [:append]))
        item-nodes (children vector-loc)
        expressions (get-in form [1 :items])]
    (when-not (and (= :vector (z/tag vector-loc))
                   (migration/same-edn? original (inventory/read-edn "<list>" (:before span)))
                   (= (count expressions) (count item-nodes)))
      (refuse :list-fragment-drift {:path path}))
    (let [items (mapv (fn [position node expression]
                        (list-item-edit source starts (cond-> path (not bare?) (conj :append) true (conj position))
                                        node expression))
                      (range) item-nodes expressions)
          closed-key (when-not bare? (key-loc loc :closed))
          edits (cond-> items
                  (not bare?) (conj (node-edit source starts (key-loc loc :append) (constantly ":items")))
                  closed-key (conj (node-edit source starts closed-key (constantly ":tail"))
                                   (node-edit source starts (z/right closed-key) (constantly (str (get-in form [1 :tail]))))))
          local (mapv #(-> % (update :start - (:start span)) (update :end - (:start span))) (sort-by :start edits))
          inner (patched-text (:before span) local)
          spec (cond bare? (str "{:tail :open :items " inner "}")
                     closed-key inner
                     :else (str (subs inner 0 (dec (count inner))) " :tail :open}"))
          after (str "[:list-builder " spec "]")]
      (when-not (migration/same-edn? form (inventory/read-edn "<generated-list>" after))
        (refuse :list-fragment-drift {:path path}))
      (assoc span :after after))))


(defn- self-pin-edit
  [source starts loc {:keys [definition path type]}]
  (let [[_ field] path
        args (value-at loc [:args])
        spec (value-at args [field])
        original (inventory/read-edn path (z/string args))]
    (when-not (and (= [:args field] path) (key-loc loc :parent) (not-any? #(key-loc loc %) [:bind :input :configure])
                   (rich/callable-shape? type) (= {field {:as field :type type}} original))
      (refuse :self-pin-origin-mismatch {:path path}))
    (let [key-edit (rename-edit source starts loc definition [:args] :configure)
          edits [key-edit (node-edit source starts (key-loc args field) #(str "[" % "]"))
                 (node-edit source starts (key-loc spec :as) (constantly ""))
                 (node-edit source starts (value-at spec [:as]) (constantly ""))]
          start (:start key-edit)
          end (:end (node-edit source starts args identity))
          before (subs source start end)
          local (map #(-> % (update :start - start) (update :end - start)) edits)]
      {:start start :end end :before before :after (patched-text before local)})))


(defn- annotation-map-edit
  [source starts loc configuration prefix & [scope-names]]
  (let [args (value-at loc [:args])
        original (inventory/read-edn "<annotation>" (:before (node-edit source starts args identity)))
        scoped-paths (into {} (map (fn [[path field]] [field path])) scope-names)
        path-for #(get scoped-paths % [%])]
    (when-not (and (or (key-loc loc :parent) (key-loc loc :parents))
                   (not-any? #(key-loc loc %) [:bind :input :configure])
                   (map? original) (seq original)
                   (every? (fn [[field spec]] (annotations/source-form? field spec)) original)
                   (= configuration (into {} (map (fn [[field spec]] [(path-for field) (select-keys spec [:type :description])])) original)))
      (refuse :annotation-origin-mismatch {}))
    (let [key-edit (assoc (rename-edit source starts loc nil [:args] :configure) :after (str prefix ":configure"))
          edits (into [key-edit]
                      (mapcat (fn [[key spec]]
                                [(node-edit source starts key (if-let [path (get scoped-paths (z/sexpr key))]
                                                                (constantly (migration/stable-edn path))
                                                                #(str "[" % "]")))
                                 (annotation-spec-edit source starts spec (z/sexpr key)
                                                       (get configuration (path-for (z/sexpr key))))]))
                      (partition 2 (children args)))
          start (:start key-edit)
          end (:end (node-edit source starts args identity))
          before (subs source start end)
          local (map #(-> % (update :start - start) (update :end - start)) (sort-by :start edits))]
      {:start start :end end :before before :after (patched-text before local)})))


(defn- projection-declaration-edit
  [source starts loc {:keys [definition path input fields position-inputs]}]
  (when-not (and (map? input) (vector? fields) (seq fields) (= (count fields) (count input))
                 (every? #(and (vector? %) (= 2 (count %)) (every? simple-keyword? %)) fields)
                 (= (set (keys input)) (set (map second fields)))
                 (or (key-loc loc :parent) (key-loc loc :parents)) (not (key-loc loc :input)))
    (refuse :invalid-projection-declaration {:definition definition}))
  (let [projections (mapv (fn [[field exposed]]
                            (let [spec (get input exposed)
                                  target (value-at loc [:args field])
                                  form (z/sexpr target)
                                  pin (:configuration (projection-leaf source starts target))]
                              (when-not (and (map? spec) (= #{:type :required} (set (keys spec)))
                                             (format/projection-input-type? (:type spec)) (false? (:required spec))
                                             (= (cond-> {:as exposed}
                                                  (contains? form :type) (assoc :type (:type spec))
                                                  (contains? form :description) (assoc :description (:description form))) form))
                                (refuse :invalid-projection-declaration {:definition definition}))
                              {:field field :exposed exposed :pin pin}))
                          fields)
        declarations (into (mapv (fn [{:keys [exposed]}] [exposed (get input exposed)]) projections) position-inputs)
        declared (str/join " " (map (fn [[name spec]] (str name " " (pr-str spec))) declarations))
        pins (keep (fn [{:keys [field pin]}] (when pin (str (pr-str [field]) " " pin))) projections)]
    (assoc (rename-edit source starts loc definition path :bind)
           :after (str ":input {" declared "}"
                       (when (seq pins) (str " :configure {" (str/join " " pins) "}"))
                       " :bind"))))


(defn- query-lookup-edit
  [source starts loc {:keys [op path from to definition]}]
  (let [target (value-at loc path)]
    (when-not (migration/same-edn? from (z/sexpr target))
      (refuse :planner-ledger-mismatch {:definition definition :path path}))
    (case op
      :query-lookup-header
      (let [edit (rename-edit source starts loc definition [:args] :bind)]
        (when (or (key-loc loc :return-type) (not= {:request {:type :ring-request-shape}} to))
          (refuse :planner-ledger-mismatch {:definition definition}))
        (assoc edit :after (str ":return-type :nullable-text :input " (migration/stable-edn to) " :bind")))
      :query-lookup-parent (node-edit source starts target (constantly (migration/stable-edn to)))
      :query-lookup-bind
      (let [nodes (tree-seq node/inner? node/children (z/node target))
            _ (when (some #(= :uneval (node/tag %)) nodes) (refuse :unsupported-reader-discard {}))
            comments (keep #(when (= :comment (node/tag %)) (node/string %)) nodes)]
        (node-edit source starts target (constantly (str (when (seq comments) (str (str/join "\n" comments) "\n"))
                                                         (migration/stable-edn to))))))))


(defn- operation-edit
  [source starts definitions {:keys [op definition path] :as operation}]
  (when-not (and (keyword? definition) (vector? path) (seq path))
    (refuse :invalid-operation {:operation operation}))
  (let [loc (or (get definitions definition)
                (refuse :missing-definition {:definition definition}))]
    (case op
      (:query-lookup-header :query-lookup-parent :query-lookup-bind)
      (query-lookup-edit source starts loc operation)
      :field-annotations (annotation-map-edit source starts loc (:configuration operation)
                                              (if (:empty-bind? operation) ":bind {} " "")
                                              (:scope-names operation))
      :self-pin (self-pin-edit source starts loc operation)
      :list-builder (list-builder-edit source starts (argument-loc loc path) operation)
      :scope-key
      (let [public-path (:public-path operation)
            old (second path)]
        (when-not (and (= [:args old] path) (< 1 (count public-path)) (every? simple-keyword? public-path)
                       (= old (peek public-path)))
          (refuse :invalid-scoped-path {:path path}))
        (let [args (value-at loc [:args])
              field (or (key-loc args old) (refuse :missing-path {:path path}))]
          (when (key-loc args public-path) (refuse :existing-target {:path path}))
          (node-edit source starts field (constantly (pr-str public-path)))))

      :rename-input
      (do
        (when (some #(key-loc loc %)
                    [:parent :parents :type :refine :list :union :map :tuple :variant :fn-type :marker])
          (refuse :not-base-declaration {:definition definition}))
        (when (key-loc loc :input)
          (refuse :existing-input {:definition definition}))
        (rename-edit source starts loc definition path :input))

      :declare-projection-input
      (projection-declaration-edit source starts loc operation)

      :rename-bind
      (do
        (when-not (some #(some-> (key-loc loc %) z/right z/sexpr) [:parent :parents])
          (refuse :not-composed-definition {:definition definition}))
        (rename-edit source starts loc definition path :bind))

      (:wrap-value :wrap-ref :map-expression-to-vector :local-projection :occupied-reference)
      (leaf-edit source starts (argument-loc loc path) op path)

      :inline-ref
      (inline-edit source starts (argument-loc loc path) (:body operation) path)

      :scoped-copies
      (append-bindings-edit source starts (value-at loc path) (:bindings operation))

      :partition-composed-args
      (partition-composed-edit source starts loc operation)

      :move-args-entry
      (refuse :unsupported-comment-safe-move
              {:recommendation "Define comment ownership and exact source/destination spans before moving entries."})

      (refuse :unknown-operation {:op op}))))


(defn- projection-operation
  [source {:keys [source-path origin expression rename policy]}]
  (let [{exposed :alias :keys [alias-id field-id pin-id declaration]} rename]
    (when-not (and (simple-keyword? exposed)
                   (= (cond-> {:as exposed} pin-id (assoc :type (:type declaration))
                              (contains? rename :description) (assoc :description (:description rename))) (get-in source source-path))
                   (= [:project [exposed]] expression)
                   (= (cond-> {} pin-id (assoc :type-override-fn-id pin-id)
                              (contains? rename :description) (assoc :description (:description rename))) policy)
                   (uuid? alias-id) (= field-id (:slot-id origin)) (uuid? field-id))
      (refuse :planner-ledger-mismatch {:path source-path}))
    {:op :local-projection :definition (:name source) :path source-path}))


(defn- argument-operation
  [source {:keys [source-path kind expression policy]}]
  (let [[operator payload] expression
        raw (get-in source source-path)
        inline? (and (= :ref operator)
                     (when-let [body (format/inline-binding-body raw)]
                       (migration/same-edn? body (format/inline-origin payload))))
        wrapper? (and (map? raw) (some #(contains? raw %) [:value :ref]))
        typed? (and (or inline? (and wrapper? (= #{operator :type} (set (keys raw)))))
                    (format/configuration-type? (:type raw))
                    (= #{:type-override-fn-id} (set (keys policy))) (uuid? (:type-override-fn-id policy)))]
    (when-not (and (= :binding kind) (or typed? (empty? policy))
                   (vector? source-path) (= 2 (count source-path)) (= :args (first source-path))
                   (contains? (:args source) (second source-path))
                   (vector? expression) (= 2 (count expression)) (#{:value :ref} operator)
                   (or (= :value operator) (qualified-keyword? payload) inline?))
      (refuse :unsupported-source-argument {:path source-path}))
    (when-not (if wrapper?
                (and (or typed? (= 1 (count raw)) (format/legacy-literal-marker? raw))
                     (contains? raw operator)
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


(defn- binding-operation
  [source {:keys [source-path list-builder list-ref-names list-inline-items expression rename] :as argument}]
  (cond
    rename (projection-operation source argument)
    list-builder (let [op (list-builder-operation source (second source-path) (peek (:fragments list-builder)) list-ref-names list-inline-items)]
                   (when-not (migration/same-edn? (:form op) expression)
                     (refuse :planner-ledger-mismatch {:path source-path}))
                   op)
    :else (argument-operation source argument)))


(defn- forwarded-bindings
  [fields]
  (into {} (mapcat (fn [{:keys [source-path scope-certificate forwarded-certificates]}]
                     (map (fn [{:keys [public-path row source-names edges]}]
                            (when-not (and (= [(peek source-path)] source-names) (vector? public-path)
                                           (= (inc (count edges)) (count public-path)) (seq edges)
                                           (= (mapv :binding-id edges) (:scope-path row))
                                           (= (peek public-path) (peek source-path))
                                           (= "projection" (:expression-kind row)) (uuid? (:id row)))
                              (refuse :planner-ledger-mismatch {:path source-path}))
                            [public-path [:project source-names]])
                          (or forwarded-certificates (some-> scope-certificate vector))))) fields))


(defn- copied-bindings
  [arguments]
  (into {}
        (mapcat
          (fn [{:keys [source-path scope-certificate scoped-copies expression]}]
            (for [{:keys [public-path row copy-of shared-source edges]} scoped-copies
                  :let [{primary :public-path original :row} scope-certificate]]
              (do
                (when-not (and (= copy-of (:id original)) (uuid? copy-of)
                               (= (count public-path) (inc (count edges)))
                               (= (peek public-path) (peek primary) (peek source-path))
                               (= (:scope-path row) (mapv :binding-id edges))
                               (= (:target-path original) (:target-path row)))
                  (refuse :planner-ledger-mismatch {:path source-path}))
                [public-path
                 (case (:expression-kind row)
                   "projection" (do
                                  (when-not (and (= :ref (first expression))
                                                 (= primary (:public-path shared-source))
                                                 (= (:scope-path original) (:projection-scope-path row))
                                                 (= (:target-path original) (:projection-path row)))
                                    (refuse :planner-ledger-mismatch {:path source-path}))
                                  [:project primary])
                   "value" (do
                             (when-not (and (= :value (first expression)) (= (:value row) (second expression)))
                               (refuse :planner-ledger-mismatch {:path source-path}))
                             expression)
                   (refuse :planner-ledger-mismatch {:path source-path}))])))
          arguments)))


(defn- self-pin-operation
  [source proposed [argument & more] pin]
  (let [path (:source-path argument)
        field (second path)
        config {[field] {:type (:type pin)}}]
    (when-not (and (= [:args field] path) (empty? more)
                   (= {field {:as field :type (:type pin)}} (:args source))
                   (= proposed (assoc (dissoc source :args) :configure config)))
      (refuse :planner-ledger-mismatch {:path path}))
    [{:op :self-pin :definition (:name source) :path path :type (:type pin)}]))


(defn- composed-entry-operations
  [source proposed legacy annotations has-args? rename]
  (let [arguments (:arguments legacy)
        paths (mapv :source-path arguments)
        {fields :declaration binding-entries :binding} (group-by :kind arguments)
        binds (remove :annotation binding-entries)
        declarations (into (array-map) (map (juxt (comp second :source-path) :spec)) fields)
        alias-entries (filterv :rename binds)
        alias-input (into {} (map (comp (juxt :alias :declaration) :rename)) alias-entries)
        position-inputs (positions/declarations arguments)
        declarations (merge declarations alias-input position-inputs)
        forwarded (forwarded-bindings fields)
        copies (copied-bindings binds)
        bindings (into (into forwarded copies) (map (fn [arg] [(or (get-in arg [:scope-certificate :public-path]) (second (:source-path arg))) (:expression arg)])) binds)
        scopes (keep (fn [{:keys [scope-certificate source-path origin]}]
                       (when-let [{:keys [public-path row]} scope-certificate]
                         (when-not (and (= source-path (:source-path scope-certificate)) (= (:id row) (:binding-id origin))
                                        (= [(:slot-id origin)] (:target-path row)))
                           (refuse :planner-ledger-mismatch {:path source-path}))
                         {:op :scope-key :definition (:name source) :path source-path :public-path public-path})) binds)
        occupied (:occupied-reference-alias legacy)
        operations (mapv (fn [{:keys [source-path origin annotation] :as argument}]
                           (cond
                             (= source-path [:args (:field occupied)])
                             (let [raw (get-in source source-path)
                                   cleaned (update-in source source-path dissoc :as)]
                               (when-not (and (= #{:as :ref} (set (keys raw))) (= (:as raw) (:alias occupied))
                                              (= (:binding-id origin) (:reference-binding-id occupied))
                                              (= (:slot-id origin) (get-in occupied [:alias-slot :source-slot-id])))
                                 (refuse :planner-ledger-mismatch {:path source-path}))
                               (assoc (binding-operation cleaned argument) :op :occupied-reference))
                             annotation
                             {:op :field-annotation :definition (:name source) :path source-path
                              :configuration (annotations/configuration annotation)}
                             :else (binding-operation source argument)))
                         binding-entries)
        configuration (into {} (keep (fn [{:keys [op path configuration]}]
                                       (cond
                                         (= :field-annotation op) [[(second path)] configuration]
                                         (or (= :typed-expression op)
                                             (and (= :inline-ref op) (contains? (get-in source path) :type))
                                             (and (= :local-projection op)
                                                  (some #(contains? (get-in source path) %) [:type :description])))
                                         [[(second path)] (select-keys (get-in source path) (if (= :local-projection op) [:type :description] [:type]))])))
                            operations)]
    (when-not (and (or (not has-args?) (map? (:args source)))
                   (vector? arguments) (= (count paths) (count (set paths)))
                   (= (count arguments) (+ (count fields) (count binding-entries)))
                   (= (set paths) (set (for [field (keys (:args source))] [:args field])))
                   (= (+ (count forwarded) (count binds) (count copies)) (count bindings))
                   (every? #(= (:spec %) (get-in source (:source-path %))) fields)
                   (= proposed (cond-> (dissoc source :args)
                                 (seq declarations) (assoc :input declarations)
                                 (or (seq binds) (and has-args? (empty? fields) (empty? annotations))) (assoc :bind bindings)
                                 (seq configuration) (assoc :configure configuration))))
      (refuse :planner-ledger-mismatch {}))
    (cond
      (seq alias-entries)
      (do
        (when (seq annotations) (refuse :unsupported-annotation-mix {}))
        (when-not (and (empty? fields) (empty? scopes) (empty? copies)
                       (not-any? #(= :typed-expression (:op %)) operations))
          (refuse :unsupported-projection-mix {}))
        (into [{:op :declare-projection-input :definition (:name source) :path [:args]
                :input alias-input :position-inputs position-inputs
                :fields (mapv (fn [{:keys [source-path rename]}] [(second source-path) (:alias rename)]) alias-entries)}]
              operations))

      (or (seq fields) (seq configuration) (seq position-inputs))
      [{:op :partition-composed-args :definition (:name source) :path [:args]
        :declarations (vec (remove (set (keys position-inputs)) (keys declarations)))
        :position-inputs position-inputs
        :forwarded-bindings forwarded
        :scoped-paths (into {} (map (juxt (comp second :path) :public-path)) scopes)
        :copied-bindings copies
        :operators (into {} (map (juxt (comp second :path) identity)) operations)}]
      :else (cond-> (into (into (rename :rename-bind) scopes) operations)
              (seq copies) (conj {:op :scoped-copies :definition (:name source) :path [:args]
                                  :bindings copies})))))


(defn- annotation-only-operations
  [source proposed arguments]
  (let [scoped (into {} (keep (fn [{:keys [scope-certificate source-path origin]}]
                                (when-let [{:keys [public-path row edges] :as certificate} scope-certificate]
                                  (when-not (and (= source-path (:source-path certificate))
                                                 (vector? public-path) (every? simple-keyword? public-path)
                                                 (= (inc (count edges)) (count public-path))
                                                 (seq edges) (= (peek source-path) (peek public-path))
                                                 (uuid? (:id row)) (= (:id row) (:binding-id origin))
                                                 (nil? (:expression-kind row))
                                                 (= [(:slot-id origin)] (:target-path row))
                                                 (= (mapv :binding-id edges) (:scope-path row)))
                                    (refuse :planner-ledger-mismatch {:path source-path}))
                                  [public-path (peek source-path)]))) arguments)
        by-name (set/map-invert scoped)
        config (into {} (map (fn [{:keys [source-path annotation scope-certificate]}]
                               (let [field (peek source-path)]
                                 [(get by-name field [field])
                                  (if scope-certificate (:configuration scope-certificate)
                                      (annotations/configuration annotation))]))) arguments)
        empty-bind? (= {} (:bind proposed))]
    (when-not (and (= (count config) (count arguments))
                   (= proposed (cond-> (assoc (dissoc source :args) :configure config)
                                 empty-bind? (assoc :bind {}))))
      (refuse :planner-ledger-mismatch {}))
    [(cond-> {:op :field-annotations :definition (:name source) :path [:args] :configuration config}
       (seq scoped) (assoc :scope-names scoped)
       empty-bind? (assoc :empty-bind? true))]))


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
        pin (:self-pin (first (:arguments legacy)))
        annotations (filter :annotation (:arguments legacy))
        annotation-only? (every? #(or (:annotation %) (contains? (:scope-certificate %) :configuration))
                                 (:arguments legacy))
        rename (fn [op] (if has-args? [{:op op :definition (:name source) :path [:args]}] []))]
    (case (cond (and (= :composed kind) (seq (:arguments legacy)) annotation-only?) :annotations
                (and (= :composed kind) pin) :self-pin
                :else kind)
      :query-lookup
      (do
        (when-not (and (= (dissoc source :parent :args) (dissoc proposed :parent :bind :input :return-type))
                       (= :nullable-text (:return-type proposed)) (map? (:bind proposed)))
          (refuse :planner-ledger-mismatch {:definition (:name source)}))
        (mapv (fn [[op path from to]]
                {:op op :definition (:name source) :path [path] :from from :to to})
              [[:query-lookup-parent :parent (:parent source) (:parent proposed)]
               [:query-lookup-header :args (:args source) (:input proposed)]
               [:query-lookup-bind :args (:args source) (:bind proposed)]]))

      (:query-lookup-inline :response-signature-test)
      (do
        (when-not (and (= (dissoc source :args) (dissoc proposed :bind)) (map? (:bind proposed)))
          (refuse :planner-ledger-mismatch {:definition (:name source)}))
        (conj (rename :rename-bind)
              {:op :query-lookup-bind :definition (:name source) :path [:args]
               :from (:args source) :to (:bind proposed)}))

      :annotations
      (annotation-only-operations source proposed (:arguments legacy))

      :self-pin (self-pin-operation source proposed (:arguments legacy) pin)

      :base
      (do
        (when-not (= proposed (cond-> (dissoc source :args) has-args? (assoc :input (:args source))))
          (refuse :planner-ledger-mismatch {}))
        (rename :rename-input))

      :type
      (do (when-not (= source proposed) (refuse :planner-ledger-mismatch {})) [])

      :composed
      (composed-entry-operations source proposed legacy annotations has-args? rename)

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


(defn- patch
  [source module-ns edits]
  (let [edits (vec (sort-by :start edits))
        target (patched-text source edits)]
    ;; A splice must remain one valid EDN module before it can leave the planner.
    (module-definitions target module-ns)
    {:namespace module-ns
     :original-sha256 (sha256 source)
     :target-sha256 (sha256 target)
     :target-text target
     :edits edits}))


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
        starts (line-starts source)]
    (patch source module-namespace
           (map (fn [operation]
                  (try (operation-edit source starts definitions operation)
                       (catch clojure.lang.ExceptionInfo error
                         (throw (ex-info (ex-message error)
                                         (assoc (ex-data error) :definition (:definition operation)) error)))))
                operations))))


(defn- module-header-edit
  [source starts module]
  (let [root (z/of-string source {:track-position? true})]
    (if (contains? module :input-format)
      (node-edit source starts (value-at root [:input-format]) (constantly "2"))
      (node-edit source starts (z/down root) #(str ":input-format 2 " %)))))


(defn- verified-entry?
  [entry]
  (and (= :supported (:status entry))
       (= :authored-source-equivalent (:verification entry))
       (empty? (:blockers entry))))


(defn- verified-entries
  [definitions entries]
  (let [entries-by-name (into {} (map (juxt #(get-in % [:source :name]) identity)) entries)
        ordered (mapv #(get entries-by-name (:name %)) definitions)]
    (when-not (and (= (count entries-by-name) (count entries) (count ordered))
                   (every? verified-entry? ordered)
                   (every? (fn [[authored entry]]
                             (migration/same-edn? (dissoc authored :namespace :source-file)
                                                  (dissoc (:source entry) :namespace :source-file)))
                           (map vector definitions ordered)))
      (refuse :whole-module-proof-required {}))
    ordered))


(defn plan-verified-module
  "Return a checksum-bound v2 patch only when every original definition has
   an authored proof and the checked context accepts every CST result again.
   verify receives the entries whose proposed forms came from the target text.
   The operation preserves untouched text and never writes any source file."
  [source entries verify]
  (let [module (inventory/read-edn "<CST source>" source)
        module-ns (:namespace module)
        _ (format/require-legacy! module "<CST source>")
        ordered (verified-entries (:fns module) entries)
        definitions (module-definitions source module-ns)
        starts (line-starts source)
        edits (into [(module-header-edit source starts module)]
                    (map #(operation-edit source starts definitions %))
                    (mapcat entry-operations ordered))
        result (patch source module-ns edits)
        target (inventory/read-edn "<CST target>" (:target-text result))
        replay (mapv (fn [entry proposed]
                       (assoc entry :proposed (merge (select-keys (:proposed entry) [:namespace :source-file]) proposed)))
                     ordered (:fns target))
        verified (verify replay)]
    (format/require-v2! target)
    (verified-entries (:fns module) verified)
    result))


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
