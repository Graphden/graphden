;; POC: сравнить current executor vs ideal hand-written eager Clojure closure
;; на минимальной композиции. Цель: подтвердить что eager compile реально
;; даёт ~2x от Clojure, а не упрётся в что-то непредвиденное.
(require '[clojure.repl :refer [pst]])
(require '[graphden.executor.test-setup :as setup])
(require '[graphden.storage.protocol.postgres-test-helpers :as pth])
(require '[graphden.executor.interface :as exec])
(require '[graphden.storage.protocol.core :as sp])
(require '[graphden.executor.composition.interface :as fn-composition])
(require '[graphden.executor.compile-runtime :as cr])

(def ^:dynamic *container* nil)


(defn bench
  [label n f]
  (dotimes [_ 100] (f))           ; warmup
  (let [t0 (System/nanoTime)]
    (dotimes [_ n] (f))
    (let [ns-per-call (/ (- (System/nanoTime) t0) (double n))]
      (printf "%-40s %10.1f ns/call (n=%d)%n" label ns-per-call n) (flush))))


((pth/create-container-fixture #'*container*)
 (fn []
   (exec/with-clean-registry
     #(let [storage (setup/create-versioned-test-storage)
            graph (setup/bootstrap-crud-graph! storage)
            ctx (:ctx graph)
            ;; :neg takes one :number → -number. Compose :poc-neg as
            ;; thin pass-through; caller passes :number.
            _ (fn-composition/sync-fns-to-storage!
                storage
                [{:name :poc-neg :parent :neg :args {}}])
            _ (cr/rebuild! ctx)
            fn-id (:id (first (sp/query-entities storage :fn {:name "poc-neg"})))

            ;; A. Current executor — full pipeline (every call rebuilds env)
            current-exec (fn [] (exec/execute ctx fn-id {:number 5}))

            ;; B. Cached callable — registry lookup happens once
            current-callable (exec/make-single-arg-callable ctx fn-id)
            current-call (fn [] (current-callable 5))

            ;; C. Ideal eager compile — what target architecture WOULD produce:
            ;;    static knowledge of bindings lifted to closure capture,
            ;;    impl call invokes directly.
            neg-impl (fn [n] (- n))   ; the bound base-fn impl
            ideal-callable (fn [free-args] (neg-impl (get free-args :number)))
            ideal-call (fn [] (ideal-callable {:number 5}))

            ;; D. Pure Clojure naked invoke — physical floor.
            naked-fn (fn [n] (- n))
            naked-call (fn [] (naked-fn 5))]

        (println "Sanity:")
        (println "  current-exec    =>" (current-exec))
        (println "  current-callable=>" (current-call))
        (println "  ideal-callable  =>" (ideal-call))
        (println "  naked-fn        =>" (naked-call))
        (println)
        (println "Bench (lower = faster):")
        (bench "A. exec/execute (full pipeline)"   100000 current-exec)
        (bench "B. make-single-arg-callable+call" 1000000 current-call)
        (bench "C. ideal eager compile (closure)" 10000000 ideal-call)
        (bench "D. naked Clojure fn (baseline)"   10000000 naked-call)))))


(println "DONE")
