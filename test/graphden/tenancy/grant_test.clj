(ns graphden.tenancy.grant-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.tenancy.grant :as grant]))


;; Grant matching keys on the STABLE subject-id, not the mutable username.
;; In unit tests the id and the name are the same string (only distinctness
;; matters); `subj` builds the `{:id :name}` identity pair `can?` expects.
(defn- subj
  [s]
  {:id s :name s})


(def ^:private store
  (grant/static-grant-store
    [{:subject-id "alice" :subject "alice" :capability :write :namespace "acme"}
     {:subject-id "bob" :subject "bob" :capability :admin :namespace "acme.team"}
     {:subject-id "carol" :subject "carol" :capability :read :namespace ""}]))


(deftest namespace-scope-covers-descendants
  (is (grant/can? store (subj "alice") :write "acme") "exact namespace")
  (is (grant/can? store (subj "alice") :write "acme.billing") "descendant")
  (is (not (grant/can? store (subj "alice") :write "acme2")) "sibling prefix is NOT covered")
  (is (not (grant/can? store (subj "alice") :write "other")) "unrelated namespace"))


(deftest admin-implies-other-capabilities-in-scope
  (is (grant/can? store (subj "bob") :write "acme.team"))
  (is (grant/can? store (subj "bob") :execute "acme.team.svc"))
  (is (grant/can? store (subj "bob") :read "acme.team"))
  (is (not (grant/can? store (subj "bob") :write "acme"))
      "admin is scoped to acme.team, not its parent acme"))


(deftest root-grant-covers-everything
  (is (grant/can? store (subj "carol") :read "anything.deep.here"))
  (is (grant/can? store (subj "carol") :read "acme")))


(deftest default-deny
  (testing "capabilities are independent — only :admin implies others"
    (is (not (grant/can? store (subj "alice") :read "acme")) ":write does not imply :read")
    (is (not (grant/can? store (subj "carol") :write "acme")) ":read does not imply :write"))
  (testing "unknown subject is denied"
    (is (not (grant/can? store (subj "dave") :read "acme")))))


(deftest write-implies-narrow-edit-caps
  ;; §4.3: :write subsumes the narrower edit caps; :admin still subsumes all.
  (testing ":write holder can :bind-args / :append-list in scope"
    (is (grant/can? store (subj "alice") :bind-args "acme.billing"))
    (is (grant/can? store (subj "alice") :append-list "acme")))
  (testing ":admin subsumes the new caps too"
    (is (grant/can? store (subj "bob") :bind-args "acme.team"))
    (is (grant/can? store (subj "bob") :append-list "acme.team.svc")))
  (testing "the narrow caps are one-way — they do NOT imply :write or each other"
    (let [s (grant/static-grant-store
              [{:subject-id "ed" :subject "ed" :capability :bind-args :namespace "ns"}])]
      (is (grant/can? s (subj "ed") :bind-args "ns"))
      (is (not (grant/can? s (subj "ed") :write "ns")) ":bind-args is narrower than :write")
      (is (not (grant/can? s (subj "ed") :append-list "ns")) ":bind-args ≠ :append-list"))))


(deftest can-mutate-coarse-gate
  ;; §4.3 coarse gate: any write-family cap passes; read-only / unknown don't.
  (testing "write-family caps pass"
    (is (grant/can-mutate? store (subj "alice")))
    (is (grant/can-mutate? store (subj "bob")))
    (let [s (grant/static-grant-store
              [{:subject-id "ed" :subject "ed" :capability :bind-args :namespace "ns"}
               {:subject-id "ann" :subject "ann" :capability :append-list :namespace "ns"}])]
      (is (grant/can-mutate? s (subj "ed")))
      (is (grant/can-mutate? s (subj "ann")))))
  (testing ":read-only / unknown subject does NOT pass"
    (is (not (grant/can-mutate? store (subj "carol"))))
    (is (not (grant/can-mutate? store (subj "dave"))))))


(deftest authorized?-bridges-the-principal
  (testing "subject is the principal's stable :user-id"
    (is (grant/authorized? store {:user "alice" :user-id "alice" :org "acme"} :write "acme.x"))
    (is (not (grant/authorized? store {:user "alice" :user-id "alice"} :read "acme")) "no :read grant"))
  (testing "an unauthenticated principal (no :user-id) is denied"
    (is (not (grant/authorized? store {:user "alice" :authenticated? false} :read "acme")))
    (is (not (grant/authorized? store nil :read "acme")))))


(deftest personal-namespaces
  (let [base (grant/static-grant-store
               [{:subject-id "alice" :subject "alice" :capability :write :namespace "shared"}])
        store (grant/with-personal-namespaces base "users")]
    (testing "the convention"
      (is (= "users.alice" (grant/personal-namespace "users" "alice"))))
    (testing "a user implicitly owns their personal namespace (full :admin → all caps)"
      (is (grant/can? store (subj "alice") :write "users.alice"))
      (is (grant/can? store (subj "alice") :execute "users.alice.proj") "descendant")
      (is (grant/can? store (subj "alice") :read "users.alice")))
    (testing "the base store's grants still apply"
      (is (grant/can? store (subj "alice") :write "shared")))
    (testing "a user does NOT own another user's personal namespace"
      (is (not (grant/can? store (subj "alice") :write "users.bob")))
      (is (not (grant/can? store (subj "bob") :write "users.alice"))))
    (testing "a user with no base grants still owns their own namespace"
      (is (grant/can? store (subj "bob") :write "users.bob")))))


(deftest workspace-is-the-union-of-granted-namespaces
  (let [base (grant/static-grant-store
               [{:subject-id "alice" :subject "alice" :capability :write :namespace "acme.team"}
                {:subject-id "alice" :subject "alice" :capability :read :namespace "shared.lib"}
                {:subject-id "alice" :subject "alice" :capability :admin :namespace nil}])
        store (grant/with-personal-namespaces base "users")]
    (testing "named granted namespaces + personal; root/blank grants excluded"
      (is (= #{"acme.team" "shared.lib" "users.alice"} (grant/workspace store (subj "alice")))))
    (testing "a user with only their personal namespace"
      (is (= #{"users.bob"} (grant/workspace store (subj "bob")))))))
