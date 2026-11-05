(ns graphden.packages.web.errors-impls-test
  "Graph tests for the top-level exception boundary: mapped author errors,
   redacted internal errors, retry headers and fatal Error passthrough."
  (:require
    [cheshire.core :as json]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.test-infra.exec-harness :as harness]))


(use-fixtures :once (harness/exec-fixture (str (ns-name *ns*))))


(defn- call
  [kw args]
  (exec/execute-by-name harness/*context* (name kw) args))


(deftest error-boundary-turns-a-throw-into-its-mapped-status
  (let [wrap (fn [handler] (call :error-boundary-wrap {:handler handler}))
        throwing (fn [t] (wrap (fn [_req] (throw t))))
        body-of (fn [resp] (json/parse-string (:body resp) true))]

    (testing "a handler that returns normally is untouched"
      (is (= {:status 200 :body "ok"}
             ((wrap (fn [_req] {:status 200 :body "ok"})) {:uri "/x"}))))

    (testing "a typed, author-facing throw keeps its status AND its message"
      ;; Losing the message here is what made sync errors unactionable.
      (let [resp ((throwing (ex-info "slot :nums is required"
                                     {:type :validation-error/missing-field}))
                  {})
            body (body-of resp)]
        (is (= 400 (:status resp)))
        (is (= "validation-error/missing-field" (:error body)))
        (is (= "slot :nums is required" (:message body)))
        (is (false? (:ok body)))
        (is (nil? (:ref body)) "a visible message needs no opaque reference")))

    (testing "a type OUTSIDE the author-facing families has its message WITHHELD"
      ;; The text may carry SQL / internal ids; the client gets a ref
      ;; that also lands in the server log.
      (let [resp ((throwing (ex-info "ERROR: relation \"fn_version\" does not exist"
                                     {:type :storage/query-failed}))
                  {})
            body (body-of resp)]
        (is (= 500 (:status resp)))
        (is (= "storage/query-failed" (:error body)))
        (is (= "Internal error — see server log." (:message body)))
        (is (string? (:ref body)) "the withheld message is correlated by ref")))

    (testing "an UNTYPED throw is a 500 whose body says nothing internal"
      (let [resp ((throwing (RuntimeException. "NullPointerException at row 4")) {})
            body (body-of resp)]
        (is (= 500 (:status resp)))
        (is (= "internal" (:error body)))
        (is (not= "NullPointerException at row 4" (:message body)))
        (is (string? (:ref body)))))

    (testing "a 429 carries Retry-After so the caller backs off"
      (let [resp ((throwing (ex-info "busy" {:type :execution/over-capacity})) {})]
        (is (= 429 (:status resp)))
        (is (= "1" (get (:headers resp) "Retry-After")))))

    (testing "every mapped answer is declared JSON"
      (let [resp ((throwing (ex-info "x" {:type :not-found})) {})]
        (is (= 404 (:status resp)))
        (is (= "application/json" (get (:headers resp) "Content-Type")))))

    (testing "a java.lang.Error is NOT swallowed — it must still crash the JVM"
      ;; Catching Throwable here would turn an OOM / linkage error into a
      ;; 500 and leave the process wedged in an unrecoverable state.
      (is (thrown? StackOverflowError
            ((throwing (StackOverflowError. "boom")) {}))))))
