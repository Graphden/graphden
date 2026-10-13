(ns graphden.executor.registry-conditional-patch-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.executor.registry.conditional-patch :as patch]))


(deftest newer-metadata-certificate-is-never-replaced-by-staged-check
  (let [id (random-uuid)
        original (with-meta {:return :uuid} {:source-certificate :original})
        newer (with-meta {:return :uuid} {:source-certificate :newer})
        before {:by-id {id original} :by-name {:example id}}
        staged (assoc-in before [:by-id id] (with-meta {:return :uuid} {:source-certificate :staged}))
        target (atom (assoc-in before [:by-id id] newer))]
    (is (= original newer) "structural equality alone cannot protect a certificate")
    (is (false? (patch/apply! target before staged [[:by-id id]])))
    (is (identical? newer (get-in @target [:by-id id])))
    (is (= (:by-name before) (:by-name @target)))))


(deftest staged-check-preserves-package-name-and-unrelated-writes
  (let [package (random-uuid)
        personal (random-uuid)
        unrelated (random-uuid)
        before {:by-id {package {:return :text} personal {:return :int}}
                :by-name {:shared package :personal personal}}
        staged (-> before
                   (assoc-in [:by-id personal] {:return :numeric})
                   (assoc-in [:by-name :shared] personal))
        target (atom (assoc-in before [:by-id unrelated] {:return :keyword}))
        paths (patch/rich-paths before staged [package personal] #{package})]
    (is (patch/apply! target before staged paths))
    (is (= :numeric (get-in @target [:by-id personal :return])))
    (is (= package (get-in @target [:by-name :shared])))
    (is (= :text (get-in @target [:by-id package :return])))
    (is (= :keyword (get-in @target [:by-id unrelated :return])))))


(deftest concurrent-name-owner-change-refuses-the-whole-staged-index-patch
  (let [id (random-uuid)
        other (random-uuid)
        before {:by-id {id {:return :int}} :by-name {:entry id}}
        staged (-> before (update :by-name dissoc :entry)
                   (assoc-in [:by-name :renamed] id))
        current (assoc-in before [:by-name :entry] other)
        target (atom current)]
    (is (false? (patch/apply! target before staged
                              (patch/rich-paths before staged [id] #{}))))
    (is (= current @target))
    (is (not (contains? (:by-name @target) :renamed)))))


(deftest unchanged-staged-read-still-protects-readiness
  (let [id (random-uuid)
        original (with-meta {:return :int} {:certificate :original})
        newer (with-meta {:return :int} {:certificate :newer})
        before {:by-id {id original}}
        target (atom {:by-id {id newer}})]
    (is (false? (patch/apply! target before before [[:by-id id]])))
    (is (identical? newer (get-in @target [:by-id id])))))
