(ns graphden.editor.root-migration-parse-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.editor.root-legacy :as legacy]
    [graphden.editor.root-migration-parse :as migration]
    [graphden.packages.records.ids :as ids]))


(deftest personal-root-cohort-parses-with-original-role-identities
  (let [root (str "root-migration." (random-uuid))
        source (random-uuid)
        parsed (migration/parse root
                                {:account-menu {:view source}
                                 :tutorial-catalog {:view (legacy/default-role-id :tutorial-catalog :view)}})
        own-ids (set (map :id (filter #(= :fn (:kind %)) (:records parsed))))]
    (is (= (ids/fn-id root :editor-root) (:root-id parsed)))
    (is (contains? own-ids (:root-id parsed)))
    (is (= #{source} (set (vals (:reference-identities parsed)))))
    (is (not (contains? own-ids source)))
    (is (= 1 (count (:overrides parsed))))))


(deftest unchanged-legacy-selection-parses-as-a-single-inherited-root
  (let [root (str "root-migration." (random-uuid))
        parsed (migration/parse root {})]
    (is (= [(:root-id parsed)]
           (mapv :id (filter #(= :fn (:kind %)) (:records parsed)))))
    (is (= {} (:reference-identities parsed)))))


(deftest duplicate-interface-type-names-retain-their-qualified-identity
  (let [parsed (migration/parse (str "root-migration." (random-uuid))
                                {:fn-picker {:view (random-uuid)}})
        records (:records parsed)
        getter-id (:fn-id (first (filter #(and (= :binding (:kind %))
                                               (= :model (:value %))) records)))
        input-slot-id (:slot-id (first (filter #(and (= :fn-slot (:kind %))
                                                     (= getter-id (:fn-id %))) records)))
        input-slot (first (filter #(= input-slot-id (:id %)) records))]
    (is (= (ids/fn-id "app.ui-fn-picker" :_root-view-input)
           (:type-fn-id input-slot)))))
