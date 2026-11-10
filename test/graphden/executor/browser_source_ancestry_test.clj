(ns graphden.executor.browser-source-ancestry-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.executor.browser-source-ancestry :as ancestry]))


(defn- collector
  [rows hints calls authorize! limit]
  (ancestry/collector
    {:hint-fns hints :limit limit :authorize! authorize!
     :reject! #(throw (ex-info "Rejected" {:reason %}))
     :read-fns (fn [ids]
                 (swap! calls conj ids)
                 (keep rows ids))}))


(deftest fresh-ancestry-batches-deep-chains-and-terminates-cycles
  (let [rows (into {} (map (fn [id] [id {:id id :parent-ids [(mod (inc id) 70)]}])) (range 70))
        calls (atom [])
        collect (collector rows rows calls (constantly nil) 100)
        result (collect #{0} #{} #{0})]
    (is (= (set (range 70)) (:ids result)))
    (is (= [(set (range 70))] @calls))
    (is (= 70 (count (:fns result))))))


(deftest hints-never-supply-edges-or-turn-missing-extras-into-errors
  (let [rows {:root {:id :root :parent-ids [:fresh]} :fresh {:id :fresh}}
        hints {:root {:id :root :parent-ids [:gone]} :gone {:id :gone}}
        calls (atom [])
        collect (collector rows hints calls (constantly nil) 10)]
    (is (= #{:root :fresh} (:ids (collect #{:root} #{} #{:root}))))
    (is (= [#{:root :gone} #{:fresh}] @calls))
    (let [missing (collector (dissoc rows :fresh) hints (atom []) (constantly nil) 10)]
      (is (= :source-missing
             (try (missing #{:root} #{} #{:root})
                  (catch clojure.lang.ExceptionInfo error (:reason (ex-data error)))))))))


(deftest interface-contracts-stop-expansion-until-payload-validation
  (doseq [authorization [:bare-type {:type-contract {:fn {:id :root}}}]]
    (let [calls (atom [])
          collect (collector {:root {:id :root :parent-ids [:private]}} nil calls
                             (constantly authorization) 10)]
      (is (= #{:root} (:ids (collect #{:root} #{} #{:root}))))
      (is (= [#{:root}] @calls)))))


(deftest actual-function-limit-includes-the-unprocessed-frontier
  (let [rows {:root {:id :root :parent-ids [:parent]} :parent {:id :parent}}
        collect (collector rows rows (atom []) (constantly nil) 2)]
    (is (= :source-function-limit
           (try (collect #{:root} #{} #{:root :other})
                (catch clojure.lang.ExceptionInfo error (:reason (ex-data error))))))))
