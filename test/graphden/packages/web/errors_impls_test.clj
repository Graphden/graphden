(ns graphden.packages.web.errors-impls-test
  "Unit tests for the `web/errors` base-fn impls — the two boundaries
   over the ONE `:type` → HTTP mapping.

   `:error-http-status` is what every response-building fn-def binds
   its `:status` through, and it is fed a type that may have survived
   a JSONB round-trip as a STRING: if the string arm regressed, every
   family would silently answer 500. `:error-boundary-wrap` sits at
   the very top of the handler chain — it decides whether an uncaught
   throw reaches the client as its real 4xx with an actionable message
   or as an opaque 500 that also pages the `:http/server-error`
   alerter. The message-withholding half is a leak guard: an
   unwhitelisted exception's text may carry SQL or internal ids."
  (:require
    [cheshire.core :as json]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.test-infra.impls :as impls]))


(use-fixtures :once (impls/impls-fixture "web" "errors"))


(defn- call
  ([kw args] (call kw args nil))
  ([kw args ctx]
   ((impls/impl-of kw)
    (into {} (map (fn [[k v]] [k (delay v)])) args)
    ctx)))


(defn- status-of
  [t]
  (call :error-http-status {:error-type t}))


(deftest error-http-status-maps-keyword-and-string-forms-alike
  (testing "explicitly mapped types"
    (is (= 404 (status-of :not-found)))
    (is (= 409 (status-of :constraint-violation/fn-name-collision)))
    (is (= 403 (status-of :authz/forbidden)))
    (is (= 429 (status-of :execution/over-capacity))))

  (testing "a type with no explicit entry falls back by FAMILY"
    ;; `:sequence-op/*` is the case that shipped as a 500 — the client
    ;; never saw the message telling it how to fix its append body.
    (is (= 400 (status-of :sequence-op/invalid-body)))
    (is (= 400 (status-of :validation-error/anything-at-all)))
    (is (= 404 (status-of :branch-router/no-such-branch))))

  (testing "the STRING wire form maps identically to the keyword"
    ;; A `:type` that went through JSONB / a JSON body arrives as text.
    (is (= 404 (status-of "not-found")))
    (is (= 409 (status-of "merge-conflict")))
    (is (= 400 (status-of "validation-error/bad-field"))))

  (testing "unknown / nil → 500, the honest default"
    (is (= 500 (status-of :something/never-mapped)))
    (is (= 500 (status-of nil)))))


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
