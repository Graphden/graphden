(ns graphden.test-infra.openbao-test
  "The OpenBao image pin is one value: `graphden.test-infra.openbao/image`.
   Clojure callers read the var; `docker-compose.yml` cannot, so it is held
   to the same string here."
  (:require
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing]]
    [graphden.test-infra.openbao :as openbao]))


(deftest image-is-pinned-test
  (testing "a fixed version, never a floating tag"
    (is (re-find #":\d+\.\d+\.\d+$" openbao/image))))


(deftest compose-runs-the-same-openbao-test
  (let [images (->> (slurp (io/file "docker-compose.yml"))
                    (re-seq #"(?m)^\s*image:\s*(\S*openbao\S*)\s*$")
                    (map second))]
    (is (seq images) "docker-compose.yml declares an openbao image")
    (is (every? #{openbao/image} images)
        (str "docker-compose.yml openbao image(s) " (vec images)
             " differ from graphden.test-infra.openbao/image " openbao/image))))
