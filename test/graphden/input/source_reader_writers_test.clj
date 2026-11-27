(ns graphden.input.source-reader-writers-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.executor.compile.lookups :as lookups]
    [graphden.executor.compile.surface :as surface]
    [graphden.input.source-reader-writers :as writers]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]))


(defn- source-graph
  [extra]
  (let [definitions [{:name :pair :args {:left {:type :int} :right {:type :int}} :return-type :int}
                     {:name :value :args {:v {:type :int}} :return-type :int}
                     {:name :left-read :parent :value :args {:v {:as :input}}}
                     {:name :right-read :parent :value :args {:v {:as :input}}}
                     {:name :recipient :parent :pair :args {:left :left-read :right :right-read :input {:type :int}}}
                     {:name :producer :args {:other {:type :int}} :return-type :int}
                     {:name :bound :parent :recipient :args {:input :producer}}
                     {:name :wrapper :parent :bound :args {:other 1}}]
        prepared (parse/prepare-module (mapv #(assoc % :namespace "receipt") (into definitions extra)) {} {})
        rows (group-by :kind (parse/parse-prepared-module prepared))]
    (lookups/build-lookups {:fns (:fn rows) :slots (:slot rows) :fn-slots (:fn-slot rows)
                            :bindings (:binding rows) :list-items (:binding-list-item rows)})))


(deftest inherited-producer-requires-an-exclusive-writer-before-every-incoming-call
  (let [id #(ids/fn-id "receipt" %)
        owner (id :recipient)
        graph (source-graph [])
        incoming (writers/incoming-uses graph)
        slot (get-in graph [:slot-map (ids/slot-id owner :input)])
        targets (into #{} (keep #(when (= :input (:ext-name %)) (:slot-id %))) (surface/surface-entries owner graph))
        roots {(id :producer) {:fn-id (id :producer) :namespace "receipt" :name :producer :args {:other :int} :return :int}}
        check #(writers/inherited-writer? %1 (delay %2) roots owner :input slot targets)]
    (is (= 2 (count targets)))
    (is (check graph incoming))
    (doseq [bad [(assoc-in roots [(id :producer) :fn-id] (random-uuid))
                 (assoc-in roots [(id :producer) :args] {:input :int})
                 (assoc-in roots [(id :producer) :args] {:other :text})]]
      (is (not (writers/inherited-writer? graph (delay incoming) bad owner :input slot targets))))
    (is (not (check (assoc-in graph [:fn-map (id :producer) :parent-ids] [(id :value)]) incoming)))
    (is (not (check graph (update incoming owner conj {:kind :ref-fn-id :target owner :owner (id :wrapper)}))))
    (is (not (check graph (update incoming (id :wrapper) conj {:kind :resolver-fn-id :target (id :wrapper) :owner owner}))))
    (is (not (check graph (update incoming (id :bound) conj {:kind :list :target (id :bound) :owner (id :wrapper)}))))
    (let [exposed (source-graph [{:name :unbound :parent :recipient}])]
      (is (not (check exposed (writers/incoming-uses exposed)))))))
