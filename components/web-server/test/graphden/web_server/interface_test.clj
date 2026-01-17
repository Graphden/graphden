(ns graphden.web-server.interface-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.web-server.interface :as web-server]))


(deftest all-defs-test
  (testing "returns all web server function definitions"
    (is (map? web-server/all-defs))

    ;; http-kit functions
    (is (contains? web-server/all-defs :http-server))
    (is (contains? web-server/all-defs :http-stop))

    ;; reitit functions
    (is (contains? web-server/all-defs :reitit-matcher)))

  (testing "http-server has correct metadata"
    (let [hs-def (get web-server/all-defs :http-server)]
      (is (map? hs-def))
      (is (contains? (:args hs-def) :handler))
      (is (contains? (:args hs-def) :port))
      (is (= :fn (:handler (:args hs-def))))
      (is (= :int (:port (:args hs-def))))
      (is (= :any (:return-type hs-def)))
      (is (fn? (:impl hs-def)))))

  (testing "reitit-matcher has correct metadata"
    (let [rm-def (get web-server/all-defs :reitit-matcher)]
      (is (map? rm-def))
      (is (contains? (:args rm-def) :routes))
      (is (= :jsonb (:routes (:args rm-def))))
      (is (= :fn (:return-type rm-def)))
      (is (fn? (:impl rm-def))))))
