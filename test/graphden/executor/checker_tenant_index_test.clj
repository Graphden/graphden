(ns graphden.executor.checker-tenant-index-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.executor.checked-source-data :as data]
    [graphden.executor.checker-tenant-index :as tenant-index]))


(defn- input
  [org]
  {:org org
   :source {:graph {:fns [{:id :root :org-id "public"}]
                    :slots [] :fn-slots [] :bindings [] :list-items []}
            :namespaces [{:id :ns :org-id "public"}]}
   :seed {:rich {:by-id {}} :per-org {org {:by-id {}}}}
   :per-org-aliases {} :aliases {} :markers {} :original-signatures {}})


(deftest only-isolated-indexes-are-renamed
  (let [a (input "a") b (input "b")
        prepared (tenant-index/prepare-input a)]
    (is (data/same-input? prepared (tenant-index/prepare-input b)))
    (is (identical? (:source a) (:source prepared)))
    (is (identical? (:original-signatures a) (:original-signatures prepared)))
    (is (identical? (:per-org-aliases a) (:per-org-aliases prepared)))
    (is (= 1 (count (get-in prepared [:seed :per-org]))))))


(deftest public-mode-and-private-source-keep-their-original-partition
  (doseq [original [(input "public") (input nil)
                    (assoc-in (input "a") [:source :graph :fns 0 :org-id] "a")
                    (assoc-in (input "a") [:source :namespaces 0 :org-id] "a")
                    (assoc-in (input "a") [:source :graph :slots] [{:org-id "a"}])
                    (assoc-in (input "a") [:source :graph :fn-slots] [{:org-id "a"}])
                    (assoc-in (input "a") [:source :graph :bindings] [{:org-id "a"}])
                    (assoc-in (input "a") [:source :graph :list-items] [{:org-id "a"}])
                    (assoc-in (input "a") [:seed :per-org "foreign"] {:by-id {}})]]
    (is (identical? original (tenant-index/prepare-input original)))))


(deftest org-specific-inputs-never-collapse
  (doseq [path [[:per-org-aliases] [:aliases]
                [:markers] [:original-signatures] [:recovery] [:caller-narrowings]]]
    (testing (str path)
      (let [a (assoc-in (input "a") path {:org-valued-literal "a"})
            b (assoc-in (input "b") path {:org-valued-literal "b"})]
        (is (false? (data/same-input? (tenant-index/prepare-input a)
                                      (tenant-index/prepare-input b))))))))


(deftest literal-org-values-and-provenance-remain-semantic-inputs
  (let [binding-row {:id :binding :org-id "public"}
        a (assoc-in (input "a") [:source :graph :bindings] [(assoc binding-row :value "a")])
        b (assoc-in (input "b") [:source :graph :bindings] [(assoc binding-row :value "b")])
        signature-a (with-meta {:return-type :text} {:graphden.types.check.provenance/input "a"})
        signature-b (with-meta {:return-type :text} {:graphden.types.check.provenance/input "a"})]
    (is (= (:org (tenant-index/prepare-input a)) (:org (tenant-index/prepare-input b))))
    (is (false? (data/same-input? (tenant-index/prepare-input a) (tenant-index/prepare-input b))))
    (is (false? (data/same-input?
                  (tenant-index/prepare-input (assoc (input "a") :original-signatures {:fn signature-a}))
                  (tenant-index/prepare-input (assoc (input "b") :original-signatures {:fn signature-b})))))))


(deftest restore-mirrors-into-the-requesting-tenant-only
  (let [id (random-uuid)
        signature (with-meta {:return-type :text} {:provenance (Object.)})
        state {:rich {:by-id {id signature} :by-name {}}
               :per-org {"a" {:by-id {id signature} :by-name {}}}}
        artifact (data/compact-registry-state state)
        shared (tenant-index/prepare-artifact (:org (tenant-index/prepare-input (input "a"))) "a" artifact)
        restored (data/expand-registry-state (tenant-index/restore-artifact shared "b"))]
    (is (= #{"b"} (set (keys (:per-org restored)))))
    (is (identical? signature (get-in restored [:rich :by-id id])))
    (is (identical? signature (get-in restored [:per-org "b" :by-id id])))
    (is (nil? (tenant-index/restore-artifact shared "public")))
    (is (nil? (tenant-index/prepare-artifact (:org (tenant-index/prepare-input (input "a")))
                                             "a" (assoc-in artifact [:registry :per-org "foreign"] {:by-id {}}))))))
