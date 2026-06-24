(ns ^:integration graphden.packages.app.page-test
  "Unit tests for the `app.page` HTML-page templates +
   `:graphden-runtime-scripts` drop-in (lives in app.editor)."
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
;; Page templates — :html-page-rendered / :html-page-handler / :html-page-route
;; =============================================================================

(deftest html-page-rendered-returns-text-test
  (testing ":html-page-rendered returns an HTML text string with the page body inside <body>"
    (let [html (exec-name :html-page-rendered
                          {:title "Test"
                           :page-body [:div "Hello"]
                           :scripts []})]
      (is (string? html))
      (is (str/includes? html "<title>Test</title>"))
      (is (str/includes? html "<div>Hello</div>")))))


(deftest html-page-handler-returns-ring-response-test
  (testing ":html-page-handler returns a Ring response — 200, text/html, body wraps the hiccup"
    (let [handler-route (exec-name :html-page-route
                                   {:path "/x"
                                    :title "X"
                                    :page-body [:p "ok"]
                                    :scripts []})
          handler (get-in (second handler-route) ["get" "handler"])
          response (handler {:request-method :get :uri "/x" :headers {}})]
      (is (= 200 (:status response)))
      (is (str/starts-with? (or (get-in response [:headers :Content-Type]) "")
                            "text/html"))
      (is (str/includes? (:body response) "<p>ok</p>")
          ":page-body slot renders into <body>"))))


(deftest html-page-route-yields-reitit-entry-test
  (testing ":html-page-route returns the [<path> {<method> {<handler>}}] tuple reitit consumes"
    (let [entry (exec-name :html-page-route
                           {:path "/about"
                            :title "About"
                            :page-body [:div "about"]
                            :scripts []})
          [path methods] (vec entry)]
      (is (= "/about" path))
      (is (contains? methods "get"))
      (is (fn? (get-in methods ["get" "handler"]))))))


;; =============================================================================
;; :graphden-runtime-scripts — drop-in for pages that need dispatch
;; =============================================================================

(deftest graphden-runtime-scripts-bundles-two-tags-test
  (testing "runtime bundle src tag first (loads bindActionDispatch), bootstrap second (calls it)"
    (let [scripts (exec-name :graphden-runtime-scripts {})]
      (is (= 2 (count scripts)))
      (is (= [:script {:src "/assets/graphden-runtime.js"}]
             (first scripts))
          "src tag must come first — bootstrap depends on it")
      (is (str/includes? (str (second scripts)) "bindActionDispatch")
          "second tag wires the dispatcher"))))


(deftest html-page-route-with-runtime-scripts-includes-both-script-tags-test
  (testing "binding :scripts :graphden-runtime-scripts lands both <script> tags in the rendered HTML"
    (let [route (exec-name :html-page-route
                           {:path "/x"
                            :title "X"
                            :page-body [:div "hi"]
                            :scripts (exec-name :graphden-runtime-scripts {})})
          handler (get-in (second route) ["get" "handler"])
          html (:body (handler {:request-method :get :uri "/x" :headers {}}))]
      (is (str/includes? html "<script src=\"/assets/graphden-runtime.js\">")
          "runtime bundle src tag present in <body>")
      (is (str/includes? html "bindActionDispatch")
          "bootstrap script present in <body>"))))
