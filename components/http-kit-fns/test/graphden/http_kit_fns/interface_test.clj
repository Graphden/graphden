(ns graphden.http-kit-fns.interface-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.http-kit-fns.interface :as http-kit-fns]))


(deftest get-all-defs-test
  (testing "returns http-kit function definitions"
    (let [defs (http-kit-fns/get-all-defs)]
      (is (map? defs))
      (is (contains? defs :http-server))
      (is (contains? defs :http-stop))))

  (testing "http-server has correct metadata"
    (let [http-server-def (get (http-kit-fns/get-all-defs) :http-server)]
      (is (map? http-server-def))
      (is (contains? (:args http-server-def) :handler))
      (is (contains? (:args http-server-def) :port))
      (is (= :fn (:handler (:args http-server-def))))
      (is (= :int (:port (:args http-server-def))))
      (is (= :any (:return-type http-server-def)))
      (is (fn? (:impl http-server-def)))))

  (testing "http-stop has correct metadata"
    (let [http-stop-def (get (http-kit-fns/get-all-defs) :http-stop)]
      (is (map? http-stop-def))
      (is (contains? (:args http-stop-def) :server))
      (is (= :any (:server (:args http-stop-def))))
      (is (= :any (:return-type http-stop-def)))
      (is (fn? (:impl http-stop-def))))))
