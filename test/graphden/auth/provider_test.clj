(ns graphden.auth.provider-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.auth.provider :as auth]))


(defn- req
  [authz]
  (cond-> {:headers {}} authz (assoc-in [:headers "authorization"] authz)))


(deftest extract-bearer-test
  (is (= "abc" (auth/extract-bearer (req "Bearer abc"))))
  (is (nil? (auth/extract-bearer (req "Basic abc"))) "wrong scheme → nil")
  (is (nil? (auth/extract-bearer (req nil))) "no header → nil")
  (is (= "" (auth/extract-bearer (req "Bearer ")))))


(deftest single-token-provider-test
  (let [p (auth/single-token-provider "s3cret")]
    (testing "correct token authenticates"
      (is (= {:authenticated? true} (auth/authenticate p (req "Bearer s3cret")))))
    (testing "wrong / missing / blank-scheme token rejected"
      (is (= {:authenticated? false} (auth/authenticate p (req "Bearer nope"))))
      (is (= {:authenticated? false} (auth/authenticate p (req nil))))
      (is (= {:authenticated? false} (auth/authenticate p (req "Basic s3cret")))))
    (testing "never throws"
      (is (false? (:authenticated? (auth/authenticate p {}))))))
  (testing "unset / blank configured token never validates (no (= nil nil) bypass)"
    (doseq [t ["" "   " nil]]
      (let [p (auth/single-token-provider t)]
        (is (false? (:authenticated? (auth/authenticate p (req "Bearer "))))
            (str "blank token=" (pr-str t) " + empty bearer must NOT validate"))
        (is (false? (:authenticated? (auth/authenticate p (req nil)))))))))
