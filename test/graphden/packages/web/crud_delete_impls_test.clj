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


(deftest query-param-reads-both-request-shapes
  (testing "reitit's enriched :query-params wins, string OR keyword keyed"
    (is (= "1" (call :query-param {:request {:query-params {"force" "1"}}
                                   :param-name "force"})))
    (is (= "1" (call :query-param {:request {:query-params {:force "1"}}
                                   :param-name "force"}))))

  (testing "a raw http-kit request falls back to :query-string, URL-DECODED"
    ;; The branch-routing passthrough never goes through enrich-request;
    ;; without the fallback every `?force=1` delete silently ignored the flag.
    (is (= "a b" (call :query-param {:request {:query-string "force=a%20b"}
                                     :param-name "force"}))))

  (testing "a malformed percent-escape fails soft instead of 500-ing"
    (is (= "a%" (call :query-param {:request {:query-string "force=a%"}
                                    :param-name "force"}))))

  (testing "absent parameter → nil (not \"\" — the caller tests presence)"
    (is (nil? (call :query-param {:request {:query-string "other=1"}
                                  :param-name "force"})))
    (is (nil? (call :query-param {:request {} :param-name "force"})))))


(deftest extract-entity-params-prefers-path-params-then-parses-the-uri
  ;; ctx has no :storage, so `schema-entity-types` answers nil and only
  ;; the CORE entity types resolve — the documented degraded mode.
  (testing "reitit's :path-params win when present"
    (is (= {:type-str "fn" :id-str "abc" :entity-type :fn}
           (call :extract-entity-params
                 {:request {:path-params {:type "fn" :id "abc"}
                            :uri "/api/entities/slot/other"}}))))

  (testing "no :path-params → the URI segments are parsed instead"
    ;; This is the http-kit passthrough path; dropping it makes every
    ;; delete reached through :branch-routing-wrap a 400.
    (is (= {:type-str "binding" :id-str "xyz" :entity-type :binding}
           (call :extract-entity-params
                 {:request {:uri "/api/entities/binding/xyz"}}))))

  (testing "a collection URI yields a type with no id"
    (let [r (call :extract-entity-params {:request {:uri "/api/entities/slot"}})]
      (is (= "slot" (:type-str r)))
      (is (nil? (:id-str r)))
      (is (= :slot (:entity-type r)))))

  (testing "an UNKNOWN segment keeps :type-str but resolves :entity-type to nil"
    ;; The handler's 400 hangs off :entity-type being nil; if the segment
    ;; were coerced to a keyword blindly, an arbitrary URL segment would
    ;; reach storage as an entity-type.
    (let [r (call :extract-entity-params {:request {:uri "/api/entities/wat/1"}})]
      (is (= "wat" (:type-str r)))
      (is (nil? (:entity-type r)))))

  (testing "an unrecognised URI shape → all nil, never a throw"
    (is (= {:type-str nil :id-str nil :entity-type nil}
           (call :extract-entity-params {:request {:uri "/"}})))
    (is (= {:type-str nil :id-str nil :entity-type nil}
           (call :extract-entity-params {:request {}})))))
