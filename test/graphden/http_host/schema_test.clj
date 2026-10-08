(ns graphden.http-host.schema-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.accounts.init :as accounts]
    [graphden.schema.protocol.protocol :as schema]
    [graphden.system.init.storage]
    [integrant.core :as ig]))


(deftest bare-and-account-enabled-boot-share-the-existing-session-schema
  (let [bare (ig/init-key :db/schema {})
        enabled (ig/init-key :db/schema {:extensions [(accounts/schema-extension)]})]
    (is (contains? (set (schema/entities bare)) :session))
    (is (not (contains? (set (schema/entities bare)) :account)))
    (is (not (contains? (set (schema/entities bare)) :identity)))
    (is (= (schema/entity-uuid bare :session) (schema/entity-uuid enabled :session)))
    (is (= (schema/entity-fields bare :session) (schema/entity-fields enabled :session)))
    (is (= :text (get-in (schema/entity-fields bare :session) [:account-id :type])))))
