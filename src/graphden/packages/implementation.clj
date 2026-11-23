(ns graphden.packages.implementation
  "Evaluate a snapshotted package implementation under the shared namespace lock.")


;; Compiling a module = `read-string` then `eval`, both of which mutate
;; JVM-GLOBAL state: the reader's `*ns*`/data-reader tables, the namespace
;; registry, and the shared DynamicClassLoader `eval` defines classes into.
;; Two threads compiling different modules concurrently corrupt that shared
;; state — the observed symptom is a bogus reader error ("Map literal must
;; contain an even number of forms") thrown while another thread is mid-eval.
;; Production loads packages serially so it never trips; only the parallel
;; test harness (many registry builds at once) does, where it surfaced as a
;; gate flake. Serialize the compile step behind one lock. This is a
;; boot/registry-build path, run a bounded number of times, NOT a hot path —
;; the lock adds no steady-state cost.
(defonce ^:private eval-load-lock (Object.))


(defn evaluate
  [content reuse?]
  (locking eval-load-lock
    ;; Parse the ns form to extract the namespace name
    (let [forms (read-string (str "[" content "]"))
          ns-form (first forms)
          ns-sym (when (and (seq? ns-form) (= 'ns (first ns-form)))
                   (second ns-form))
          loaded-impls (when (and ns-sym reuse?)
                         (some-> (find-ns ns-sym) (ns-resolve 'impls)))]
      (cond
        ;; Coverage mode: the instrumented namespace is already here.
        loaded-impls @loaded-impls

        ns-sym
        (do
          ;; Create the namespace if it doesn't exist
          (create-ns ns-sym)

          ;; Evaluate all forms in the namespace context
          (binding [*ns* (the-ns ns-sym)]
            (doseq [form forms]
              (eval form)))

          ;; Return the impls var value
          (when-let [impls-var (ns-resolve ns-sym 'impls)]
            @impls-var))))))
