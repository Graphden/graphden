(ns ^:serial graphden.crud.fn-execution.trace-test
  "The wire format of the cross-service trace header, the
   `*execution*`-driven header map, and which incoming headers may make a
   request a persisted hop. Pure; the persisted hop end to end is covered
   by `services.service-endpoint-e2e-test`.

   ^:serial — the hop probe `with-redefs` `run-traced-with!` and
   `sp/read-entity`."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.crud.fn-execution.trace :as trace]
    [graphden.executor.compile-runtime :as cr]
    [graphden.storage.protocol.core :as sp]))


(def ^:private t (random-uuid))
(def ^:private e (random-uuid))


(deftest header-round-trip-test
  (is (= (str t ";" e) (trace/format-header {:id e :trace-id t})))
  (is (= (str e ";" e) (trace/format-header {:id e})) "a top-level run is its own trace")
  (is (nil? (trace/format-header nil)))
  (is (= {:trace-id t :parent-execution-id e} (trace/parse-header (str t ";" e))))
  (is (= {:trace-id t :parent-execution-id e} (trace/parse-header (str " " t " ; " e " "))))
  (testing "malformed values are ignored, never an error"
    (is (nil? (trace/parse-header nil)))
    (is (nil? (trace/parse-header "")))
    (is (nil? (trace/parse-header "not-a-uuid;also-not")))
    (is (nil? (trace/parse-header (str t))))))


(deftest trace-headers-follow-the-bound-execution-test
  (is (= {} (trace/trace-headers)) "no persisted run → nothing to name")
  (binding [cr/*execution* {:id e :trace-id t}]
    (is (= {"X-Graphden-Trace" (str t ";" e)} (trace/trace-headers)))))


(deftest incoming-trace-reads-the-lower-cased-ring-header-test
  (is (= {:trace-id t :parent-execution-id e}
         (trace/incoming-trace {:headers {"x-graphden-trace" (str t ";" e)}})))
  (is (nil? (trace/incoming-trace {:headers {}}))))


(defn- storage-with-executions
  "A storage stand-in whose `:fn-execution` table holds exactly `ids`
   (read through the `sp/read-entity` redef in `traced?`)."
  [ids]
  {::executions ids})


(defn- traced?
  "Did `run-traced!` hand this request to `run-traced-with!` (the
   persisted-hop path) or just run it?"
  [ctx header]
  (let [seen (atom ::unset)]
    (with-redefs [trace/run-traced-with! (fn [_ _ _ _ thunk] (reset! seen :traced) (thunk))
                  sp/read-entity (fn [storage entity-type id]
                                   (when (and (= :fn-execution entity-type)
                                              (contains? (::executions storage) id))
                                     {:id id}))]
      (trace/run-traced! ctx (random-uuid) {:headers {"x-graphden-trace" header}}
                         #(when (= ::unset @seen) (reset! seen :plain))))
    (= :traced @seen)))


(deftest only-a-known-execution-makes-a-traced-hop-test
  ;; The header arrives on a tenant's PUBLIC :http-server: any outside
  ;; caller could send one and make every request a fully traced,
  ;; persisted execution (~7 writes, rows kept 7 days).
  (let [parent (random-uuid)
        root (random-uuid)]
    (testing "an unknown (made-up / other org's) execution id → the request just runs"
      (is (false? (traced? {:storage (storage-with-executions #{})}
                           (str (random-uuid) ";" (random-uuid))))))
    (testing "no storage to check against → not persisted"
      (is (false? (traced? {} (str root ";" parent)))))
    (testing "the caller's own execution row is visible → a linked hop"
      (is (true? (traced? {:storage (storage-with-executions #{parent})}
                          (str root ";" parent)))))
    (testing "a traced intermediate hop (row not yet written) links through the trace root"
      (is (true? (traced? {:storage (storage-with-executions #{root})}
                          (str root ";" parent)))))))
