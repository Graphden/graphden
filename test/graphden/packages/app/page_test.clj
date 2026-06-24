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
                           :head []
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
                                    :head []
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
                            :head []
                            :scripts []})
          [path methods] (vec entry)]
      (is (= "/about" path))
      (is (contains? methods "get"))
      (is (fn? (get-in methods ["get" "handler"]))))))


;; =============================================================================
;; :graphden-runtime-scripts — drop-in for pages that need dispatch
;; =============================================================================

(deftest graphden-runtime-scripts-bundles-two-tags-test
  (testing "runtime bundle src tag first (loads bindActionDispatch), bootstrap second (calls it); src is hash-busted via ?v="
    (let [scripts (exec-name :graphden-runtime-scripts {})
          first-tag (first scripts)]
      (is (= 2 (count scripts)))
      (is (= :script (first first-tag)) "src tag is :script")
      (is (str/starts-with? (get-in first-tag [1 :src]) "/assets/graphden-runtime.js?v=")
          "src tag URL hash-busted (so immutable-cached bundles invalidate on each deploy)")
      (is (str/includes? (str (second scripts)) "bindActionDispatch")
          "second tag wires the dispatcher"))))


(deftest html-page-route-with-runtime-scripts-includes-both-script-tags-test
  (testing "binding :scripts :graphden-runtime-scripts lands both <script> tags in the rendered HTML"
    (let [route (exec-name :html-page-route
                           {:path "/x"
                            :title "X"
                            :page-body [:div "hi"]
                            :head []
                            :scripts (exec-name :graphden-runtime-scripts {})})
          handler (get-in (second route) ["get" "handler"])
          html (:body (handler {:request-method :get :uri "/x" :headers {}}))]
      (is (re-find #"<script src=\"/assets/graphden-runtime\.js\?v=[0-9a-f]+\">" html)
          "runtime bundle src tag present in <body> (with ?v= hash-bust)")
      (is (str/includes? html "bindActionDispatch")
          "bootstrap script present in <body>"))))


;; =============================================================================
;; :head slot wiring — :graphden-page-head bundles the components stylesheet
;; =============================================================================

(deftest graphden-page-head-bundles-stylesheet-link-test
  (testing "the default :head bundle contains the components-css <link> tag (hash-busted)"
    (let [head (exec-name :graphden-page-head {})]
      (is (= 1 (count head))
          "currently exactly one <link>")
      (let [[tag attrs] (vec (first head))]
        (is (= :link tag))
        (is (= "stylesheet" (:rel attrs)))
        (is (re-matches #"/assets/graphden-components\.css\?v=[0-9a-f]+"
                        (:href attrs))
            "href is hash-busted")))))


(deftest html-page-route-with-page-head-includes-stylesheet-link-test
  (testing "binding :head :graphden-page-head lands the <link rel=stylesheet> in <head>"
    (let [route (exec-name :html-page-route
                           {:path "/y"
                            :title "Y"
                            :page-body [:div "hi"]
                            :head (exec-name :graphden-page-head {})
                            :scripts []})
          handler (get-in (second route) ["get" "handler"])
          html (:body (handler {:request-method :get :uri "/y" :headers {}}))]
      (is (re-find #"<head>.*<link href=\"/assets/graphden-components\.css\?v=[0-9a-f]+\" rel=\"stylesheet\""
                   html)
          "stylesheet link lands in <head> (with ?v= hash-bust)"))))
