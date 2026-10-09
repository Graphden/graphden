(ns ^:serial graphden.executor.delete-delta-test
  "Deletion-only invalidation: structural work bounds, not timing assertions.
   Serial because fallback instrumentation temporarily rebinds compiler vars."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.executor.compile-eager :as ce]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.compile.deps :as deps]
    [graphden.executor.context :as ctx]
    [graphden.packages.owned :as owned]))


(defn- graph
  [fns]
  {:fns fns :slots [] :fn-slots [] :bindings [] :list-items []})


(defn- fixture
  [before after]
  (let [survivor (fn [] 42)]
    {:context {:graph-cache (atom after)
               :compiled-registry (atom {:gone (fn [] :deleted) :stay survivor})
               :compile-deps (atom (deps/build-deps-state before))}
     :survivor survivor}))


(deftest deleted-composed-leaf-skips-global-preparation
  (let [before (graph [{:id :gone :parent-ids [:base]} {:id :stay}])
        after (graph [{:id :stay}])
        {:keys [context survivor]} (fixture before after)]
    (with-redefs-fn
      {#'cr/prep-compile-inputs (fn [& _] (throw (ex-info "whole graph prep forbidden" {})))}
      #(cr/delta-recompile! context #{:gone} before))
    (is (= #{:stay} (set (keys @(:compiled-registry context)))))
    (is (identical? survivor (get @(:compiled-registry context) :stay)))
    (is (= 42 ((get @(:compiled-registry context) :stay))))
    (is (= {:forward-deps {:stay #{}} :reverse-deps {}} @(:compile-deps context)))
    (is (identical? after @(:graph-cache context)))))


(deftest deletion-fast-path-is-conservative
  (let [gone {:id :gone :parent-ids [:base]}
        plain (graph [gone {:id :stay}])
        deleted (graph [{:id :stay}])
        typed (assoc plain :slots [{:id :slot :type-fn-id :gone}]
                     :fn-slots [{:id :junction :fn-id :stay :slot-id :slot}])
        resolver (assoc plain :bindings [{:id :binding :fn-id :stay :resolver-fn-id :gone}])]
    (doseq [[label before after prior package?]
            [["type row" (graph [{:id :gone :element-fn-id :base} {:id :stay}]) deleted true false]
             ["typed dependent" typed (update typed :fns #(filterv (fn [f] (not= :gone (:id f))) %)) true false]
             ["resolver dependent" resolver (update resolver :fns #(filterv (fn [f] (not= :gone (:id f))) %)) true false]
             ["sibling override survives" plain plain true false]
             ["already deleted dependent in old blast"
              (graph [gone {:id :stay} {:id :dead-caller :parent-ids [:gone]}])
              deleted true false]
             ["no authoritative previous cache" plain deleted false false]
             ["package row" plain deleted true true]]]
      (testing label
        (let [{:keys [context]} (fixture before after)
              prepared (atom 0)
              compiled (atom 0)]
          (with-redefs-fn
            {#'cr/prep-compile-inputs (fn [_ g]
                                        (swap! prepared inc)
                                        {:graph g :fns-map (into {} (map (juxt :id identity)) (:fns g))
                                         :lookups {:fn-map (into {} (map (juxt :id identity)) (:fns g))}})
             #'ce/compile-subset (fn [_ registry _] (swap! compiled inc) registry)
             #'owned/owned-fn-id? (constantly package?)}
            #(cr/delta-recompile! context #{:gone} (when prior before)))
          (is (= 1 @prepared))
          (is (= 1 @compiled)))))))


(deftest cold-deletion-keeps-rebuild-fallback
  (let [rebuilt (atom 0)
        context {:compiled-registry (atom nil) :compile-deps (atom nil)}]
    (with-redefs [cr/rebuild! (fn [_] (swap! rebuilt inc) {:rebuilt true})]
      (is (= {:rebuilt true} (cr/delta-recompile! context #{:gone} (graph []))))
      (is (= 1 @rebuilt)))))


(deftest invalidation-passes-this-contexts-pre-splice-graph
  (let [before (graph [{:id :gone :parent-ids [:base]}])
        after (graph [])
        context {:storage :storage :graph-cache (atom before)
                 :compiled-registry (atom {}) :compile-deps (atom {})}
        observed (atom nil)]
    (with-redefs-fn
      {#'ctx/splice-graph-cache! (fn [c _] (reset! (:graph-cache c) after) true)
       #'cr/delta-recompile! (fn [c seeds prior]
                               (reset! observed [seeds prior @(:graph-cache c)]))}
      #(ctx/invalidate-graph-cache! context #{:gone}))
    (is (= [#{:gone} before after] @observed))))
