(ns graphden.crud.callable-persistence-test
  "Real PostgreSQL JSONB codec at source/export and anonymous override seams."
  (:require
    [cheshire.core :as json]
    [clojure.test :refer [deftest is]]
    [graphden.crud.type-check :as tc]
    [graphden.crud.types-api :as ta]
    [graphden.packages.export :as export]
    [graphden.packages.records.parse :as parse]
    [graphden.storage.postgres.codec :as codec]
    [graphden.storage.protocol.core :as sp]
    [graphden.types.core :as types]
    [graphden.types.core.shapes :as shapes]))


(defn- persisted-constraint
  [constraint]
  (let [codec (codec/create-codec)]
    (sp/decode-value codec (sp/encode-value codec constraint {:type :jsonb}) {:type :jsonb})))


(deftest named-callable-execution-vars-survive-persistence-export-and-reimport
  (doseq [execution [[:execution-var 'E] [:execution-var 'E-17]]]
    (let [inner [:fn {} :int #{:db} execution]
          source [{:name :callback :namespace "wire"
                   :fn-type [{:callback inner} [:list [:map :text inner]] #{:db} execution]}]
          rows (parse/parse-module source)
          saved (mapv #(cond-> % (:constraint %) (update :constraint persisted-constraint)) rows)
          exported (export/records->fn-defs saved)
          reimported (parse/parse-module exported)]
      (is (= rows saved))
      (is (= source exported))
      (is (= (set rows) (set reimported)))
      (is (= (set (map :id rows)) (set (map :id reimported)))))))


(deftest anonymous-callable-overrides-survive-jsonb-before-reconstruction
  (let [shape [:fn {} [:list [:map :text :int]] #{:db} [:execution-var 'E]]
        rows (parse/parse-module
               [{:name :callback-source :namespace "wire"
                 :args {:callback {:type shape}} :return-type :int}])
        type-row (first (filter #(= shape (:constraint %)) rows))
        saved (update type-row :constraint persisted-constraint)
        slot (first (filter #(= :slot (:kind %)) rows))
        binding {:slot-id (:id slot) :type-override-fn-id (:id type-row)}
        input (tc/binding-shape-for-edn nil {(:id saved) saved} {} {} binding)]
    (is (= type-row saved))
    (is (= (:id saved) (:type-fn-id slot)))
    (is (= {:type shape} input))
    (is (shapes/fn-type? (:type input)))))


(deftest damaged-untagged-execution-name-does-not-gain-browser-support
  (let [old [:fn {} :int #{} [:execution-var "E"]]
        decoded (persisted-constraint old)]
    (is (= old decoded))
    (is (false? (shapes/fn-type? decoded)))
    (is (nil? (shapes/fn-execution decoded)))
    (is (false? (types/subtype? decoded [:fn {} :int #{} #{:browser-main}])))
    (let [error (try
                  (parse/parse-module [{:name :damaged :namespace "wire"
                                        :fn-type (vec (rest decoded))}])
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (= :records/unsupported-type-ref (:type (ex-data error)))))))


(deftest standalone-execution-collections-survive-source-storage-wire-and-reimport
  (doseq [execution [:unknown #{} #{:browser-main} [:execution-var 'E]]
          shape [[:map :keyword [:fn {} :int #{:db} execution]]
                 [:list [:fn {} :int :any execution]]
                 [:list [:map :keyword [:fn {} :int #{} execution]]]]]
    (let [source [{:name :contracts :namespace "wire" :args {:handlers {:type shape}}}]
          rows (parse/parse-module source)
          saved (mapv #(cond-> % (:constraint %) (update :constraint persisted-constraint)) rows)
          exported (export/records->fn-defs saved)
          reimported (parse/parse-module exported)
          type-row (first (filter #(= shape (:constraint %)) saved))
          slot (first (filter #(= :slot (:kind %)) saved))
          binding {:slot-id (:id slot) :type-override-fn-id (:id type-row)}
          decoded (tc/binding-shape-for-edn nil {(:id type-row) type-row} {} {} binding)
          wire (ta/json->type (json/parse-string (json/generate-string (:type decoded))))]
      (is (= rows saved))
      (is (= (set rows) (set reimported)))
      (is (= (set (map :id rows)) (set (map :id reimported))))
      (is (= {:type shape} decoded))
      (is (= shape wire))
      (is (= shape (tc/rich-type-from-row type-row {(:id type-row) type-row}))))))
