(ns graphden.system.branch-router-stale-test
  "A page load naming a branch that no longer exists must not answer 400.

   The 400 replaced the HTML document, so the editor never booted — no
   scripts, no explanation, nothing to click; a user whose branch was
   merged-and-deleted in another tab was simply stuck (found 2026-08-20
   walking the tutorial guard). Navigations now redirect to the same URL
   without the stale `?branch=`; API/XHR callers keep the 400, which is
   what they can act on."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.system.branch-router :as br]))


(deftest document-navigation?-test
  (testing "a browser page load is a navigation"
    (is (true? (br/document-navigation?
                 {:request-method :get
                  :headers {"accept" "text/html,application/xhtml+xml"}}))))

  (testing "editor fetch / API callers are not"
    (is (false? (br/document-navigation?
                  {:request-method :get :headers {"accept" "application/json"}})))
    (is (false? (br/document-navigation?
                  {:request-method :get :headers {"accept" "*/*"}})))
    (is (false? (br/document-navigation?
                  {:request-method :post
                   :headers {"accept" "text/html"}}))
        "a POST is never a navigation we can safely redirect")
    (is (false? (br/document-navigation?
                  {:request-method :get
                   :headers {"accept" "text/html" "hx-request" "true"}}))
        "htmx asks for HTML but is an XHR — it must see the error")))


(deftest uri-without-branch-test
  (testing "the branch param is dropped, everything else survives"
    (is (= "/" (br/uri-without-branch {:uri "/" :query-string "branch=gone"})))
    (is (= "/?demo=1&tutorial=01"
           (br/uri-without-branch {:uri "/"
                                   :query-string "demo=1&branch=gone&tutorial=01"})))
    (is (= "/editor" (br/uri-without-branch {:uri "/editor" :query-string nil})))
    (is (= "/editor?x=1"
           (br/uri-without-branch {:uri "/editor" :query-string "x=1"}))))

  (testing "a bare `branch` key (no value) is dropped too"
    (is (= "/" (br/uri-without-branch {:uri "/" :query-string "branch"})))))
