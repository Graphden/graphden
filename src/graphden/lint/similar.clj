(ns graphden.lint.similar
  "Opt-in structural template similarity. A match suggests shared composition;
   it is not proof that two functions have equivalent behavior."
  (:require
    [graphden.lint.core :as lint]
    [graphden.packages.records.slot-resolution :as slot-res]))


(def ^:private max-expansion-depth 24)
(def ^:private max-expanded-fns 512)
(def ^:private max-structural-depth 128)
(def ^:private max-structural-visits 32768)


(defn- ordered-map
  [entries]
  (into (sorted-map-by #(compare (pr-str %1) (pr-str %2))) entries))


(defn- literal-kind
  [value]
  (cond
    (string? value) :text
    (integer? value) :integer
    (number? value) :number
    :else nil))


(defn- template
  [idx fd]
  (let [expanded (volatile! 0)
        visited (volatile! 0)
        literals (atom {})]
    ;; The recursive walk is one indivisible structural algorithm: every
    ;; private expansion shares the same cycle set, size budget and paths.
    (letfn [(incomplete!
              [reason]
              (throw (ex-info "Incomplete similarity template" {:reason reason})))
            (visit!
              [path]
              (when (> (count path) max-structural-depth) (incomplete! :structure-depth))
              (when (> (vswap! visited inc) max-structural-visits)
                (incomplete! :structure-size)))
            (literal
              [value path mask?]
              (visit! path)
              (cond
                (map? value)
                [:map (ordered-map
                        (map (fn [[k v]] [k (literal v (conj path k) mask?)]) value))]

                (vector? value)
                [:vector (mapv (fn [i v] (literal v (conj path i) mask?))
                               (range) value)]

                (sequential? value)
                [:sequence (mapv (fn [i v] (literal v (conj path i) mask?))
                                 (range) value)]

                (set? value)
                [:set (mapv (fn [i v] (literal v (conj path i) mask?))
                            (range) (sort-by pr-str value))]

                (and mask? (literal-kind value))
                (do (swap! literals assoc path value)
                    [:literal (literal-kind value)])

                :else [:literal-exact value]))
            (reference
              [kw seen depth path mask?]
              (visit! path)
              (let [[kind target] (lint/resolve-ref idx kw)]
                (case kind
                  :fn-def (if (and (lint/private-name? (:name target))
                                   (lint/composed? target))
                            [:expand (definition target seen (inc depth) (conj path :expand) mask?)]
                            [:reference (lint/fn-key target)])
                  :base [:base target]
                  :ambiguous [:ambiguous target]
                  [:keyword kw])))
            (argument
              [value seen depth path mask?]
              (visit! path)
              (cond
                (keyword? value) (reference value seen depth path mask?)

                (and (map? value) (or (:parent value) (seq (:parents value))))
                [:inline (definition value seen (inc depth) (conj path :inline) mask?)]

                (and (map? value) (some slot-res/binding-spec-keys (keys value)))
                (let [spec (dissoc value :description)]
                  (case (set (keys spec))
                    #{:ref} (reference (:ref spec) seen depth path mask?)
                    #{:value} (literal (:value spec) path mask?)
                    ;; Type pins, renames and binding policy are exact
                    ;; contracts, not template holes.
                    [:binding (ordered-map
                                (map (fn [[k v]]
                                       [k (case k
                                            :ref (reference v seen depth (conj path k) mask?)
                                            :value (literal v (conj path k) mask?)
                                            [:exact (literal v (conj path k) false)])]) spec))]))

                (vector? value)
                [:list (mapv (fn [i v] (argument v seen depth (conj path i) mask?))
                             (range) value)]

                :else (literal value path mask?)))
            (definition
              [current seen depth path mask?]
              (visit! path)
              (when (> depth max-expansion-depth) (incomplete! :depth))
              (when (> (vswap! expanded inc) max-expanded-fns) (incomplete! :size))
              (let [key (when (:name current) (lint/fn-key current))]
                (when (and key (contains? seen key))
                  (throw (ex-info "Incomplete similarity template"
                                  {:reason :cycle :at key :path path})))
                (let [seen (cond-> seen key (conj key))
                      parents (if (:parent current) [(:parent current)] (vec (:parents current)))
                      operation (some-> (:parent current) name)
                      args (ordered-map
                             (map (fn [[slot value]]
                                    [slot (argument value seen depth (conj path :args slot)
                                                    (and mask?
                                                         (not (or (= slot :tag)
                                                                  (and (= operation "if")
                                                                       (#{:then :else} slot))
                                                                  (and (= operation "equal?")
                                                                       (#{:a :b} slot))))))])
                                  (:args current)))
                      declaration (ordered-map
                                    (map (fn [[k v]] [k (literal v (conj path :declaration k) false)])
                                         (dissoc current :id :name :namespace :description
                                                 :parent :parents :args)))]
                  [:fn
                   [:parents (mapv (fn [i p] (reference p seen depth (conj path :parents i) false))
                                   (range) parents)]
                   [:args args]
                   [:declaration declaration]])))]
      (try
        {:shape (definition fd #{} 0 [] true) :literals @literals}
        (catch clojure.lang.ExceptionInfo e
          (when-not (= "Incomplete similarity template" (ex-message e)) (throw e))
          {:skipped (:reason (ex-data e))
           :at (:at (ex-data e))
           :path (:path (ex-data e))})))))


(defn- fn-nodes
  [shape]
  (let [nodes (atom 0)]
    (letfn [(walk
              [x]
              (when (and (vector? x) (= :fn (first x))) (swap! nodes inc))
              (cond
                (map? x) (doseq [v (vals x)] (walk v))
                (sequential? x) (doseq [v x] (walk v))))]
      (walk shape)
      @nodes)))


(defn analyze
  "Find repeated multi-fn templates. Results reveal only differing paths,
   never literal values. Cyclic/oversize subjects are skipped with a finite
   reason, never grouped by a truncated signature."
  [fn-defs {:keys [base-fn-names]}]
  (let [idx (lint/build-index fn-defs base-fn-names)
        subjects (filter #(and (lint/composed? %) (not (lint/anon-name? (:name %)))) fn-defs)
        analyzed (mapv (fn [fd] (assoc (template idx fd) :fn (lint/fn-key fd))) subjects)
        skipped (->> analyzed (filter :skipped)
                     (map #(select-keys % [:fn :skipped]))
                     (sort-by (comp pr-str :fn)) vec)
        groups (->> analyzed
                    (filter :shape)
                    (group-by :shape)
                    vals
                    (filter #(>= (count %) 2))
                    (keep (fn [members]
                            (let [shape (:shape (first members))
                                  nodes (fn-nodes shape)
                                  paths (into #{} (mapcat (comp keys :literals)) members)
                                  different (->> paths
                                                 (filter #(> (count (set (map (fn [m] (get (:literals m) %)) members))) 1))
                                                 (sort-by pr-str) vec)]
                              (when (and (>= nodes 4) (>= (count different) 3))
                                {:rule :similar-template
                                 :severity :info
                                 :fns (vec (sort-by pr-str (map :fn members)))
                                 :difference-paths different
                                 :expanded-fns nodes}))))
                    (sort-by (juxt (comp - :expanded-fns) (comp pr-str :fns)))
                    vec)]
    {:groups groups :skipped skipped :subjects (count analyzed)}))
