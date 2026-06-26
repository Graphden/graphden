(ns graphden.packages.export-test
  "Round-trip tests for the graph → fns.edn exporter.

   Two layers:
   1. `roundtrip-*` — hand-written fixtures covering every role +
      binding shape, asserting EXACT records-level round-trip
      (`parse(export(parse(fns))) == parse(fns)`).
   2. `corpus-fixpoint` — loads the real core/web/app packages and
      asserts the exporter reaches a stable fixpoint (the property
      publish / install relies on)."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.composition.deps :as deps]
    [graphden.packages.export :as export]
    [graphden.packages.loader :as loader]
    [graphden.packages.records.parse :as parse]
    [graphden.types.core :as types]))


;; Aliases registry is process-global; clear around these tests so a
;; sibling ns that registers aliases can't perturb resolution (parse
;; itself doesn't read the registry, but keep the ns hermetic).
(use-fixtures :each
  (fn [t] (types/clear-aliases!) (t) (types/clear-aliases!)))


(defn- norm
  "Records as an order-insensitive, key-order-insensitive set."
  [records]
  (set (map #(into (sorted-map) %) records)))


(defn- roundtrips-exactly?
  "True iff `fns` survives parse → export → parse with identical
   records."
  [fns]
  (= (norm (parse/parse-module fns))
     (norm (parse/parse-module (export/records->fn-defs (parse/parse-module fns))))))


(defn- diff-report
  [fns]
  (let [a (norm (parse/parse-module fns))
        b (norm (parse/parse-module (export/records->fn-defs (parse/parse-module fns))))]
    {:only-orig (remove b a) :only-rt (remove a b)}))


;; =============================================================================
;; Type-row roles
;; =============================================================================

(deftest roundtrip-type-rows
  (let [fns [{:name :user-shape :namespace "ex" :type {:nm :text :age :int} :description "u"}
             {:name :pos-int :namespace "ex" :refine {:base :int :constraint [:> 0]}}
             {:name :int-list :namespace "ex" :list :int}
             {:name :str-or-int :namespace "ex" :union [:text :int]}
             {:name :smap :namespace "ex" :map {:key :text :value :int}}
             {:name :pair :namespace "ex" :tuple [:text :int]}
             {:name :result :namespace "ex" :variant [:ok :int :err :text]}
             {:name :handler-t :namespace "ex" :fn-type [{:request :int} :text]}]]
    (is (roundtrips-exactly? fns) (pr-str (diff-report fns)))))


;; =============================================================================
;; Base-fns + composed binding shapes
;; =============================================================================

(deftest roundtrip-base-and-composed
  (let [fns [{:name :pos-int :namespace "ex" :refine {:base :int :constraint [:> 0]}}
             {:name :resp :namespace "ex" :type {:status :int :body :text}}
             {:name :add :namespace "ex"
              :args {:a :int :b {:type :int :required false}} :return-type :int}
             {:name :collect :namespace "ex" :args {:items :sequence} :return-type :sequence}
             {:name :sink :namespace "ex" :args {:data :jsonb} :return-type :any}
             ;; literal value + ref + required-narrow
             {:name :add-10 :namespace "ex" :parent :add :args {:a {:value 10} :b :pos-int}}
             ;; PB' own-slot
             {:name :tmpl :namespace "ex" :parent :add :args {:a 1 :extra {:type :jsonb}}}
             ;; list-append: bare (closed nil) / closed true / not-closed
             {:name :seed-open :namespace "ex" :parent :collect :args {:items [1 2 3]}}
             {:name :seed-closed :namespace "ex" :parent :collect :args {:items {:append [1 2] :closed true}}}
             {:name :seed-nc :namespace "ex" :parent :collect :args {:items {:append [9]}}}
             ;; type-override on a value binding
             {:name :over :namespace "ex" :parent :sink :args {:data {:value {"k" 1} :type :resp}}}
             ;; multi-parent
             {:name :multi :namespace "ex" :parents [:collect :sink] :args {:items [7] :data {"a" 1}}}]]
    (is (roundtrips-exactly? fns) (pr-str (diff-report fns)))))


;; =============================================================================
;; Renames (scalar / no-op / positional) + type-override-only bindings
;; =============================================================================

(deftest roundtrip-renames
  (let [fns [{:name :resp :namespace "ex" :type {:status :int :body :text}}
             ;; scalar rename with explicit type
             {:name :resp-ok :namespace "ex" :parent :resp
              :args {:body {:as :text-out :type :text} :status 200}}
             ;; no-op rename re-exposing a free arg (empty binding) +
             ;; type-override-only binding (must stay a binding, not a
             ;; PB' own-slot)
             {:name :resp-passthrough :namespace "ex" :parent :resp
              :args {:status {:as :status} :body {:as :body :type :text}}}
             ;; positional rename inside a list binding
             {:name :catfn :namespace "ex" :args {:colls :sequence} :return-type :sequence}
             {:name :cat-scripts :namespace "ex" :parent :catfn
              :args {:colls [{:as :scripts :type :sequence}]}}]]
    (is (roundtrips-exactly? fns) (pr-str (diff-report fns)))))


;; =============================================================================
;; Inline structural / composite types + effects metadata
;; =============================================================================

(deftest roundtrip-inline-types-and-meta
  (let [fns [;; inline [:fn args ret] as a slot type (kept in :constraint)
             {:name :invoke :namespace "ex" :args {:f [:fn {:x :int} :int] :x :int} :return-type :int}
             {:name :double :namespace "ex" :parent :invoke :args {:x 2}}
             {:name :wrap :namespace "ex" :parent :invoke :args {:f :double :x 5}}
             ;; inline composite record as a slot type
             {:name :rec-slot :namespace "ex" :args {:cfg {:host :text :port :int}} :return-type :any}
             ;; effects + branch-local pass-through
             {:name :svc :namespace "ex" :args {:p :int} :return-type :any
              :expects-effects [:network :io] :branch-local? true}]]
    (is (roundtrips-exactly? fns) (pr-str (diff-report fns)))))


;; =============================================================================
;; Corpus fixpoint — the publish / install guarantee
;; =============================================================================

(deftest corpus-fixpoint
  (let [packages (loader/load-packages ["core" "web" "app"])
        all-defs (vec (concat (map (fn [[nm d]] (assoc d :name nm)) (:base-fn-defs packages))
                              (:fn-defs packages)))
        sorted (deps/topological-sort all-defs)
        recs1 (parse/parse-module sorted)
        out1  (export/records->fn-defs recs1)
        recs2 (parse/parse-module out1)
        out2  (export/records->fn-defs recs2)
        recs3 (parse/parse-module out2)]
    (testing "round-trip preserves the record count (nothing gained/lost)"
      (is (= (count recs1) (count recs2)))
      (is (= (count recs2) (count recs3))))
    (testing "exporter is a fixpoint after the first round"
      (is (= (norm recs2) (norm recs3))
          "second round-trip must be bit-identical to the first")
      (is (= (set out1) (set out2))
          "exported EDN must be stable"))
    (testing "first-round normalisation stays within the documented tail"
      ;; Behaviour-preserving drift (anon-composite identity + HOF
      ;; owner-disambiguation) only; guard against silent ballooning.
      (let [diff (count (remove (norm recs2) (norm recs1)))]
        (is (< diff (* 0.05 (count recs1)))
            (str "first-round diff " diff " exceeded 5% — investigate a regression"))))))
