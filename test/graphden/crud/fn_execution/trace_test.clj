(ns graphden.crud.fn-execution.trace-test
  "The wire format of the cross-service trace header and the
   `*execution*`-driven header map. Pure; the persisted hop is covered by
   `services.service-endpoint-e2e-test`."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.crud.fn-execution.trace :as trace]
    [graphden.executor.compile-runtime :as cr]))


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
