(ns ^:integration graphden.packages.app.user-site-test
  "Unit tests for the `app.user-site` package — Block 4 of the
   user-sites plan (docs/USER_SITES_PLAN.md). Templates for
   building a site alongside graphden's editor."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.postgres-test-helpers :as pth]))


(def ^:dynamic *container* nil)
(def ^:dynamic *bootstrap* nil)


(use-fixtures :once
  (pth/create-container-fixture #'*container*)
  exec/with-clean-registry
  (fn [f]
    (binding [*bootstrap* (setup/bootstrap-crud-graph-from-golden!)]
      (f))))


(defn- exec-name
  [nm args]
  (let [{:keys [ctx storage all-name->id]} *bootstrap*
        fn-id (get all-name->id nm)]
    (when-not fn-id
      (throw (ex-info (str "No fn-id for " nm) {:nm nm})))
    (setup/exec-with-storage ctx storage fn-id args)))


;; =============================================================================
;; Runtime scripts — drop-in for user pages
;; =============================================================================

(deftest graphden-runtime-script-tag-test
  (testing "renders `<script src=\"/assets/graphden-runtime.js\">`"
    (is (= [:script {:src "/assets/graphden-runtime.js"}]
           (exec-name :graphden-runtime-script-tag {})))))


(deftest user-bootstrap-script-test
  (testing "renders an inline `<script>` calling bindActionDispatch on DOMContentLoaded"
    (let [tag (exec-name :user-bootstrap-script {})]
      (is (= :script (first tag))
          "tag is :script")
      (is (= {} (second tag))
          "no attrs (inline body)")
      (is (str/includes? (nth tag 2) "bindActionDispatch")
          "body wires the dispatcher")
      (is (str/includes? (nth tag 2) "DOMContentLoaded")
          "body waits for DOMContentLoaded"))))


(deftest user-runtime-scripts-bundles-both-test
  (testing "two-element list: src tag first, then bootstrap"
    (let [scripts (exec-name :user-runtime-scripts {})]
      (is (= 2 (count scripts)))
      (is (= [:script {:src "/assets/graphden-runtime.js"}]
             (first scripts))
          "runtime bundle src tag is first — must load before the bootstrap script runs")
      (is (str/includes? (str (second scripts)) "bindActionDispatch")
          "bootstrap script second"))))


;; =============================================================================
;; :user-page-route — full route entry
;; =============================================================================

(deftest user-page-route-renders-full-route-entry-test
  (testing "yields a reitit-shaped `[<path> {<method> {<handler>}}]` entry"
    (let [entry (exec-name :user-page-route
                           {:path "/about"
                            :title "About"
                            :page-body [:div "hi"]
                            :scripts []})
          [path method-map] (vec entry)]
      (is (= "/about" path))
      (is (map? method-map) "method-data is a map")
      (is (contains? method-map "get")
          "GET method registered")
      (is (fn? (get-in method-map ["get" "handler"]))
          "handler is a Ring function"))))


(deftest user-page-route-handler-returns-html-test
  (testing "executing the handler renders the body into a 200 + text/html response"
    (let [entry (exec-name :user-page-route
                           {:path "/test"
                            :title "Test page"
                            :page-body [:div "Hello world"]
                            :scripts []})
          handler (get-in (second entry) ["get" "handler"])
          response (handler {:request-method :get :uri "/test" :headers {}})]
      (is (= 200 (:status response))
          "200 OK")
      (is (str/starts-with? (or (get-in response [:headers :Content-Type]) "")
                            "text/html")
          (str "Content-Type is text/html — got " (pr-str (:headers response))))
      (is (str/includes? (:body response) "<title>Test page</title>")
          ":title slot renders into <head>")
      (is (str/includes? (:body response) "<div>Hello world</div>")
          ":body slot renders into <body>"))))


;; =============================================================================
;; :user-site-routes — mount-point starts empty
;; =============================================================================

(deftest user-site-routes-defaults-to-empty-test
  (testing "default :items is empty so the mount-point is a no-op until the user appends their routes"
    (is (= () (exec-name :user-site-routes {})))))
