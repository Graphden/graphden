(ns graphden.packages.web.crud-write-impls-test
  "Unit tests for the `web/crud-write` base-fn impls.

   `:chain-has-process-effect?` is service-registration guard 6: it is
   the LENIENT layer that lets a composed fn-def whose OWN rich-type
   entry is missing (a failed type-check on first sync) still register
   as a service because an ancestor declared `:process`. Too strict and
   a legitimate service silently refuses to start; too loose and any fn
   can be registered as a long-running process. The walk also carries a
   `seen` set — a parent cycle must terminate, not hang the write.

   The other two are Stage-2 validators wired into `:cond` chains: both
   MUST answer nil (no rejection) for absent entity-data without
   touching storage, because the chain calls them on arms where the
   body carried no row."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.registry.core :as registry]
    [graphden.test-infra.impls :as impls]
    [graphden.test-infra.storage-double :as double]))


(use-fixtures :once (impls/impls-fixture "web" "crud-write"))


(defn- call
  ([kw args] (call kw args nil))
  ([kw args ctx]
   ((impls/impl-of kw)
    (into {} (map (fn [[k v]] [k (delay v)])) args)
    ctx)))


(deftest chain-has-process-effect?-is-the-lenient-ancestor-walk
  (let [child (random-uuid)
        parent (random-uuid)
        grandparent (random-uuid)
        rows {child {:id child :parent-ids [parent]}
              parent {:id parent :parent-ids [grandparent]}
              grandparent {:id grandparent :parent-ids []}}
        ctx {:storage (double/rows-storage {:fn rows})}
        with-effects (fn [id->effects body]
                       (binding [registry/*rich-types-override*
                                 (atom {:by-id (into {} (map (fn [[k v]] [k {:effects v}]))
                                                     id->effects)
                                        :by-name {}})]
                         (body)))]

    (testing "nil fn-id → false, and storage is never consulted"
      ;; The guard runs on every service write, including ones whose
      ;; target fn-id failed to parse.
      (is (false? (call :chain-has-process-effect? {:fn-id nil} nil))))

    (testing "the fn's OWN declared :process qualifies it"
      (is (true? (with-effects {child #{:process}}
                   #(call :chain-has-process-effect? {:fn-id child} ctx)))))

    (testing "an ANCESTOR's :process qualifies a child with NO entry of its own"
      ;; This is the whole point of the chain walk — a fn-def whose
      ;; rich-type lookup missed must not lose its service eligibility.
      (is (true? (with-effects {grandparent #{:process}}
                   #(call :chain-has-process-effect? {:fn-id child} ctx)))))

    (testing "an unrelated effect anywhere on the chain does NOT qualify it"
      (is (false? (with-effects {child #{:db} parent #{:network} grandparent #{:io}}
                    #(call :chain-has-process-effect? {:fn-id child} ctx)))))

    (testing "an empty registry → false (no entry is not a yes)"
      (is (false? (with-effects {} #(call :chain-has-process-effect? {:fn-id child} ctx)))))

    (testing "the walk answers true from a DESCENDANT, not the other way round"
      ;; :process on the child says nothing about the grandparent.
      (is (false? (with-effects {child #{:process}}
                    #(call :chain-has-process-effect? {:fn-id grandparent} ctx)))))

    (testing "a parent CYCLE terminates instead of looping forever"
      (let [a (random-uuid)
            b (random-uuid)
            cyclic {:storage (double/rows-storage
                               {:fn {a {:id a :parent-ids [b]}
                                     b {:id b :parent-ids [a]}}})}]
        (is (false? (with-effects {} #(call :chain-has-process-effect? {:fn-id a} cyclic))))))

    (testing "an id with no row at all → false, no throw"
      (is (false? (with-effects {} #(call :chain-has-process-effect?
                                          {:fn-id (random-uuid)} ctx)))))))


(deftest stage-2-validators-skip-absent-entity-data
  ;; A nil ctx proves the point: if either validator evaluated its
  ;; storage argument before the nil check, `require-storage` would
  ;; throw `:execution-error/missing-storage` here instead of answering
  ;; \"nothing to reject\".
  (testing ":write-rej on absent entity-data → nil (no rejection)"
    (is (nil? (call :write-rej {:entity-type "fn" :entity-data nil} nil))))

  (testing ":type-check-binding-rej on absent entity-data → nil"
    (is (nil? (call :type-check-binding-rej {:entity-data nil :id nil} nil)))
    (is (nil? (call :type-check-binding-rej {:entity-data nil :id (random-uuid)} nil)))))
