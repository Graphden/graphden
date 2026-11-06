(ns ^:serial graphden.crud.reconstruction-memo-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.crud.type-check :as reconstruction]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.tx :as tx]
    [graphden.tenancy.context :as tenancy])
  (:import
    (java.sql
      Connection)))


(defn- connection
  [read-only? isolation]
  (reify Connection
    (isReadOnly [_] read-only?)

    (getTransactionIsolation [_] isolation)))


(defn- with-counted-rows
  [storage rows calls f]
  (with-redefs [tx/datasource :connection
                sp/read-entity (fn [source entity id]
                                 (swap! calls conj [source entity [id]])
                                 (get @rows id))
                sp/read-entities (fn [source entity ids]
                                   (swap! calls conj [source entity (vec ids)])
                                   (select-keys @rows ids))]
    (reconstruction/call-with-reconstruction-memo storage f)))


(deftest overlapping-batches-share-single-rows-and-negative-hits
  (let [storage {:connection (connection true Connection/TRANSACTION_REPEATABLE_READ)}
        rows (atom {:a {:id :a :name "a"} :b {:id :b :name "b"} :c {:id :c :name "c"}})
        calls (atom [])
        chain #(reconstruction/chain-fns-by-id storage %)]
    (with-counted-rows
      storage rows calls
      (fn []
        (testing "overlapping batches reuse identities independently of argument order"
          (let [first-root {:id :first :base-fn-id :a :element-fn-id :b}
                next-root {:id :next :base-fn-id :b :element-fn-id :c}]
            (is (= (assoc (select-keys @rows [:a :b]) :first first-root) (chain first-root)))
            (is (= (assoc (select-keys @rows [:b :c]) :next next-root) (chain next-root)))
            (is (nil? (reconstruction/reconstruct-fn-def storage :c)))
            (is (= [[:a :b] [:c]] (mapv last @calls)))))
        (testing "a missing batch row remains absent in later single and batch reads"
          (let [root {:id :root :base-fn-id :missing}]
            (is (= {:root root} (chain root)))
            (is (nil? (reconstruction/reconstruct-fn-def storage :missing)))
            (is (= {:root root} (chain root)))
            (is (= [[:a :b] [:c] [:missing]] (mapv last @calls)))))))
    (testing "the next snapshot sees changed and newly created rows"
      (swap! rows assoc :a {:id :a :name "new-a"} :missing {:id :missing :name "created"})
      (with-counted-rows
        storage rows calls
        #(let [root {:id :root :base-fn-id :a :element-fn-id :missing}]
           (is (= (assoc (select-keys @rows [:a :missing]) :root root) (chain root))))))))


(deftest large-snapshot-retains-rows-after-4096-distinct-reads
  (let [storage {:connection (connection true Connection/TRANSACTION_REPEATABLE_READ)}
        rows (atom (into {} (map #(vector % {:id % :name (str %)})) (range 4200)))
        calls (atom [])]
    (with-counted-rows
      storage rows calls
      (fn []
        (doseq [id (keys @rows)] (reconstruction/reconstruct-fn-def storage id))
        (let [before (count @calls)
              root {:id :root :base-fn-id 4199 :element-fn-id 4198}]
          (is (= 4200 before))
          (is (= (assoc (select-keys @rows [4199 4198]) :root root)
                 (reconstruction/chain-fns-by-id storage root)))
          (is (= before (count @calls)) "one-off query forms cannot evict or disable row reuse"))))))


(deftest memo-never-crosses-storage-org-or-unsafe-transaction-boundaries
  (let [storage {:connection (connection true Connection/TRANSACTION_REPEATABLE_READ)}
        other-storage (assoc storage :decorator :other)
        rows (atom {:a {:id :a :name "a"}})
        calls (atom [])
        read-row #(reconstruction/reconstruct-fn-def % :a)]
    (with-counted-rows
      storage rows calls
      (fn []
        (read-row storage)
        (read-row storage)
        (read-row other-storage)
        (binding [tenancy/*current-org* "other-org"]
          (read-row storage)
          (read-row storage))
        (read-row storage)))
    (is (= 4 (count @calls)))
    (doseq [[read-only? isolation] [[false Connection/TRANSACTION_REPEATABLE_READ]
                                    [true Connection/TRANSACTION_READ_COMMITTED]]]
      (let [unsafe {:connection (connection read-only? isolation)}]
        (reset! calls [])
        (with-counted-rows unsafe rows calls #(do (read-row unsafe) (read-row unsafe)))
        (is (= 2 (count @calls)))))))
