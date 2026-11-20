(ns graphden.input.format
  "Version boundary shared by the offline reader and the legacy loader.")


(defn local-projection?
  [form]
  (and (vector? form) (= 2 (count form)) (= :project (first form))
       (vector? (second form)) (= 1 (count (second form))) (simple-keyword? (first (second form)))))


(defn inline-leaf?
  "A single-parent computation with no authored fields, bindings or annotations."
  [form]
  (and (map? form) (= #{:parent} (set (keys form))) (keyword? (:parent form))))


(defn- scalar?
  [x]
  (or (nil? x) (boolean? x) (number? x) (string? x) (keyword? x)))


(defn- single-field?
  [fields]
  (and (map? fields) (= 1 (count fields)) (simple-keyword? (ffirst fields))))


(defn legacy-inline?
  "Direct use-sites whose source spelling can be reconstructed from v2."
  [body]
  (or (inline-leaf? body)
      (and (map? body) (= #{:parent :args} (set (keys body))) (keyword? (:parent body))
           (single-field? (:args body))
           (let [v (val (first (:args body)))]
             (or (scalar? v) (and (map? v) (= #{:value} (set (keys v)))))))))


(defn inline-origin
  "Recover the original anonymous body for use-site identity. Explicit v2
   values preserve v1 value wrappers; bare scalar values preserve their bare
   spelling. An explicit ref here came from a bare graph name. V1 ref wrappers
   would lose that distinction and are outside this reversible subset."
  [body]
  (cond
    (inline-leaf? body) body
    (and (map? body) (= #{:parent :bind} (set (keys body))) (keyword? (:parent body))
         (single-field? (:bind body)))
    (let [[field form] (first (:bind body))
          leaf (fn [v] {:parent (:parent body) :args {field v}})]
      (cond
        (scalar? form) (leaf form)
        (and (vector? form) (= 2 (count form)))
        (case (first form)
          :value (leaf {:value (second form)})
          :ref (when (keyword? (second form)) (leaf (second form)))
          nil)))))


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
