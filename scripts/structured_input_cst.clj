(ns structured-input-cst
  "Pure, checksum-bound CST edits for a semantic migration planner.
   Operations address the original module, never recursively search literal data.
   No package evaluation, filesystem writes, or semantic classification."
  (:require
    [clojure.string :as str]
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


(defn- operation-edit
  [source starts definitions {:keys [op definition path] :as operation}]
  (when-not (and (keyword? definition) (vector? path) (seq path))
    (refuse :invalid-operation {:operation operation}))
  (let [loc (or (get definitions definition)
                (refuse :missing-definition {:definition definition}))]
    (case op
      :rename-input
      (do
        (when-not (= [:args] path)
          (refuse :invalid-rename-path {:path path}))
        (when (some #(key-loc loc %)
                    [:parent :parents :type :refine :list :union :map :tuple :variant :fn-type :marker])
          (refuse :not-base-declaration {:definition definition}))
        (when (key-loc loc :input)
          (refuse :existing-input {:definition definition}))
        (let [key-node (or (key-loc loc :args)
                           (refuse :missing-path {:path path}))]
          (node-edit source starts key-node (constantly ":input"))))

      :wrap-value
      (do
        (when-not (and (= :args (first path)) (<= 2 (count path)))
          (refuse :invalid-argument-path {:path path}))
        (node-edit source starts (value-at loc path) #(str "[:value " % "]")))

      :move-args-entry
      (refuse :unsupported-comment-safe-move
              {:recommendation "Define comment ownership and exact source/destination spans before moving entries."})

      (refuse :unknown-operation {:op op}))))


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
   :operations [{:op :rename-input|:wrap-value :definition KEYWORD :path VECTOR}]}.
   Paths contain map keys and address the ORIGINAL definition. Rename requires
   [:args]; wrap requires an explicit path below :args. Overlaps refuse.
   The caller owns semantic classification. Moving entries is unsupported."
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
