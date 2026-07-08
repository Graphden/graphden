(ns graphden.schema.extension-seam-test
  "Type-3 swap seam (PACKAGE_DISTRIBUTION § 6): the `extend-builder`
   schema-extension seam accepts an ARBITRARY third-party entity — the SAME
   seam core / tenancy (`:org` / `:grant`) / packages (`:package-version` /
   `:package-install`) chain their own entities onto. This is the answer to
   'a new module adds its own tables without touching the storage backend'.
   Storage MATERIALISATION of a builder-added entity is proven separately by
   registry-test's `:package-install` round-trip."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.schema.graph.schema :as gds]
    [graphden.schema.malli.core :as malli]
    [graphden.schema.protocol.protocol :as ds]))


(deftest extend-builder-accepts-arbitrary-third-party-entity
  (let [widget-uuid (random-uuid)
        label-uuid (random-uuid)
        count-uuid (random-uuid)
        ;; A "third-party" module's own extend-builder — chained AFTER the core
        ;; graph schema, exactly as tenancy / packages chain theirs.
        third-party (fn [b]
                      (ds/add-entity b :widget widget-uuid
                                     {:label {:uuid label-uuid :type :text}
                                      :count {:uuid count-uuid :type :int}}))
        schema (-> (malli/create-builder)
                   (gds/extend-builder)
                   (third-party)
                   (ds/build))]
    (testing "the seam produced the novel entity with its declared fields"
      (let [fields (ds/entity-fields schema :widget)]
        (is (some? fields) "extend-builder accepted a novel entity with no backend change")
        (is (= :text (:type (:label fields))))
        (is (= :int (:type (:count fields))))))
    (testing "core entities remain — the extension composes, it does not replace"
      (is (some? (ds/entity-fields schema :fn))))))
