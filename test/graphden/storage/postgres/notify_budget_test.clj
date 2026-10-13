(ns graphden.storage.postgres.notify-budget-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is]]
    [graphden.crud.entities.invalidation :as invalidation]
    [graphden.storage.postgres.graph-epoch :as epoch]
    [graphden.storage.postgres.notify :as notify]
    [graphden.storage.postgres.util :as util]
    [graphden.versioning.storage.core :as versioned]))


(deftest oversized-exact-coverage-is-omitted-not-truncated
  (let [event {:kind :fn :op :invalidate :id "one" :branch-id "branch"
               :org-id "org" :emitter "emitter" :invalidate-origin? true
               :epochs (vec (range 1 10000 2))}
        payload (notify/bounded-payload event)]
    (is (= (dissoc event :epochs) (notify/parse-payload payload)))
    (is (< (alength (String/.getBytes payload "UTF-8")) 8192))
    (is (= (notify/format-payload (assoc event :epochs [1 3 9]))
           (notify/bounded-payload (assoc event :epochs [1 3 9]))))))


(deftest utf8-budget-includes-emitter-and-noncoverage-fields
  (let [calls (atom [])
        emit (notify/make-emitter nil)]
    (with-redefs [util/exec! (fn [& args] (swap! calls conj args))]
      (emit {:kind :fn :op :invalidate :id (str/join (repeat 4096 "я"))})
      (is (empty? @calls))
      (emit {:kind :fn :op :invalidate :id "one" :epochs (vec (range 5000))})
      (is (= 1 (count @calls)))
      (let [event (notify/parse-payload (last (second (first @calls))))]
        (is (= "one" (:id event)))
        (is (string? (:emitter event)))
        (is (not (contains? event :epochs)))))))


(deftest bulk-publication-cannot-cover-an-unreceived-sibling-delta
  (let [events (atom [])]
    (binding [epoch/*request-bump-log* (atom [11 13 17])]
      (with-redefs [versioned/current-branch-id (constantly "branch")]
        (invalidation/notify-after-bulk-write!
          {:notify-emitter #(swap! events conj %)} nil {:org-id "org"})
        (is (= [{:kind :fn :op :invalidate :id "" :branch-id "branch" :org-id "org"}]
               @events))
        ;; Losing this single full-clear event leaves every exact bump
        ;; uncovered, so the existing epoch gap can still trigger healing.
        (is (not-any? :epochs @events))
        (is (= [11 13 17] @epoch/*request-bump-log*))
        (let [receiver (epoch/attach-state {})]
          ;; Model the existing receiver's coverage hook, both when delivery
          ;; succeeds and when this whole event is lost. Neither can bury
          ;; unseen writes under a scalar maximum or a seed's coverage.
          (epoch/cover-foreign! receiver (:epochs (first @events)))
          (is (= #{:foreign} (epoch/classify-range receiver 10 17 0)))
          (is (= #{:foreign} (epoch/classify-range (epoch/attach-state {}) 10 17 0))))
        (is (nil? (invalidation/notify-after-bulk-write! {} nil {})))))))
