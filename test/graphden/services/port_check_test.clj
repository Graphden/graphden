(ns graphden.services.port-check-test
  "Tests for `graphden.services.port-check` — port-collision
   detector that runs at sync time."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.services.port-check :as port-check]))


(deftest scan-port-collisions-empty-test
  (testing "no fn-defs → empty map"
    (is (= {} (port-check/scan-port-collisions []))))
  (testing "single :http-server descendant — no collision"
    (is (= {} (port-check/scan-port-collisions
                [{:name :web-server :parent :http-server
                  :args {:handler :h :port 8080}}])))))


(deftest scan-port-collisions-detects-shared-port-test
  (testing "two :http-server descendants on the same port surface"
    (let [defs [{:name :prod-server :parent :http-server
                 :args {:handler :h :port 8080}}
                {:name :dev-server  :parent :http-server
                 :args {:handler :h :port 8080}}]
          out (port-check/scan-port-collisions defs)]
      (is (= 1 (count out)))
      (is (= #{:prod-server :dev-server} (set (get out 8080))))))

  (testing "value-shape binding `{:value 8080}` is also literal"
    (let [defs [{:name :a :parent :http-server
                 :args {:handler :h :port 8080}}
                {:name :b :parent :http-server
                 :args {:handler :h :port {:value 8080}}}]
          out (port-check/scan-port-collisions defs)]
      (is (= 1 (count out)))
      (is (= #{:a :b} (set (get out 8080))))))

  (testing "different ports — no collision"
    (let [defs [{:name :prod-server :parent :http-server
                 :args {:handler :h :port 8080}}
                {:name :dev-server  :parent :http-server
                 :args {:handler :h :port 9001}}]]
      (is (= {} (port-check/scan-port-collisions defs))))))


(deftest scan-port-collisions-walks-deep-ancestor-chain-test
  (testing "descendant of an :http-server descendant still counts"
    (let [defs [{:name :a-derived  :parent :app-server  :args {:port 8080}}
                {:name :app-server :parent :http-server :args {}}
                {:name :b-direct   :parent :http-server :args {:port 8080}}]
          out (port-check/scan-port-collisions defs)]
      (is (= 1 (count out)))
      (is (= #{:a-derived :b-direct} (set (get out 8080)))))))


(deftest scan-port-collisions-ignores-non-http-server-defs-test
  (testing "fn-def NOT rooted at :http-server is ignored even with :port"
    (let [defs [{:name :web :parent :http-server :args {:port 8080}}
                {:name :metric :parent :counter :args {:port 8080}}]]
      ;; Only :web bound on :http-server tree → no collision.
      (is (= {} (port-check/scan-port-collisions defs))))))


(deftest scan-port-collisions-skips-non-literal-port-test
  (testing "ref-binding `:port :some-fn-ref` is skipped (we can't eval refs)"
    (let [defs [{:name :a :parent :http-server :args {:port :env-driven}}
                {:name :b :parent :http-server :args {:port :env-driven}}]
          out (port-check/scan-port-collisions defs)]
      ;; Both refs — comparable by name but the helper only inspects
      ;; literals, so it bails out.
      (is (= {} out)))))


(deftest scan-port-collisions-handles-mi-test
  (testing "MI: parents-vector containing :http-server counts as ancestor"
    (let [defs [{:name :mi-server
                 :parents [:http-server :debug-mixin]
                 :args {:port 8080}}
                {:name :prod-server :parent :http-server
                 :args {:port 8080}}]
          out (port-check/scan-port-collisions defs)]
      (is (= #{:mi-server :prod-server} (set (get out 8080)))))))
