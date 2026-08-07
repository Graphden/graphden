(ns ^:integration graphden.packages.web.form-body-kw-test
  "Regression test for `:parse-form-body-kw` — the KEYWORD-keyed form-body
   parser every form-POST handler that reads fields with keyword keys
   (`:get {:key {:value :field}}`) depends on.

   Why this exists: the raw `:parse-form-body` yields STRING keys. A handler
   reading `:subject` (keyword) against a string-keyed body silently falls to
   the default and EVERY field collapses to \"\" — the bug that made the whole
   tenancy control plane's form routes non-functional. Handler-impl unit tests
   (which pass keyword-keyed Clojure maps directly) never exercise this seam;
   this test does."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.storage.protocol.postgres-test-helpers :as pth]
    [graphden.test-infra.graph-harness :as gh]))


(def ^:dynamic *container* nil)


(use-fixtures :once
  (pth/create-container-fixture #'*container*)
  (gh/graph-fixture (str (ns-name *ns*))))


(defn- form-req
  [body]
  {:body body
   :headers {"content-type" "application/x-www-form-urlencoded"}})


(deftest parse-form-body-kw-produces-keyword-keys-test
  (testing "urlencoded body → KEYWORD-keyed map (so `:get {:key {:value :field}}` reads it)"
    (is (= {:subject "alice" :capability "read"}
           (gh/exec-name :parse-form-body-kw
                         {:request (form-req "subject=alice&capability=read")}))))
  (testing "the raw string variant yields STRING keys — the contrast that motivates -kw"
    (is (= {"subject" "alice" "capability" "read"}
           (gh/exec-name :parse-form-body
                         {:request (form-req "subject=alice&capability=read")}))))
  (testing "wrong content-type → {} (guard #2), regardless of body"
    (is (= {}
           (gh/exec-name :parse-form-body-kw
                         {:request {:body "subject=alice"
                                    :headers {"content-type" "text/plain"}}}))))
  (testing "missing body → {}"
    (is (= {}
           (gh/exec-name :parse-form-body-kw {:request {:headers {}}})))))
