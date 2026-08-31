(ns graphden.packages.sync-test
  "Unit tests for the pure package → storage sync helpers that don't
   need a live DB — `validate-no-name-collisions!` (per-ns namesake
   legality + the base-fn cross-namespace clobber guard),
   `compute-all-fn-name-ids` (dual keying + the ambiguous-name
   sentinel), `drop-orphan-anon-defs` (orphan reachability), and
   `validate-route-handler-shapes!` (the bare-route wire guard)."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.packages.records :as records]
    [graphden.packages.records.parse :as records-parse]
    [graphden.packages.sync :as sync]))


(def ^:private validate!
  #'sync/validate-no-name-collisions!)


(def ^:private validate-routes!
  #'sync/validate-route-handler-shapes!)


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


;; =============================================================================
;; compute-all-fn-name-ids
;; =============================================================================

(deftest name-id-map-is-dual-keyed
  (testing "every named def lands under its bare name AND its qualified
            spelling, both mapping to the deterministic (ns, name) id;
            base-fns and composed fn-defs feed the same map"
    (let [m (sync/compute-all-fn-name-ids
              {:base-fn-defs {:add {:namespace "core.arithmetic"}}
               :fn-defs [{:name :upper :namespace "core.strings"}]})]
      (is (= (records/fn-id "core.arithmetic" :add) (get m :add)))
      (is (= (records/fn-id "core.arithmetic" :add) (get m :core.arithmetic/add)))
      (is (= (records/fn-id "core.strings" :upper) (get m :upper)))
      (is (= (records/fn-id "core.strings" :upper) (get m :core.strings/upper))))))


(deftest ambiguous-bare-name-maps-to-sentinel
  (testing "a bare name claimed by two different (ns, name) identities
            maps to the ambiguous-name sentinel — never silently the
            last-write id — while the qualified keys stay precise"
    (let [m (sync/compute-all-fn-name-ids
              {:base-fn-defs {}
               :fn-defs [{:name :handler :namespace "a"}
                         {:name :handler :namespace "b"}]})]
      (is (= records-parse/ambiguous-name (get m :handler)))
      (is (= (records/fn-id "a" :handler) (get m :a/handler)))
      (is (= (records/fn-id "b" :handler) (get m :b/handler))))))


(deftest same-identity-twice-is-not-ambiguous
  (testing "seeing the SAME (ns, name) identity again (same
            deterministic id) keeps the bare mapping — only genuinely
            different identities trip the sentinel"
    (let [m (sync/compute-all-fn-name-ids
              {:base-fn-defs {:emit {:namespace "a"}}
               :fn-defs [{:name :emit :namespace "a"}]})]
      (is (= (records/fn-id "a" :emit) (get m :emit))))))


(deftest anonymous-defs-are-excluded-from-name-ids
  (testing "defs without a :name contribute nothing"
    (is (= {} (sync/compute-all-fn-name-ids
                {:base-fn-defs {}
                 :fn-defs [{:namespace "a" :parent :x}]})))))


;; =============================================================================
;; drop-orphan-anon-defs
;; =============================================================================

(deftest orphan-anons-dropped-reachable-kept
  (testing "anons a kept def reaches — directly or through other kept
            anons — stay; pure orphans are stripped"
    (let [defs [{:name :root :namespace "a" :parent :x
                 :args {:handler :_anon-aaa}}
                {:name :_anon-aaa :parent :y :args {:inner :_anon-bbb}}
                {:name :_anon-bbb :parent :z}
                {:name :_anon-orphan :parent :w}]]
      (is (= [:root :_anon-aaa :_anon-bbb]
             (mapv :name (sync/drop-orphan-anon-defs defs)))))))


(deftest orphan-anon-cycle-is-dropped
  (testing "two anons referencing only each other are unreachable from
            any root and both get stripped"
    (let [defs [{:name :root :namespace "a" :parent :x :args {}}
                {:name :_anon-a :parent :y :args {:next :_anon-b}}
                {:name :_anon-b :parent :z :args {:next :_anon-a}}]]
      (is (= [:root] (mapv :name (sync/drop-orphan-anon-defs defs)))))))


(deftest anon-refs-found-in-every-ref-position
  (testing "references are recognised deep inside :args and via
            :parent / :parents / :return-type — including
            namespace-qualified spellings"
    (let [defs [{:name :r1 :namespace "a"
                 :args {:routes [[{:h :_anon-deep}]]}}
                {:name :_anon-deep :parent :x}
                {:name :r2 :namespace "a" :parent :_anon-parent}
                {:name :_anon-parent :parent :y}
                {:name :r3 :namespace "a" :parents [:_anon-mi]}
                {:name :_anon-mi :parent :z}
                {:name :r4 :namespace "a" :return-type :some.ns/_anon-ret}
                {:name :_anon-ret :parent :w}]]
      (is (= [:r1 :_anon-deep :r2 :_anon-parent :r3 :_anon-mi :r4 :_anon-ret]
             (mapv :name (sync/drop-orphan-anon-defs defs)))))))


(deftest non-anon-defs-always-survive
  (testing "named (non-anon) defs are never dropped, referenced or not"
    (let [defs [{:name :lonely :namespace "a" :parent :x}]]
      (is (= defs (sync/drop-orphan-anon-defs defs))))))


;; =============================================================================
;; validate-route-handler-shapes!
;; =============================================================================

(defn- routes-pkg
  [& fn-defs]
  {:fn-defs (vec fn-defs)})


(deftest bare-route-with-bad-handler-shape-throws
  (testing "a :get-route handler declaring :lambda-params other than
            [] / [:request] mis-binds the raw ring request — sync fails
            loud with the offender listed"
    (let [ex (try (validate-routes!
                    (routes-pkg
                      {:name :bad-route :namespace "a" :parent :get-route
                       :args {:handler :bad-handler}}
                      {:name :bad-handler :namespace "a"
                       :lambda-params [:request :body]}))
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex))
      (is (= :packages/route-handler-shape (:type (ex-data ex))))
      (is (= [{:route :bad-route :handler :bad-handler
               :lambda-params [:request :body]}]
             (:offenders (ex-data ex)))))))


(deftest bare-route-valid-handler-shapes-pass
  (testing "[] and [:request] (incl. the stored string form) and nil
            (derived params) all pass; the handler may be referenced
            as a bare keyword or a {:ref …} map"
    (is (nil? (validate-routes!
                (routes-pkg
                  {:name :r1 :namespace "a" :parent :get-route
                   :args {:handler :h1}}
                  {:name :h1 :namespace "a" :lambda-params []}
                  {:name :r2 :namespace "a" :parent :post-route
                   :args {:handler {:ref :h2}}}
                  {:name :h2 :namespace "a" :lambda-params ["request"]}
                  {:name :r3 :namespace "a" :parents [:get-route]
                   :args {:handler :h3}}
                  {:name :h3 :namespace "a"}))))))


(deftest qualified-route-parent-only-counts-for-owning-module
  (testing "a parent qualified with the owning module counts as bare; a
            same-named parent from another namespace does not"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Bare-route handlers"
          (validate-routes!
            (routes-pkg
              {:name :r :namespace "a"
               :parent :app.routes.method/get-route
               :args {:handler :h}}
              {:name :h :namespace "a" :lambda-params [:body]}))))
    (is (nil? (validate-routes!
                (routes-pkg
                  {:name :r :namespace "a"
                   :parent :other.ns/get-route
                   :args {:handler :h}}
                  {:name :h :namespace "a" :lambda-params [:body]}))))))


(deftest middlewared-routes-are-not-checked
  (testing "the middlewared family tolerates wider handler shapes —
            deliberately outside the guard"
    (is (nil? (validate-routes!
                (routes-pkg
                  {:name :r :namespace "a" :parent :post
                   :args {:handler :h}}
                  {:name :h :namespace "a"
                   :lambda-params [:request :body]}))))))
