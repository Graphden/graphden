(ns graphden.input.format
  "Version boundary shared by the offline reader and the legacy loader.")


(defn local-projection?
  [form]
  (and (vector? form) (= 2 (count form)) (= :project (first form))
       (vector? (second form)) (= 1 (count (second form))) (simple-keyword? (first (second form)))))


(def ^:private inline-metadata
  #{:description :return-type :return-type-wide? :lambda-params :expects-effects
    :effects :branch-local? :tags})


(defn- inline-shape?
  [body body-keys]
  (and (map? body)
       (or (and (keyword? (:parent body)) (not (contains? body :parents)))
           (and (not (contains? body :parent)) (vector? (:parents body))
                (seq (:parents body)) (every? keyword? (:parents body))))
       (every? (into inline-metadata body-keys) (keys body))))


(defn- fields?
  [fields]
  (and (map? fields) (every? simple-keyword? (keys fields))))


(defn legacy-inline?
  "Direct inline forms. The legacy parser decides the meaning of each arg."
  [body]
  (and (inline-shape? body #{:parent :parents :args})
       (or (not (contains? body :args)) (fields? (:args body)))))


(defn- replace-inline-fields
  "Update a field map without moving its key. A nested inline's legacy ID
   includes the printed contents and ordering of the enclosing argument map."
  [body from to fields]
  (into (empty body)
        (keep (fn [[k v]]
                (cond
                  (= from k) [to fields]
                  (= :legacy-forms k) nil
                  :else [k v])))
        body))


(declare inline-origin)


(defn- original-leaf
  [form spelling]
  (cond
    (and (nil? spelling) (not (coll? form))) [form]
    (and (vector? form) (= 2 (count form)))
    (let [[op value] form]
      (case op
        :value (case spelling
                 nil [{:value value}]
                 :bare-value (when (coll? value) [value])
                 nil)
        :ref (case spelling
               nil (cond
                     (keyword? value) [value]
                     (map? value) (when-let [origin (inline-origin value)] [origin]))
               :ref-wrapper (when (keyword? value) [{:ref value}])
               nil)
        nil))))


(defn inline-origin
  "Recover the legacy body from direct v2 binds. :legacy-forms records
   spellings that explicit :value and :ref expressions would otherwise erase.
   The prefix and position of an inline use are supplied by its parent."
  [body]
  (when (inline-shape? body #{:parent :parents :bind :legacy-forms})
    (if-not (contains? body :bind)
      (when-not (contains? body :legacy-forms) body)
      (let [{:keys [bind legacy-forms]} body]
        (when (and (fields? bind)
                   (or (not (contains? body :legacy-forms))
                       (and (map? legacy-forms)
                            (every? (set (keys bind)) (keys legacy-forms))
                            (every? #{:bare-value :ref-wrapper} (vals legacy-forms)))))
          (let [originals (mapv (fn [[k form]]
                                  (when-let [[original] (original-leaf form (get legacy-forms k))]
                                    [k original]))
                                bind)]
            (when (every? some? originals)
              (replace-inline-fields body :bind :args (into (empty bind) originals)))))))))


(defn- migrated-leaf
  [raw [op value :as expression]]
  (case op
    :value (cond
             (and (map? raw) (= #{:value} (set (keys raw)))) [expression nil]
             (coll? raw) [expression :bare-value]
             :else [raw nil])
    :ref (cond
           (legacy-inline? raw) [expression nil]
           (keyword? raw) [[:ref raw] nil]
           (and (map? raw) (= #{:ref} (set (keys raw))) (keyword? (:ref raw)))
           [[:ref (:ref raw)] :ref-wrapper])
    nil))


(defn migrate-inline
  "Form a direct v2 body with the source argument order and spelling. The
   caller must compare the reconstructed body and independently check its UUID."
  [body expressions]
  (if-not (contains? body :args)
    body
    (let [leaves (mapv (fn [[k raw]]
                         (when-let [[form spelling] (migrated-leaf raw (get expressions k))]
                           [k form spelling]))
                       (:args body))]
      (when (every? some? leaves)
        (let [fields (into (empty (:args body)) (map (fn [[k form _]] [k form])) leaves)
              spellings (into (array-map) (keep (fn [[k _ spelling]] (when spelling [k spelling]))) leaves)]
          (cond-> (replace-inline-fields body :args :bind fields)
            (seq spellings) (assoc :legacy-forms spellings)))))))


(defn configuration-type?
  "Named types and collection shapes whose leaves resolve to named types.
   Forms that require new inline type rows are outside this source format slice."
  [type]
  (or (keyword? type)
      (and (vector? type)
           (case (first type)
             :list (= 2 (count type))
             :map (= 3 (count type))
             (:tuple :union) (< 1 (count type))
             false)
           (every? configuration-type? (rest type)))))


(defn require-v2!
  [module]
  (when-not (and (map? module) (= 2 (:input-format module)))
    (throw (ex-info "Structured source requires input format 2"
                    {:type :input/invalid-module :code :input-format-2-required}))))


(defn require-legacy!
  "Reject unsupported headers before the legacy loader validates or executes
   anything in the module. An absent header and explicit version 1 are v1."
  [module path]
  (when (and (map? module) (contains? module :input-format)
             (not= 1 (:input-format module)))
    (throw (ex-info "Module input format is not supported by the production loader"
                    {:type :package-error/input-format :code :unsupported-input-format
                     :path path})))
  (when (some (fn [definition]
                (and (map? definition)
                     (some #(contains? definition %) [:input :bind :configure])))
              (:fns module))
    (throw (ex-info "Structured input declarations require the offline version 2 reader"
                    {:type :package-error/input-format :code :mixed-input-format
                     :path path}))))
