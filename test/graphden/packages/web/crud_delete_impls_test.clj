(ns graphden.packages.web.crud-delete-impls-test
  "Unit tests for the `web/crud-delete` base-fn impls — the two
   primitives that turn a Ring request into the `(entity-type, id)`
   pair the delete chain acts on.

   Both exist because the same handler is reached two ways: through
   reitit (enriched `:path-params` / `:query-params`) AND raw from
   http-kit via `:branch-routing-wrap` (only `:uri` / `:query-string`).
   If either source stops being read, a shipped button goes dead with
   a 400 — the exact regression the addon entity-type lookup was added
   for."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.test-infra.impls :as impls]))


(use-fixtures :once (impls/impls-fixture "web" "crud-delete"))


(defn- call
  ([kw args] (call kw args nil))
  ([kw args ctx]
   ((impls/impl-of kw)
    (into {} (map (fn [[k v]] [k (delay v)])) args)
    ctx)))


(deftest query-param-raw-decodes-a-soft-fallback
  (testing "raw query values are URL-decoded"
    (is (= "a b" (call :_query-param-raw {:query-string "force=a%20b"
                                          :param-name "force"}))))
  (testing "malformed escapes stay raw"
    (is (= "a%" (call :_query-param-raw {:query-string "force=a%"
                                         :param-name "force"}))))
  (testing "absent parameters return nil"
    (is (nil? (call :_query-param-raw {:query-string "other=1"
                                       :param-name "force"})))
    (is (nil? (call :_query-param-raw {:query-string nil
                                       :param-name "force"})))))


(deftest entity-route-primitives-keep-uri-and-schema-boundaries
  (is (= {:type-str "binding" :id-str "xyz"}
         (call :_entity-uri-params {:uri "/api/entities/binding/xyz"})))
  (is (nil? (call :_entity-uri-params {:uri nil})))
  (is (= :binding (call :_entity-type-from-string {:type-str "binding"})))
  (is (nil? (call :_entity-type-from-string {:type-str "unknown"}))))
