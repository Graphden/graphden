(ns ^:integration graphden.packages.web.crud-parse-flags-test
  "The two fn-row FLAG fragments of `:parse-fn-from-form` the editor's
   card strips write through — `:lambda-params` (the λ chip) and
   `:branch-local?` (the 📍 strip). Both are pure composed fn-defs over
   the parsed form-data map; this executes them through the golden
   registry so the three-state wire (`\"\"` / `\"[]\"` / CSV, and
   `\"true\"` / `\"false\"`) is pinned where a drift would otherwise
   surface as a card that cannot clear its declaration."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.test-setup :as setup]
    [graphden.test-infra.golden-app :as ga]))


(use-fixtures :once (ga/fixture (ns-name *ns*)))


(defn- parse
  "Run the named fragment over `form-data`."
  [fragment form-data]
  (let [{:keys [ctx storage]} ga/*bootstrap*]
    (setup/exec-with-storage ctx storage (ga/fn-id fragment) {:form-data form-data})))


(deftest lambda-params-fragment
  (testing "absent → {} — a PUT that does not mention the field leaves it alone"
    (is (= {} (parse :_parse-fn-form-lambda-params-fragment {:name "x"}))))
  (testing "blank or \"null\" → nil — back to derived"
    (is (= {:lambda-params nil} (parse :_parse-fn-form-lambda-params-fragment {:lambda-params ""})))
    (is (= {:lambda-params nil} (parse :_parse-fn-form-lambda-params-fragment {:lambda-params "null"}))))
  (testing "\"[]\" → [] — everything captured, no per-call input"
    (is (= {:lambda-params []} (parse :_parse-fn-form-lambda-params-fragment {:lambda-params "[]"}))))
  (testing "CSV → the ordered vector, trimmed, colons stripped, stored as strings"
    (is (= {:lambda-params ["request" "limit"]}
           (parse :_parse-fn-form-lambda-params-fragment {:lambda-params " :request, limit ,"})))))


(deftest branch-local-fragment
  (testing "absent → {}"
    (is (= {} (parse :_parse-fn-form-branch-local-fragment {:name "x"}))))
  (testing "\"true\" → true; anything else → false"
    (is (= {:branch-local? true} (parse :_parse-fn-form-branch-local-fragment {:branch-local "true"})))
    (is (= {:branch-local? false} (parse :_parse-fn-form-branch-local-fragment {:branch-local "false"})))
    (is (= {:branch-local? false} (parse :_parse-fn-form-branch-local-fragment {:branch-local ""})))))
