(ns graphden.packages.web.http-client-test
  "The universal `http-request` primitive + its fns.edn ladder.

   Impl-level: a local http-kit echo server receives what the impl
   actually dials (platform path) — method on the wire, body, header
   stringification, Authorization injection from `:auth-value`, the
   invalid-method backstop — and the RESTRICTED path is asserted to
   egress-block an internal target before any connection.

   Ladder-level: the parsed package defs are asserted to carry the
   narrowing structure (`:standard-http-request` type-narrows `:method`,
   presets pin the literal) so a fns.edn edit can't silently flatten
   the ladder.

   Loads impls.clj dynamically (same pattern as `http_realize_body_test`)
   so private helpers stay private to production but reachable here."
  (:require
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing]]
    [graphden.executor.compile-runtime :as cr]
    [graphden.packages.loader :as loader]
    [org.httpkit.server :as server]))


(def ^:private client-impls-ns
  (let [impls-file (io/resource "packages/web/http-client/impls.clj")]
    (when impls-file
      (load-file (java.io.File/.getPath (io/file impls-file))))
    (find-ns 'graphden.packages.web.http-client.impls)))


(def ^:private http-request-base
  @(ns-resolve client-impls-ns 'http-request))


(defn- call!
  "Invoke the defbase impl with named args (platform path unless the
   caller binds `cr/*allowed-effects*`)."
  [args]
  (http-request-base (merge {:headers {} :body nil :auth-value nil :timeout-ms 2000}
                            args)
                     nil))


(defn- with-echo-server
  "Boot a local echo server; `f` gets the base url. The handler answers
   with a JSON dump of {method, uri, headers, body}."
  [f]
  (let [stop (server/run-server
               (fn [req]
                 {:status 200
                  :headers {"Content-Type" "application/json"}
                  :body (json/generate-string
                          {:method (name (:request-method req))
                           :uri (:uri req)
                           :headers (:headers req)
                           :body (some-> (:body req) slurp)})})
               {:port 0 :legacy-return-value? false})]
    (try
      (f (str "http://127.0.0.1:" (server/server-port stop)))
      (finally
        (server/server-stop! stop)))))


(defn- echo
  [resp]
  (json/parse-string (:body resp) true))


(deftest method-travels-to-the-wire-test
  (with-echo-server
    (fn [base]
      (testing "lower-case graph convention reaches the server as the verb"
        (doseq [m ["get" "post" "put" "delete" "patch"]]
          (is (= m (:method (echo (call! {:method m :url base}))))
              (str "method " m))))
      (testing "body travels on POST; GET carries none"
        (is (= "hello" (:body (echo (call! {:method "post" :url base :body "hello"})))))
        (let [got (:body (echo (call! {:method "get" :url base})))]
          (is (or (nil? got) (= "" got)))))
      (testing "nil body on a body-required method dials an empty body, not an error"
        (let [got (:body (echo (call! {:method "post" :url base})))]
          (is (or (nil? got) (= "" got))))))))


(deftest headers-and-auth-injection-test
  (with-echo-server
    (fn [base]
      (testing "keyword header keys are stringified at the boundary"
        (is (= "1" (get-in (echo (call! {:method "get" :url base
                                         :headers {:x-custom 1}}))
                           [:headers :x-custom]))))
      (testing ":auth-value lands as the Authorization header"
        (is (= "Bearer tok" (get-in (echo (call! {:method "get" :url base
                                                  :auth-value "Bearer tok"}))
                                    [:headers :authorization]))))
      (testing "auth wins over a colliding :headers entry"
        (is (= "Bearer tok"
               (get-in (echo (call! {:method "get" :url base
                                     :headers {"Authorization" "spoof"}
                                     :auth-value "Bearer tok"}))
                       [:headers :authorization])))))))


(deftest invalid-method-token-throws-test
  ;; The type ladder is the authoring-time guarantee; this is the runtime
  ;; backstop for /api/execute-supplied values. No server needed — the
  ;; validation throws before dialing.
  (doseq [bad ["GE T" "" "po/st" "get\nSmuggle: x"]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid HTTP method"
          (call! {:method bad :url "http://example.com/"}))
        (pr-str bad))))


(deftest restricted-path-blocks-internal-targets-test
  ;; A tenant execution (non-nil *allowed-effects*) must be egress-checked
  ;; BEFORE any connection — an internal/loopback URL dies as
  ;; :egress/blocked for every method, POST included.
  (doseq [m ["get" "post"]]
    (binding [cr/*allowed-effects* #{:network}]
      ;; ONE call, not two: the throw and its `:type` are the same fact, and a
      ;; second attempt only doubles the connect the guard is supposed to
      ;; prevent. `::no-throw` makes a silent return fail here rather than
      ;; skipping the assertions below.
      (let [ex (try (call! {:method m :url "http://127.0.0.1:9/"})
                    ::no-throw
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (not= ::no-throw ex)
            (str "method " m " must be blocked before it connects"))
        (is (= "egress" (some-> ex ex-data :type namespace))
            (str "method " m " → " (some-> ex ex-data)))))))


;; =============================================================================
;; The fns.edn ladder — structural assertions over the PARSED package.
;; =============================================================================

(def ^:private web-defs
  (delay
    (into {}
          (keep (fn [fd] (when (:name fd) [(:name fd) fd])))
          (:fn-defs (loader/load-packages ["web"])))))


(deftest ladder-structure-test
  (let [defs @web-defs]
    (testing "the narrowing rung: :standard-http-request type-narrows :method, no pin"
      (let [fd (get defs :standard-http-request)]
        (is (= :http-request (:parent fd)))
        (is (= {:type :http-method} (select-keys (get-in fd [:args :method]) [:type])))
        (is (nil? (get-in fd [:args :method :value])))))
    (testing "per-method presets pin the method literal (sweep validates
              it against :http-method's [:in] constraint)"
      (doseq [[fn-name m] {:http-get "get" :http-post "post" :http-put "put"
                           :http-delete "delete" :http-patch "patch"}]
        (let [fd (get defs fn-name)]
          (is (= :standard-http-request (:parent fd)) (str fn-name))
          (is (= m (get-in fd [:args :method])) (str fn-name)))))
    (testing "back-compat: :http-get-with-authorization pins GET + renames
              :headers on the ROOT (defaulted-slot renames resolve only at
              the slot owner's direct child)"
      (let [fd (get defs :http-get-with-authorization)]
        (is (= :http-request (:parent fd)))
        (is (= "get" (get-in fd [:args :method])))
        (is (= :extra-headers (get-in fd [:args :headers :as])))))))


;; =============================================================================
;; Helpers the dialed tests above cannot reach
;; =============================================================================
;; The echo-server tests cover the PLATFORM path end to end; these two helpers
;; belong to the RESTRICTED (OkHttp) path, which the suite deliberately never
;; dials — so their rules have to be asserted directly or not at all.

(defn- priv
  [sym]
  @(ns-resolve client-impls-ns sym))


(deftest okhttp-request-body-follows-the-method-test
  (let [f (priv 'okhttp-request-body)]
    (testing "a body is sent whatever the method"
      (is (some? (f "GET" "x")))
      (is (some? (f "POST" "x"))))
    (testing "POST / PUT / PATCH with no body send an EMPTY one — curl -X parity"
      ;; OkHttp refuses to build these without a body object, so a nil body
      ;; would be a 500 on a perfectly ordinary `:http-post` with no payload.
      (doseq [m ["POST" "PUT" "PATCH"]]
        (is (some? (f m nil)) (str m " must carry an empty body"))))
    (testing "GET / HEAD with no body send none — OkHttp rejects a body there"
      (is (nil? (f "GET" nil)))
      (is (nil? (f "HEAD" nil))))))


(deftest egress-blocked-cause-walks-the-chain-test
  (let [f (priv 'egress-blocked-cause)]
    (testing "an :egress/* ex-info is recognised at the top"
      (let [e (ex-info "blocked" {:type :egress/blocked})]
        (is (identical? e (f e)))))
    (testing "and through a wrapper — OkHttp wraps the resolver's throw"
      ;; Without the walk a DNS-rebind block surfaces as a generic
      ;; "unexpected end of stream", which reads like a flaky upstream.
      (let [inner (ex-info "blocked" {:type :egress/rebind})]
        (is (identical? inner (f (java.io.IOException. "eof" inner))))))
    (testing "an ordinary failure is not an egress block"
      (is (nil? (f (java.io.IOException. "connection reset"))))
      (is (nil? (f (ex-info "other" {:type :http/timeout}))))
      (is (nil? (f nil))))))


(deftest stringify-header-keys-handles-non-maps-test
  (let [f (priv 'stringify-header-keys)]
    (testing "a keywordized JSON header map is named for http-kit's writer"
      (is (= {"Authorization" "Bearer x" "X-N" "1"} (f {:Authorization "Bearer x" :X-N 1}))))
    (testing "a non-map is nil rather than a cast error at the boundary"
      (is (nil? (f nil)))
      (is (nil? (f "not a map")))
      (is (= {} (f {}))))))
