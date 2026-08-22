(ns graphden.packages.app.tour-progress-test
  "The tutorial funnel's two counter base-fns (`app/tour/impls.clj`).

   They are reachable UNAUTHENTICATED — the landing demo's anonymous
   session runs lesson 01, and that population's drop-off is the one
   worth knowing — while the counters map is process-global and grows a
   key per distinct name. So the validation is the security boundary,
   not a nicety: a caller must not be able to name a counter."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [graphden.util.counters :as counters]))


(def ^:private impls
  (delay (-> (java.io.PushbackReader.
               (io/reader (io/resource "packages/app/tour/impls.clj")))
             ((fn [rdr]
                ;; Load the module the way the package loader does — eval the
                ;; file into its own ns — so the test exercises the shipped
                ;; file, not a copy of its logic.
                (let [forms (take-while some?
                                        (repeatedly #(read {:eof nil} rdr)))
                      ns-sym (second (first forms))]
                  (create-ns ns-sym)
                  (binding [*ns* (the-ns ns-sym)]
                    (doseq [f forms] (eval f)))
                  @(ns-resolve ns-sym 'impls)))))))


(defn- tour-delta
  "The `tour-*` slice of what changed since `before`.

   The counters map is PROCESS-GLOBAL and the unit suite runs eight
   namespaces at a time, so a raw `delta-since` also reports whatever a
   sibling namespace bumped mid-assertion (`:compile/all-miss` is the one
   that actually caught this out). Scoping to the prefix loses nothing:
   the impl builds every key as `(str \"tour-\" …)`, so a counter a caller
   managed to name would still land in this slice — which is the property
   these tests exist to pin."
  [before]
  (into {}
        (filter (fn [[k _]] (str/starts-with? (name k) "tour-")))
        (counters/delta-since before)))


(defn- count-event!
  [lesson event]
  ((:count-tour-event! @impls) {:lesson lesson :event event} {}))


(defn- count-step!
  [lesson step]
  ((:count-tour-step! @impls) {:lesson lesson :step step} {}))


(deftest counts-a-real-lesson-event
  (testing "a two-digit lesson + a known event bumps `:tour-<event>-<lesson>`"
    (let [before (counters/snapshot)]
      (is (= ":tour-started-01" (count-event! "01" "started")))
      (is (= ":tour-finished-14" (count-event! "14" "finished")))
      (let [delta (counters/delta-since before)]
        (is (= 1 (get delta :tour-started-01)))
        (is (= 1 (get delta :tour-finished-14)))))))


(deftest counts-a-step-bucket
  (testing "an advance bumps the per-step bucket the drop-off is read off"
    (let [before (counters/snapshot)]
      (is (= ":tour-step-05-3" (count-step! "05" 3)))
      (is (= ":tour-step-05-3" (count-step! "05" "3"))
          "the step arrives as JSON — a string index counts the same bucket")
      (is (= 2 (get (counters/delta-since before) :tour-step-05-3))))))


(deftest refuses-to-let-a-caller-name-a-counter
  ;; Each of these used to be a new key in a process-global map, forever.
  (testing "a lesson id that is not two digits counts NOTHING"
    (let [before (counters/snapshot)]
      (doseq [bogus ["1" "001" "0a" "" "../etc" "01; drop" nil
                     {:a 1} "01 " "٠١"]]
        (is (nil? (count-event! bogus "started"))
            (str "lesson " (pr-str bogus) " must not count"))
        (is (nil? (count-step! bogus 1))
            (str "lesson " (pr-str bogus) " must not count a step")))
      (is (empty? (tour-delta before))
          "and none of them left a key behind")))

  (testing "an event outside the three counts NOTHING"
    (let [before (counters/snapshot)]
      (doseq [bogus ["opened" "STARTED" "" nil "step " :step]]
        (is (nil? (count-event! "01" bogus))))
      (is (empty? (tour-delta before)))))

  (testing "a step index outside a lesson's possible length counts NOTHING"
    (let [before (counters/snapshot)]
      (doseq [bogus [-1 100 1000000 "abc" nil "1e9"]]
        (is (nil? (count-step! "01" bogus))
            (str "step " (pr-str bogus) " must not count")))
      (is (empty? (tour-delta before))))))
