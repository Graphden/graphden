(ns graphden.tenancy.grant-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.tenancy.grant :as grant]))


(def ^:private store
  (grant/static-grant-store
    [{:subject "alice" :capability :write :namespace "acme"}
     {:subject "bob" :capability :admin :namespace "acme.team"}
     {:subject "carol" :capability :read :namespace ""}]))


(deftest namespace-scope-covers-descendants
  (is (grant/can? store "alice" :write "acme") "exact namespace")
  (is (grant/can? store "alice" :write "acme.billing") "descendant")
  (is (not (grant/can? store "alice" :write "acme2")) "sibling prefix is NOT covered")
  (is (not (grant/can? store "alice" :write "other")) "unrelated namespace"))


(deftest admin-implies-other-capabilities-in-scope
  (is (grant/can? store "bob" :write "acme.team"))
  (is (grant/can? store "bob" :execute "acme.team.svc"))
  (is (grant/can? store "bob" :read "acme.team"))
  (is (not (grant/can? store "bob" :write "acme"))
      "admin is scoped to acme.team, not its parent acme"))


(deftest root-grant-covers-everything
  (is (grant/can? store "carol" :read "anything.deep.here"))
  (is (grant/can? store "carol" :read "acme")))


(deftest default-deny
  (testing "capabilities are independent — only :admin implies others"
    (is (not (grant/can? store "alice" :read "acme")) ":write does not imply :read")
    (is (not (grant/can? store "carol" :write "acme")) ":read does not imply :write"))
  (testing "unknown subject is denied"
    (is (not (grant/can? store "dave" :read "acme")))))


(deftest authorized?-bridges-the-principal
  (testing "subject is the principal's :user"
    (is (grant/authorized? store {:user "alice" :org "acme"} :write "acme.x"))
    (is (not (grant/authorized? store {:user "alice"} :read "acme")) "no :read grant"))
  (testing "an unauthenticated principal is denied"
    (is (not (grant/authorized? store {:authenticated? false} :read "acme")))
    (is (not (grant/authorized? store nil :read "acme")))))
