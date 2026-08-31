(ns graphden.layout.strip-facts-test
  "Pure unit tests for `graphden.layout.strip-facts/annotate` — the
   server-computed bottom-of-card strip facts (`:returnTypeAlias` /
   `:ruleOwner` / `:branchLocal`). The golden-graph integration test
   (`graphden.packages.app.layout-strip-facts-test`) proves the wiring
   end-to-end; these tests pin the WALK semantics on literal fn maps:
   BFS inheritance of the return-type alias, the documented
   stop-at-first-hit MI edge, primitive/unnamed suppression, own vs
   inherited branch-local seed attribution, cycle termination, and the
   pass-through contract for non-fn / unknown nodes."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.executor.registry.core :as registry]
    [graphden.layout.strip-facts :as sf]))


(defn- fn-node
  [fn-id]
  {:data {:id (str "fn-" fn-id) :type "fn" :originalFnId (str fn-id)}})


(defn- annotate-data
  "Annotate a single fn-node against `fns` and return its `:data`."
  [fns fn-id]
  (-> (sf/annotate {:nodes [(fn-node fn-id)] :edges []} {:fns fns})
      :nodes
      first
      :data))


;; ============================================================================
;; :returnTypeAlias
;; ============================================================================

(deftest own-return-type-alias-test
  (testing "a fn's own :return-type-fn-id resolving to a named non-primitive
            type-row surfaces as :returnTypeAlias"
    (let [f (random-uuid)
          alias-row (random-uuid)
          fns [{:id f :name "sf-mk-port" :parent-ids []
                :return-type-fn-id alias-row}
               {:id alias-row :name "sf-port" :parent-ids []}]]
      (is (= "sf-port" (:returnTypeAlias (annotate-data fns f)))))))


(deftest inherited-return-type-alias-test
  (testing "a composed fn with no own return type inherits the alias from
            its parent via the :parent-ids BFS"
    (let [child (random-uuid)
          parent (random-uuid)
          alias-row (random-uuid)
          fns [{:id child :name "sf-child" :parent-ids [parent]}
               {:id parent :name "sf-parent" :parent-ids []
                :return-type-fn-id alias-row}
               {:id alias-row :name "sf-handle" :parent-ids []}]]
      (is (= "sf-handle" (:returnTypeAlias (annotate-data fns child)))))))


(deftest primitive-and-unnamed-alias-suppressed-test
  (testing "a return type resolving to a bare primitive row carries no alias
            (the structural form is at least as informative)"
    (let [f (random-uuid)
          int-row (random-uuid)
          fns [{:id f :name "sf-prim" :parent-ids []
                :return-type-fn-id int-row}
               {:id int-row :name "int" :parent-ids []}]]
      (is (not (contains? (annotate-data fns f) :returnTypeAlias)))))
  (testing "a return type resolving to an UNNAMED row carries no alias"
    (let [f (random-uuid)
          anon-row (random-uuid)
          fns [{:id f :name "sf-anon-ret" :parent-ids []
                :return-type-fn-id anon-row}
               {:id anon-row :name nil :parent-ids []}]]
      (is (not (contains? (annotate-data fns f) :returnTypeAlias))))))


(deftest alias-walk-stops-at-first-return-type-test
  (testing "the BFS stops at the FIRST :return-type-fn-id found — a primary
            parent declaring a primitive return hides a secondary parent's
            named alias (documented MI edge, matches old client behaviour)"
    (let [child (random-uuid)
          p1 (random-uuid)
          p2 (random-uuid)
          int-row (random-uuid)
          alias-row (random-uuid)
          fns [{:id child :name "sf-mi" :parent-ids [p1 p2]}
               {:id p1 :name "sf-mi-p1" :parent-ids []
                :return-type-fn-id int-row}
               {:id p2 :name "sf-mi-p2" :parent-ids []
                :return-type-fn-id alias-row}
               {:id int-row :name "int" :parent-ids []}
               {:id alias-row :name "sf-mi-alias" :parent-ids []}]]
      (is (not (contains? (annotate-data fns child) :returnTypeAlias))))))


