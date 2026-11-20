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
                  (#{:legacy-forms :legacy-order :input :configure} k) nil
                  :else [k v])))
        body))


(declare inline-origin configuration-type?)


(defn inline-binding-body
  "Extract a supported legacy direct declaration. The enclosing field's type
   pin belongs to the binding and was excluded from the legacy child hash."
  [form]
  (when (and (map? form) (or (contains? form :parent) (contains? form :parents)))
    (let [body (dissoc form :type)]
      (when (and (legacy-inline? body)
                 (or (not (contains? form :type)) (configuration-type? (:type form))))
        body))))


(defn- inline-type-position?
  [spelling]
  (and (vector? spelling) (= 2 (count spelling)) (= :inline-type (first spelling))
       (nat-int? (second spelling))))


(defn- add-inline-type
  [form spelling type]
  (when (inline-type-position? spelling)
    (when-let [body (inline-origin form)]
      (let [entries (vec body)
            index (second spelling)]
        (when (<= index (count entries))
          (apply array-map (mapcat identity (into (conj (subvec entries 0 index) [:type type])
                                                  (subvec entries index)))))))))


(defn- typed-origin
  [form spelling type]
  (when (and (vector? form) (= 2 (count form)))
    (let [[op value] form]
      (if (and (= :ref op) (map? value))
        (when-let [original (add-inline-type value spelling type)] [original])
        (when (contains? #{nil :type-first} spelling)
          (let [key (case op
                      :value :value
                      :ref (when (keyword? value) :ref)
                      :project (when (local-projection? form) :as)
                      nil)
                value (if (= key :as) (first value) value)]
            (when key
              [(if (= :type-first spelling)
                 (array-map :type type key value)
                 (array-map key value :type type))])))))))


(defn- original-leaf
  [form spelling type]
  (cond
    type (typed-origin form spelling type)
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
        :project (when (and (nil? spelling) (local-projection? form)) [{:as (first value)}])
        nil))))


(defn- valid-inline-configuration?
  [{:keys [bind configure] :as body}]
  (or (not (contains? body :configure))
      (and (map? configure)
           (every? (fn [[path spec]]
                     (and (vector? path) (= 1 (count path)) (contains? bind (first path))
                          (map? spec) (= #{:type} (set (keys spec)))
                          (configuration-type? (:type spec))))
                   configure))))


(defn- valid-inline-input?
  [{:keys [bind input] :as body}]
  (let [aliases (into #{} (keep #(when (local-projection? %) (first (second %)))) (vals bind))]
    (if (contains? body :input)
      (and (seq aliases) (map? input) (= aliases (set (keys input))))
      (empty? aliases))))


(defn- restore-inline-order
  [{:keys [legacy-order] :as body} original]
  (if (contains? body :legacy-order)
    (when (and (vector? legacy-order)
               (= (count original) (count legacy-order))
               (= (set (keys original)) (set legacy-order)))
      (apply array-map (mapcat (juxt identity original) legacy-order)))
    original))


(defn inline-origin
  "Recover the legacy body from direct v2 binds. :legacy-forms records
   spellings that explicit expressions or configuration would erase.
   The prefix and position of an inline use are supplied by its parent."
  [body]
  (when (inline-shape? body #{:parent :parents :bind :legacy-forms :legacy-order :input :configure})
    (if-not (contains? body :bind)
      (when-not (some #(contains? body %) [:legacy-forms :legacy-order :input :configure]) body)
      (let [{:keys [bind legacy-forms configure]} body]
        (when (and (fields? bind) (valid-inline-configuration? body) (valid-inline-input? body)
                   (or (not (contains? body :legacy-forms))
                       (and (map? legacy-forms)
                            (every? (set (keys bind)) (keys legacy-forms))
                            (every? #(or (#{:bare-value :ref-wrapper :type-first} %) (inline-type-position? %))
                                    (vals legacy-forms)))))
          (let [originals (mapv (fn [[k form]]
                                  (when-let [[original] (original-leaf form (get legacy-forms k)
                                                                       (get-in configure [[k] :type]))]
                                    [k original]))
                                bind)]
            (when (every? some? originals)
              (restore-inline-order body (replace-inline-fields body :bind :args (into (empty bind) originals))))))))))


(defn- migrated-asserted-leaf
  [raw [op _ :as expression]]
  (if (and (= :ref op) (inline-binding-body raw))
    [expression [:inline-type (count (take-while #(not= :type %) (keys raw)))]]
    (when-let [key (case op :value :value :ref :ref :project :as nil)]
      (when (and (map? raw) (= #{:type key} (set (keys raw))))
        [(if (= op :ref) [:ref (:ref raw)] expression)
         (when (= :type (ffirst raw)) :type-first)]))))


(defn- migrated-leaf
  [raw [op _ :as expression] assertion]
  (if assertion
    (migrated-asserted-leaf raw expression)
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
      :project (when (local-projection? expression) [expression nil])
      nil)))


(defn migrate-inline
  "Form a direct v2 body with the source argument order and spelling. The
   caller must compare the reconstructed body and independently check its UUID."
  [body {:keys [bind configure] :as proposed}]
  (if-not (contains? body :args)
    body
    (let [leaves (mapv (fn [[k raw]]
                         (when-let [[form spelling] (migrated-leaf raw (get bind k) (get configure [k]))]
                           [k form spelling]))
                       (:args body))]
      (when (every? some? leaves)
        (let [fields (into (empty (:args body)) (map (fn [[k form _]] [k form])) leaves)
              spellings (into (array-map) (keep (fn [[k _ spelling]] (when spelling [k spelling]))) leaves)
              migrated (cond-> (into (replace-inline-fields body :args :bind fields)
                                     (select-keys proposed [:input :configure]))
                         (seq spellings) (assoc :legacy-forms spellings))
              restored (inline-origin migrated)]
          (cond-> migrated
            (and restored (not= (seq (keys body)) (seq (keys restored))))
            (assoc :legacy-order (vec (keys body)))))))))


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
