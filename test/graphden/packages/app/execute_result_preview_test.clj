(ns graphden.packages.app.execute-result-preview-test
  "The execute-popover result body's HTML-response branch — a
   Ring-shaped `{:status <int> :body \"<html…\"}` result renders as a
   sandboxed iframe preview instead of a JSON record dump; near-miss
   shapes keep their old panes. Runs `:_er-succeeded-body` over the
   real synced app.execution graph (golden clone)."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.test-infra.exec-harness :as harness]))


(use-fixtures :once (harness/exec-fixture (str (ns-name *ns*))))


(defn- in-tree?
  [form x]
  (boolean (some #(= x %) (tree-seq coll? seq form))))


(defn- succeeded-body
  [result]
  (exec/execute-by-name harness/*context* "_er-succeeded-body"
                        {:exec {:status "succeeded" :result result}}))


(deftest html-response-preview-test
  (testing "a Ring HTML response renders the sandboxed iframe pane"
    (let [f (succeeded-body {:status 200
                             :headers {:Content-Type "text/html"}
                             :body "<html><body>hi</body></html>"})]
      (is (in-tree? f :iframe))
      (is (in-tree? f "allow-scripts") "sandboxed — no same-origin")
      (is (in-tree? f "<html><body>hi</body></html>")
          "the body travels as the iframe's srcdoc")
      (is (in-tree? f "HTML response — status 200"))))

  (testing "a record with a non-HTML :body keeps the record pane"
    (let [f (succeeded-body {:status 200 :body "plain text"})]
      (is (not (in-tree? f :iframe)))))

  (testing "a record with an HTML-ish :body but no int :status keeps the record pane"
    (let [f (succeeded-body {:body "<b>x</b>"})]
      (is (not (in-tree? f :iframe)))))

  (testing "a plain scalar keeps the scalar pane"
    (let [f (succeeded-body "hello")]
      (is (not (in-tree? f :iframe))))))
