(ns graphden.packages.web.http-client-test
  "The universal `http-request` primitive + its fns.edn ladder.

   Impl-level: a local http-kit echo server receives what the impl
   actually dials (platform path) — method on the wire, body, header
   stringification, Authorization injection from `:auth-value`, the
   invalid-method backstop — and the RESTRICTED path is asserted to
   egress-block an internal target before any connection.

   Ladder-level: the parsed package defs are asserted to carry the
   narrowing structure (`:standard-http-request` type-narrows `:method`,
   presets pin it; `:http-get` also pins `:body` nil) so a fns.edn edit
   can't silently flatten the ladder.

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
      (is (thrown? clojure.lang.ExceptionInfo
            (call! {:method m :url "http://127.0.0.1:9/"})))
      (try
        (call! {:method m :url "http://127.0.0.1:9/"})
        (catch clojure.lang.ExceptionInfo e
          (is (= "egress" (some-> (ex-data e) :type namespace))
              (str "method " m " → " (ex-data e))))))))


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
    (testing "per-method presets pin the method literal"
      (doseq [[fn-name m] {:http-get "get" :http-post "post" :http-put "put"
                           :http-delete "delete" :http-patch "patch"}]
        (let [fd (get defs fn-name)]
          (is (= :standard-http-request (:parent fd)) (str fn-name))
          (is (= m (get-in fd [:args :method])) (str fn-name)))))
    (testing ":http-get also pins body to literal nil (method↔body dependency)"
      (is (= {:value nil} (get-in (get defs :http-get) [:args :body]))))
    (testing "back-compat: :http-get-with-authorization is a pure rename preset over :http-get"
      (let [fd (get defs :http-get-with-authorization)]
        (is (= :http-get (:parent fd)))
        (is (= :extra-headers (get-in fd [:args :headers :as])))))))
