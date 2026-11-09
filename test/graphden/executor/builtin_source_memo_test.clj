(ns ^:serial graphden.executor.builtin-source-memo-test
  "Small real-checker regressions for builtin generation, without package or DB bootstrap."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.executor.registry.core :as registry]
    [graphden.packages.records.ids :as ids]
    [graphden.types.check :as check]))


(load-file "tools/ui_preview/builtin_plans.clj")


(defn- definitions
  [value return-type]
  [{:name :const :namespace "core.logic" :args {:value {:type 'a}} :return-type 'a}
   {:name :inner :namespace "builtin.memo" :parent :core.logic/const
    :args {:value {:value value}}}
   {:name :middle :namespace "builtin.memo" :parent :core.logic/const
    :args {:value :inner}}
   {:name :view :namespace "builtin.memo" :parent :core.logic/const
    :args {:value :middle} :return-type return-type}])


(defn- observe-generation
  ([source memo?] (observe-generation source memo? identity))
  ([source memo? stored-source]
   (let [tool-var #(ns-resolve 'tools.ui-preview.builtin-plans %)
         source-graph @(tool-var 'source-graph)
         check-definition! check/check-fn-def!
         scopes (atom [])
         signatures (atom nil)
         outcome (with-redefs-fn
                   {(tool-var 'source-definitions) (constantly source)
                    (tool-var 'entries) {:probe {:view (ids/fn-id "builtin.memo" :view)}}
                    (tool-var 'source-graph) #(source-graph (stored-source %))
                    #'check/check-fn-def!
                    (fn [definition]
                      (swap! scopes conj check/*ref-return-memo*)
                      (let [result (if memo?
                                     (check-definition! definition)
                                     (binding [check/*ref-return-memo* nil]
                                       (check-definition! definition)))]
                        (reset! signatures (registry/effective-rich-types))
                        result))}
                   #(try {:artifact ((tool-var 'generate))}
                         (catch clojure.lang.ExceptionInfo error
                           {:failure (ex-data error)})))]
     (assoc outcome :scopes @scopes :signatures @signatures))))


(deftest builtin-source-memos-do-not-cross-passes-or-generations
  (let [ambient (atom {:ambient :untouched})
        revisions [(definitions 7 :int) (definitions "changed" :text)]
        observed (binding [check/*ref-return-memo* ambient]
                   (mapv #(observe-generation % true) revisions))
        passes (mapv #(mapv first (partition-by identity (:scopes %))) observed)]
    (doseq [[source result scopes] (map vector revisions observed passes)]
      (is (nil? (:failure result)) (pr-str (:failure result)))
      (is (some? (:artifact result)))
      (testing "authored, reconstructed and exported checks have independent memos"
        (is (= 3 (count scopes)))
        (doseq [memo scopes]
          (is (instance? clojure.lang.Atom memo))
          (is (not (identical? ambient memo)))
          (is (seq (some-> memo deref)))))
      (testing "memoization preserves the actual plan and inferred signatures"
        (let [uncached (observe-generation source false)]
          (is (nil? (:failure uncached)) (pr-str (:failure uncached)))
          (is (= (:artifact uncached) (:artifact result)))
          (is (= (:signatures uncached) (:signatures result))))))
    (is (= 6 (count (distinct (mapcat identity passes))))
        "a second generation of the same identities cannot reuse the first one's cache")
    (is (= {:ambient :untouched} @ambient))
    (is (= (get-in (first observed) [:artifact :plans :probe :entries "view"])
           (get-in (second observed) [:artifact :plans :probe :entries "view"])))
    (is (not= (:artifact (first observed)) (:artifact (second observed))))))


(deftest reconstructed-type-change-cannot-borrow-the-authored-result
  (let [ambient (atom {:ambient :untouched})
        source (definitions 7 :int)
        changed (fn [stored]
                  (mapv #(cond-> % (= :inner (:name %))
                                 (assoc-in [:args :value :value] "incompatible stored value"))
                        stored))]
    (doseq [memo? [true false]]
      (let [result (binding [check/*ref-return-memo* ambient]
                     (observe-generation source memo? changed))]
        (is (nil? (:artifact result)))
        (is (= :types/check-failed (get-in result [:failure :type]))
            (pr-str (:failure result)))))
    (is (= {:ambient :untouched} @ambient))))
