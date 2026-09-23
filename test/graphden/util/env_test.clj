(ns graphden.util.env-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.util.env :as env]))


(deftest env-truthy?-test
  (let [env-truthy? env/env-truthy?]
    (testing "boolean true / false / nil"
      (is (true?  (env-truthy? true)))
      (is (false? (env-truthy? false)))
      (is (false? (env-truthy? nil))))

    (testing "wire-friendly strings (case-insensitive)"
      (doseq [yes ["1" "true" "TRUE" "True" "yes" "YES" "on" "ON"]]
        (is (true? (env-truthy? yes))
            (str "should be enabled: " yes))))

    (testing "everything else off"
      (doseq [no [""    "0"  "false" "FALSE" "no" "off"
                  "  true  "   ; whitespace not stripped — intentional
                  "yeah" "enabled" "yep"]]
        (is (false? (env-truthy? no))
            (str "should be disabled: " (pr-str no)))))

    (testing "non-string truthy/falsy values pass through boolean"
      (is (true?  (env-truthy? :keyword)))
      (is (true?  (env-truthy? 42)))
      (is (true?  (env-truthy? {:a 1}))))))


(deftest csv-list-trims-and-drops-blanks-test
  (is (= ["a" "b" "c"] (env/csv-list "a,b,c")))
  (is (= ["a" "b"] (env/csv-list " a , , b ")) "trims + drops empties, keeps order")
  (testing "nil / blank / all-blank → nil so the caller falls through"
    (is (nil? (env/csv-list nil)))
    (is (nil? (env/csv-list "")))
    (is (nil? (env/csv-list " , , ")))))