(deftest alias-walk-terminates-on-parent-cycle-test
  (testing "a :parent-ids cycle with no return type anywhere terminates and
            yields no facts (imported/corrupt data must not hang layout)"
    (let [a (random-uuid)
          b (random-uuid)
          fns [{:id a :name "sf-cyc-a" :parent-ids [b]}
               {:id b :name "sf-cyc-b" :parent-ids [a]}]
          data (annotate-data fns a)]
      (is (not (contains? data :returnTypeAlias)))
      (is (not (contains? data :branchLocal))))))


;; ============================================================================
;; :branchLocal
;; ============================================================================

(deftest branch-local-own-seed-test
  (testing "a fn carrying :branch-local? true itself is its own seed"
    (let [f (random-uuid)
          fns [{:id f :name "sf-bl-own" :parent-ids [] :branch-local? true}]]
      (is (= {:own true :seed "sf-bl-own"}
             (:branchLocal (annotate-data fns f)))))))


(deftest branch-local-inherited-seed-test
  (testing "a descendant of a branch-local ancestor carries {:own false}
            with the ANCESTOR's name as the seed"
    (let [child (random-uuid)
          mid (random-uuid)
          seed (random-uuid)
          fns [{:id child :name "sf-bl-child" :parent-ids [mid]}
               {:id mid :name "sf-bl-mid" :parent-ids [seed]}
               {:id seed :name "sf-bl-seed" :parent-ids [] :branch-local? true}]]
      (is (= {:own false :seed "sf-bl-seed"}
             (:branchLocal (annotate-data fns child)))))))


(deftest branch-local-absent-without-seed-test
  (testing "no :branchLocal fact when the closure carries no seed"
    (let [child (random-uuid)
          parent (random-uuid)
          fns [{:id child :name "sf-bl-none" :parent-ids [parent]}
               {:id parent :name "sf-bl-none-p" :parent-ids []}]]
      (is (not (contains? (annotate-data fns child) :branchLocal))))))


;; ============================================================================
;; :ruleOwner
;; ============================================================================

(deftest rule-owner-fact-test
  (testing "a fn whose primary-parent chain roots at a base-fn carrying a
            :return-type-rule gets :ruleOwner = that base-fn's name"
    (binding [registry/*rich-types-override*
              (atom {:by-id {1 {:name :sf-rule-fn :primary-parent :sf-rule-base}
                             2 {:name :sf-rule-base
                                :return-type-rule (fn [& _] :int)}}
                     :by-name {:sf-rule-fn 1 :sf-rule-base 2}})]
      (let [f (random-uuid)
            fns [{:id f :name "sf-rule-fn" :parent-ids []}]]
        (is (= "sf-rule-base" (:ruleOwner (annotate-data fns f)))))))
  (testing "no :ruleOwner when the chain's root carries neither a rule nor a
            var-carrying signature (a fully concrete declaration)"
    (binding [registry/*rich-types-override*
              (atom {:by-id {1 {:name :sf-plain-fn :primary-parent :sf-plain-base}
                             2 {:name :sf-plain-base :return :int}}
                     :by-name {:sf-plain-fn 1 :sf-plain-base 2}})]
      (let [f (random-uuid)
            fns [{:id f :name "sf-plain-fn" :parent-ids []}]]
        (is (not (contains? (annotate-data fns f) :ruleOwner)))))))


;; ============================================================================
;; Pass-through contract
;; ============================================================================

(deftest non-fn-and-unknown-nodes-pass-through-test
  (let [known (random-uuid)
        fns [{:id known :name "sf-known" :parent-ids [] :branch-local? true}]
        arg-node {:data {:id "arg-1" :type "arg" :label "5"}}
        unknown-node (fn-node (random-uuid))
        edge {:data {:id "e-1" :source "fn-a" :target "arg-1"}}
        result (sf/annotate {:nodes [arg-node unknown-node (fn-node known)]
                             :edges [edge]}
                            {:fns fns})]
    (testing "non-fn nodes and unknown fn-ids are untouched"
      (is (= arg-node (first (:nodes result))))
      (is (= unknown-node (second (:nodes result)))))
    (testing "known fn-node still gets its facts in the same pass"
      (is (= {:own true :seed "sf-known"}
             (get-in (nth (:nodes result) 2) [:data :branchLocal]))))
    (testing "edges pass through untouched"
      (is (= [edge] (:edges result))))))
