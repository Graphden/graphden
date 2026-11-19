(ns ^:serial graphden.executor.source-recipe-jdbc-metadata-test
  "The JDBC metadata producer is temporarily observed through its root Var."
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.executor.source-recipe-jdbc-rows :as rows]
    [next.jdbc.result-set :as rs]))


(deftest columnar-navigation-metadata-is-created-for-the-current-lease-test
  (let [old-connection (Object.)
        new-connection (Object.)
        opts {:builder-fn rs/as-unqualified-lower-maps}
        original (rs/datafiable-row {:id 1 :nullable nil} old-connection opts)
        frozen (rows/freeze-columnar [original {:id 2 :nullable nil}] 65536)
        calls (atom [])
        datafiable-row rs/datafiable-row
        [first-copy second-copy]
        (with-redefs [rs/datafiable-row (fn [row connection options]
                                          (when (identical? new-connection connection)
                                            (swap! calls conj [row connection options]))
                                          (datafiable-row row connection options))]
          ;; A parallel namespace's JDBC rows must not enter this lease's
          ;; observations. Reproduce that overlap without another database.
          @(future (rs/datafiable-row {:unrelated true} old-connection opts))
          [(rows/thaw frozen new-connection opts)
           (rows/thaw frozen new-connection opts)])]
    (is (= [[{:id 1 :nullable nil} new-connection opts]
            [{:id 1 :nullable nil} new-connection opts]] @calls))
    (is (nil? (meta (second first-copy))))
    (is (= (set (keys (meta original))) (set (keys (meta (first first-copy))))))
    (is (every? false? (map identical? (vals (meta original))
                            (vals (meta (first first-copy))))))
    (is (every? false? (map identical? (vals (meta (first first-copy)))
                            (vals (meta (first second-copy))))))))
