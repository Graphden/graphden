(ns graphden.packages.core.hof.impls
  "Implementations for core/hof base functions.

   Functions with :fn type args are handled specially by the loader.
   The loader wraps fn arguments as callables before passing them here.")


(defn map-fn
  [{:keys [f coll]}]
  (if coll
    (map f coll)
    (map f)))


(defn filter-fn
  [{:keys [pred coll]}]
  (if coll
    (filter pred coll)
    (filter pred)))


(defn reduce-fn
  [{:keys [f init coll]}]
  ;; reduce passes [acc item] as single vector to the function
  (reduce (fn [acc item] (f [acc item])) init coll))


(defn some-fn
  [{:keys [pred coll]}]
  (some (fn [item]
          (when-let [result (pred item)]
            result))
        coll))


(defn every?-fn
  [{:keys [pred coll]}]
  (every? pred coll))


(defn find-first
  [{:keys [pred coll]}]
  (some #(when (pred %) %) coll))


(defn group-by-fn
  [{:keys [key-fn coll]}]
  (group-by key-fn coll))


(defn sort-by-fn
  [{:keys [key-fn coll]}]
  (vec (sort-by key-fn coll)))


(defn apply-fn
  [{:keys [f args]}]
  (f args))


(defn constantly-fn
  [{:keys [x]}]
  x)


(defn comp-fn
  [{:keys [fns]}]
  (apply comp fns))


(defn transduce-fn
  [{:keys [xf rf init coll]}]
  (transduce xf
             (fn
               ([acc] acc)
               ([acc item] (rf [acc item])))
             init coll))


;; === Registry ===

(def impls
  {:map map-fn
   :filter filter-fn
   :reduce reduce-fn
   :some some-fn
   :every? every?-fn
   :find-first find-first
   :group-by group-by-fn
   :sort-by sort-by-fn
   :apply apply-fn
   :constantly constantly-fn
   :comp comp-fn
   :transduce transduce-fn})
