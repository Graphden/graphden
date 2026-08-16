(ns graphden.packages.sync-test
  "Unit tests for the package → storage sync guards that don't need a
   live DB — chiefly `validate-no-name-collisions!` (per-ns namesake
   legality + the base-fn cross-namespace clobber guard)."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.packages.sync :as sync]))


(def ^:private validate!
  #'sync/validate-no-name-collisions!)


;; =============================================================================
;; validate-no-name-collisions!
;; =============================================================================

(deftest per-ns-namesake-composed-defs-are-legal
  (testing "two composed fn-defs sharing a bare name in DIFFERENT
            namespaces sync cleanly — the (namespace, name) pair is the
            identity, so distinct namespaces are distinct fns
            (ADR-identity-model stage 5)"
    (is (nil? (validate!
                {:base-fn-pairs []
                 :base-fn-defs {}
                 :fn-defs [{:name :handler :namespace "a" :parent :x}
                           {:name :handler :namespace "b" :parent :y}]})))))


(deftest same-ns-name-pair-collision-throws
  (testing "the SAME (namespace, name) pair on two composed fn-defs is a
            silent-overwrite hazard — rejected"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Colliding \(namespace, name\) pairs"
          (validate!
            {:base-fn-pairs []
             :base-fn-defs {}
             :fn-defs [{:name :dup :namespace "a" :parent :x}
                       {:name :dup :namespace "a" :parent :y}]})))))


(deftest base-fn-namesakes-across-namespaces-throw
  (testing "base-fn bare names must be globally unique (the impls
            registry is name-keyed). The guard was DEAD when it read the
            bare-name-keyed `:base-fn-defs` map — the collision already
            collapsed to one entry upstream. It reads the loader's
            uncollapsed `:base-fn-pairs` now, so the clobber is caught."
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Colliding BASE-FN names across namespaces"
          (validate!
            {:base-fn-pairs [["a" :emit] ["b" :emit]]
             ;; the map already collapsed the two same-named base-fns —
             ;; deriving the check from it alone would miss the clash.
             :base-fn-defs {:emit {:namespace "b"}}
             :fn-defs []})))))


(deftest fallback-to-map-when-no-base-fn-pairs
  (testing "hand-built package maps (tests / registry) without a
            `:base-fn-pairs` key fall back to the map-derived pairs and
            still validate a single base-fn cleanly"
    (is (nil? (validate!
                {:base-fn-defs {:add {:namespace "core.arithmetic"}}
                 :fn-defs []})))))
