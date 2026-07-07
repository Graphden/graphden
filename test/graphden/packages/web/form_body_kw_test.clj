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


(defn- form-req
  [body]
  {:body body
   :headers {"content-type" "application/x-www-form-urlencoded"}})


(deftest parse-form-body-kw-produces-keyword-keys-test
  (testing "urlencoded body → KEYWORD-keyed map (so `:get {:key {:value :field}}` reads it)"
    (is (= {:subject "alice" :capability "read"}
           (exec-name :parse-form-body-kw
                      {:request (form-req "subject=alice&capability=read")}))))
  (testing "the raw string variant yields STRING keys — the contrast that motivates -kw"
    (is (= {"subject" "alice" "capability" "read"}
           (exec-name :parse-form-body
                      {:request (form-req "subject=alice&capability=read")}))))
  (testing "wrong content-type → {} (guard #2), regardless of body"
    (is (= {}
           (exec-name :parse-form-body-kw
                      {:request {:body "subject=alice"
                                 :headers {"content-type" "text/plain"}}}))))
  (testing "missing body → {}"
    (is (= {}
           (exec-name :parse-form-body-kw {:request {:headers {}}})))))
