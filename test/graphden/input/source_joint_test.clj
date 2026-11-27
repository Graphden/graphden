(ns graphden.input.source-joint-test
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is]]
    [graphden.input.source :as source]
    [graphden.input.source-joint :as joint]
    [graphden.packages.records.parse :as parse]
    [graphden.types.core :as types]
    [graphden.types.core.shapes :as shapes]))


(defn- read-module
  [path]
  (let [{ns-id :namespace :keys [fns]} (edn/read-string (slurp (io/resource path)))]
    (mapv #(assoc % :namespace ns-id) fns)))


(defn- code
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error (:code (ex-data error)))))


(deftest joint-groups-never-drop-a-third-blocker-or-nested-owner
  (let [base {:blockers [{:code :field-projection-required :source-path [:args :data]}
                         {:code :ref-scope-required :source-path [:args :string]}]}]
    (is (= {:fields [:data] :paths [[:args :string]]} (joint/groups base)))
    (doseq [entry [(update base :blockers conj {:code :inherited-occupancy-required :source-path [:args :data]})
                   (assoc-in base [:blockers 1 :source-path] [:args :body :args :string])
                   (assoc-in base [:blockers 1 :source-path] [:args :data])]]
      (is (= :joint-source-mismatch (code #(joint/groups entry)))))))


(deftest actual-ring-owner-retains-complete-callable-and-map-reference-origins
  (binding [types/*type-aliases-override* (atom {})
            shapes/*marker-registry-override* (atom {:secret {:monotone? true :hide-result? true}})]
    (let [core (mapcat #(read-module (str "packages/core/" % "/fns.edn"))
                       (:modules (edn/read-string (slurp (io/resource "packages/core/package.edn")))))
          web (read-module "packages/web/ring-adapter/fns.edn")
          original (first (filter #(= :router-ring-response (:name %)) web))
          definitions (into (vec core) web)
          prepared (parse/prepare-module definitions {} {})
          context (assoc prepared :fn-defs (filterv #(= :router-ring-response (:name %)) (:fn-defs prepared)))
          entry (first (:definitions (source/plan-module [original] context)))
          baseline-entry (update original :args dissoc :defaults)
          reduced-prepared (parse/prepare-module (mapv #(if (= original %) baseline-entry %) definitions) {} {})
          reduced-context (assoc reduced-prepared :fn-defs (filterv #(= :router-ring-response (:name %)) (:fn-defs reduced-prepared)))
          reduced-entry (first (:definitions (source/plan-module [baseline-entry] reduced-context)))]
      (is (= {:fields [:func] :paths [[:args :defaults]]} (joint/groups entry)))
      (is (= :fn (first (get-in original [:args :func :type]))))
      (is (nil?
            (code #(joint/check-full-reference-origins! prepared entry reduced-prepared reduced-entry)))))))


(deftest omitted-scoped-ref-cannot-hide-a-second-forwarding-occurrence
  (let [definitions (mapv #(assoc % :namespace "joint-origins")
                          [{:name :sink :args {:a :text :b :text} :return-type :text}
                           {:name :reader-a :args {:data :text} :return-type :text}
                           {:name :reader-b :args {:string :text} :return-type :text}
                           {:name :producer :args {:data :text} :return-type :text}
                           {:name :host :parent :sink
                            :args {:a :reader-a :b :reader-b :data {:type :text} :string :producer}}])
        prepared (parse/prepare-module definitions {} {})
        entry (last (:definitions (source/plan-module definitions prepared)))
        baseline-entry (update-in definitions [4 :args] dissoc :string)
        reduced-prepared (parse/prepare-module baseline-entry {} {})
        reduced-entry (last (:definitions (source/plan-module baseline-entry reduced-prepared)))]
    (is (= {:fields [:data] :paths [[:args :string]]} (joint/groups entry)))
    (is (= [:field-projection-required] (mapv :code (:blockers reduced-entry))))
    (is (= :nonunique-reference-use
           (code #(joint/check-full-reference-origins! prepared entry reduced-prepared reduced-entry))))))


(deftest joint-forwarding-keeps-both-authored-occurrences-of-the-same-slot
  (let [definitions (mapv #(assoc % :namespace "joint-fanout")
                          [{:name :sink :args {:a :text :b :text :c :text} :return-type :text}
                           {:name :reader :args {:data :text} :return-type :text}
                           {:name :left :parent :reader :return-type :text}
                           {:name :right :parent :reader :return-type :text}
                           {:name :third :args {:string :text} :return-type :text}
                           {:name :host :parent :sink
                            :args {:a :left :b :right :c :third
                                   :data {:type :text} :string {:value "label"}}}])
        prepared (parse/prepare-module definitions {} {})
        original (peek (:definitions (source/plan-module definitions prepared)))
        reduced (update-in definitions [5 :args] dissoc :string)
        reduced-prepared (parse/prepare-module reduced {} {})
        reduced-entry (peek (:definitions (source/plan-module reduced reduced-prepared)))]
    (is (= {:fields [:data] :paths [[:args :string]]} (joint/groups original)))
    (is (nil? (code #(joint/check-full-reference-origins! prepared original reduced-prepared reduced-entry))))))
