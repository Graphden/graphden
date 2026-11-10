(ns graphden.executor.browser-source-ancestry-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.executor.browser-source-ancestry :as ancestry]))


(defn- collector
  [rows calls authorize! limit]
  (ancestry/collector
    {:limit limit :authorize! authorize!
     :reject! #(throw (ex-info "Rejected" {:reason %}))
     :read-fns (fn [ids]
                 (swap! calls conj ids)
                 (keep rows ids))}))


(deftest fresh-ancestry-collects-deep-chains-and-terminates-cycles
  (let [rows (into {} (map (fn [id] [id {:id id :parent-ids [(mod (inc id) 70)]}])) (range 70))
        calls (atom [])
        collect (collector rows calls (constantly nil) 100)
        result (collect #{0} #{} #{0})]
    (is (= (set (range 70)) (:ids result)))
    (is (= 70 (count @calls)))
    (is (= 70 (count (:fns result))))))


(deftest actual-missing-ancestry-is-rejected
  (let [rows {:root {:id :root :parent-ids [:fresh]} :fresh {:id :fresh}}
        calls (atom [])
        collect (collector rows calls (constantly nil) 10)]
    (is (= #{:root :fresh} (:ids (collect #{:root} #{} #{:root}))))
    (is (= [#{:root} #{:fresh}] @calls))
    (let [missing (collector (dissoc rows :fresh) (atom []) (constantly nil) 10)]
      (is (= :source-missing
             (try (missing #{:root} #{} #{:root})
                  (catch clojure.lang.ExceptionInfo error (:reason (ex-data error)))))))))


(deftest interface-contracts-stop-expansion-until-payload-validation
  (doseq [authorization [:bare-type {:type-contract {:fn {:id :root}}}]
          field [:parent-ids :base-fn-id :return-type-fn-id :element-fn-id]]
    (let [calls (atom [])
          collect (collector {:root {:id :root field (if (= :parent-ids field) [:private] :private)}} calls
                             (constantly authorization) 10)]
      (is (= #{:root} (:ids (collect #{:root} #{} #{:root}))))
      (is (= [#{:root}] @calls)))))


(deftest actual-function-limit-includes-the-unprocessed-frontier
  (let [rows {:root {:id :root :parent-ids [:parent]} :parent {:id :parent}}
        collect (collector rows (atom []) (constantly nil) 2)]
    (is (= :source-function-limit
           (try (collect #{:root} #{} #{:root :other})
                (catch clojure.lang.ExceptionInfo error (:reason (ex-data error))))))))
