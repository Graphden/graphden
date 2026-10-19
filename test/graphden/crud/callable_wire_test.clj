(ns graphden.crud.callable-wire-test
  "Contextual callable JSON decoding; no database fixture or package sweep."
  (:require
    [cheshire.core :as json]
    [clojure.test :refer [deftest is]]
    [graphden.crud.types-api :as ta]
    [graphden.types.core :as types]))


(defn- through-json
  [value]
  (ta/json->type (json/parse-string (json/generate-string value))))


(deftest callable-contracts-survive-json-with-separate-variable-sorts
  (doseq [effects [:any #{} #{:db :network}]
          execution [:unknown #{} #{:jvm-server} #{:browser-main}
                     #{:jvm-server :browser-main} [:execution-var 'E]
                     [:execution-var 'E-17] [:execution-var 'e]]]
    (let [shape [:fn {:x 'a} 'a effects execution]
          decoded (through-json shape)]
      (is (= shape decoded))
      (is (types/well-formed? decoded))))
  (let [shape [:fn {:callback [:fn {} :text #{} [:execution-var 'E]]}
               [:map :text [:list [:fn {:x 'a} 'a #{:db} #{:browser-main}]]]
               :any [:execution-var 'E]]]
    (is (= shape (through-json shape)))))


(deftest legacy-callable-json-retains-shape-and-unknown-execution
  (doseq [shape [[:fn {:x :int} :int] [:fn {} :int :any] [:fn {} :int #{:db}]]]
    (let [decoded (through-json shape)]
      (is (= shape decoded))
      (is (= :unknown (types/fn-execution decoded))))))


(deftest malformed-callable-wire-contracts-are-rejected
  (doseq [wire [["fn" {} "int" [] ["worker"]]
                ["fn" {} "int" [] ["execution-var"]]
                ["fn" {} "int" [] ["execution-var" "E" "F"]]
                ["fn" {} "int" [] ["execution-var" nil]]
                ["fn" {} "int" [] "E"]
                ["fn" {} "int" [] nil]
                ["fn" {} "int" [] "any"]
                ["fn" {} "int" [1] "unknown"]
                ["fn" {} "int" "db" "unknown"]
                ["fn" [] "int" [] "unknown"]
                ["fn" {}] ["fn" {} "int" [] "unknown" "extra"]]]
    (is (thrown? clojure.lang.ExceptionInfo (ta/json->type wire)))))
