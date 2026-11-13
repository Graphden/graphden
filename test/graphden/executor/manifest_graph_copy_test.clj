(ns graphden.executor.manifest-graph-copy-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.executor.manifest-graph-copy :as copy]))


(defn- graph
  []
  {:fns [{:id (random-uuid) :parent-ids #{(random-uuid)}}]
   :slots [] :fn-slots [] :bindings [] :list-items []
   :identity-target-ids #{(random-uuid)}})


(deftest structural-identity-sets-have-a-private-bounded-codec
  (let [source (graph)
        frozen (copy/freeze source 100000 100)]
    (is (= source (copy/thaw frozen nil)))
    (is (pos? (copy/charge-bytes frozen)))
    (is (thrown? clojure.lang.ExceptionInfo (copy/freeze source 100 100)))
    (is (thrown? clojure.lang.ExceptionInfo (copy/freeze source 100000 0)))))


(deftest literal-and-unknown-values-stay-ineligible
  (doseq [source [(assoc (graph) :unknown [])
                  (assoc (graph) :identity-target-ids #{"not-an-id"})
                  (assoc (graph) :bindings [{:value #{(random-uuid)}}])
                  (assoc (graph) :bindings [{:value (Object.)}])
                  (assoc (graph) :bindings [{:value (with-meta [] {:unknown true})}])
                  (with-meta (graph) {:unknown true})]]
    (is (thrown? clojure.lang.ExceptionInfo (copy/freeze source 100000 100)))))


(deftest mutable-leaves-are-never-shared
  (let [timestamp (java.sql.Timestamp. 23)
        source (assoc (graph) :bindings [{:value timestamp}])
        frozen (copy/freeze source 100000 100)
        first-copy (copy/thaw frozen nil)]
    (java.sql.Timestamp/.setTime timestamp 999)
    (java.sql.Timestamp/.setTime (get-in first-copy [:bindings 0 :value]) 888)
    (is (= 23 (java.sql.Timestamp/.getTime (get-in (copy/thaw frozen nil) [:bindings 0 :value]))))))
