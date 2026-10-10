(ns graphden.executor.registry.anonymous-test
  "Anonymous graph identities have signatures without claiming name aliases."
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.executor.registry.core :as registry]
    [graphden.tenancy.context :as tenancy]
    [graphden.types.check.provenance :as provenance]))


(defn- anonymous-signature
  [id]
  (let [definition {:fn-id id :name nil :namespace "personal.components"
                    :parent :core.logic/const :args {:value 1}}]
    {:definition definition
     :signature (provenance/stamp {:return :int :args {} :namespace "personal.components"}
                                  definition)}))


(deftest fresh-anonymous-identities-record-current-provenance-without-name-keys
  (doseq [org [tenancy/public-org "anonymous-registry-tenant"]]
    (binding [registry/*rich-types-override* (atom {})
              registry/*per-org-rich-override* (atom {})]
      (tenancy/with-org org
                        (let [id (random-uuid)
                              {:keys [definition signature]} (anonymous-signature id)]
                          (registry/record-rich-types-raw! id nil signature)
                          (is (= :int (:return (registry/rich-type-of-id id))))
                          (is (provenance/matches? (registry/rich-type-of-id id) definition))
                          (is (empty? (:by-name @registry/*rich-types-override*)))
                          (is (empty? (get-in @registry/*per-org-rich-override* [org :by-name]))))))))


(deftest becoming-anonymous-removes-owned-aliases-and-updates-identity-provenance
  (doseq [org [tenancy/public-org "anonymous-registry-tenant"]]
    (binding [registry/*rich-types-override* (atom {})
              registry/*per-org-rich-override* (atom {})]
      (tenancy/with-org org
                        (let [id (random-uuid)
                              other (random-uuid)
                              {:keys [definition signature]} (anonymous-signature id)]
                          (registry/record-rich-types-raw! id :formerly-named
                                                           {:return :text :args {} :namespace "personal.components"})
                          ;; Another identity owns the bare spelling after a namespace collision.
                          (registry/record-rich-types-raw! other :formerly-named
                                                           {:return :text :args {} :namespace "another.components"})
                          (registry/record-rich-types-raw! id nil signature)
                          (is (= :int (:return (registry/rich-type-of-id id))))
                          (is (provenance/matches? (registry/rich-type-of-id id) definition))
                          (is (nil? (registry/rich-type-of :personal.components/formerly-named)))
                          (is (= other (:fn-id (registry/rich-type-of :formerly-named))))
                          (is (not-any? #(= id %) (vals (:by-name @registry/*rich-types-override*))))
                          (is (not-any? #(= id %) (vals (get-in @registry/*per-org-rich-override* [org :by-name])))))))))
