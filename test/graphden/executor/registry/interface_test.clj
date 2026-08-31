(ns ^:serial graphden.executor.registry.interface-test
  "Unit tests for the registry façade — the parts that carry LOGIC:
   `initialize-with-base-fns!`'s name→id map assembly + init ordering +
   close-on-throw, `sync-defs-to-storage!`'s arity defaults, and the
   pure `compute-base-fns-map` / `fn-uuid` surface.

   `^:serial`: stubs the underlying `registry.core` / `packages.loader`
   Vars with `with-redefs` (process-wide root rebinds)."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.executor.registry.core :as core]
    [graphden.executor.registry.interface :as reg]
    [graphden.packages.loader :as pkg]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.sync :as pkg-sync]
    [graphden.storage.protocol.core :as sp]))


;; =============================================================================
;; Pure surface — fn-uuid, compute-base-fns-map
;; =============================================================================

(deftest fn-uuid-is-deterministic-and-namespace-less
  (is (uuid? (reg/fn-uuid :add)))
  (is (= (reg/fn-uuid :add) (reg/fn-uuid :add)) "same name → same UUID")
  (is (not= (reg/fn-uuid :add) (reg/fn-uuid :sub)))
  (is (= (ids/fn-id nil :add) (reg/fn-uuid :add))
      "façade UUID = records/fn-id with nil namespace — tests and
       production records must agree"))


(deftest compute-base-fns-map-is-pure-and-skips-impl-less-defs
  (let [impl (fn [x] x)
        m (reg/compute-base-fns-map
            {:with-impl {:impl impl :return-type :any}
             :no-impl-no-marker {:return-type :any}})]
    (is (= {:with-impl impl} m)
        "fn-defs with no impl AND no type marker are skipped")))


(deftest compute-base-fns-map-synthesises-type-row-impls
  (let [m (reg/compute-base-fns-map
            {:record-row {:type {:a :int}}
             :list-row {:list :int}})]
    (is (= #{:record-row :list-row} (set (keys m))))
    (is (every? fn? (vals m)) "type-rows get synthesised impls")))


;; =============================================================================
;; sync-defs-to-storage! — arity defaults
;; =============================================================================

(deftest sync-defs-arities-default-the-maps
  (let [calls (atom [])]
    (with-redefs [core/sync-defs-to-storage!
                  (fn [storage defs ns-id-map extra-name->id]
                    (swap! calls conj [storage defs ns-id-map extra-name->id])
                    ::synced)]
      (is (= ::synced (reg/sync-defs-to-storage! ::st {:a {}})))
      (is (= ::synced (reg/sync-defs-to-storage! ::st {:a {}} {"ns" ::id})))
      (is (= ::synced (reg/sync-defs-to-storage! ::st {:a {}} {} {:b ::bid}))))
    (is (= [[::st {:a {}} {} {}]
            [::st {:a {}} {"ns" ::id} {}]
            [::st {:a {}} {} {:b ::bid}]]
           @calls)
        "2- and 3-arity fill the missing ns-id-map / extra-name->id with {}")))


;; =============================================================================
;; initialize-with-base-fns! — ordering + the merged name→id map
;; =============================================================================

(def ^:private stub-packages
  {:base-fn-defs {:base-a {:namespace "core.demo" :impl identity}
                  :base-b {:impl identity}}
   :fn-defs [{:name :composed-c :namespace "app.demo" :parent :base-a}
             {:parent :base-b}]})  ; anonymous — must not enter name→id


(deftest initialize-with-base-fns-wires-the-full-name->id-map-in-order
  (let [calls (atom [])
        loaded (atom nil)]
    (with-redefs [pkg/load-packages (fn [names] (reset! loaded names) stub-packages)
                  core/sync-primitives! (fn [_] (swap! calls conj :primitives))
                  core/register-base-fns! (fn [_] (swap! calls conj :register))
                  pkg-sync/register-type-aliases! (fn [_] (swap! calls conj :aliases))
                  core/sync-defs-to-storage!
                  (fn [_ defs _ extra-name->id]
                    (swap! calls conj [:sync defs extra-name->id]))]
      (testing "default package set"
        (is (= ::storage (reg/initialize-with-base-fns! ::storage))
            "returns the storage")
        (is (= ["core" "web" "app"] @loaded)))
      (testing "primitives → register → type-aliases → base-fn sync, and the
                sync sees ids for BOTH base-fns and named composed fn-defs"
        (let [[a b c d] @calls]
          (is (= [:primitives :register :aliases] [a b c]))
          (is (= [:sync
                  (:base-fn-defs stub-packages)
                  {:base-a (ids/fn-id "core.demo" :base-a)
                   :base-b (ids/fn-id nil :base-b)
                   :composed-c (ids/fn-id "app.demo" :composed-c)}]
                 d)
              "deterministic ids keyed by each def's own namespace;
               the anonymous fn-def contributes nothing"))))))


(deftest initialize-with-base-fns-closes-storage-on-failure
  (let [closed (atom 0)
        storage #_{:clj-kondo/ignore [:missing-protocol-method]}
        (reify sp/Storage
          (close [_] (swap! closed inc)))]
    (with-redefs [pkg/load-packages (fn [_] stub-packages)
                  core/sync-primitives! (fn [_] nil)
                  core/register-base-fns! (fn [_] nil)
                  pkg-sync/register-type-aliases! (fn [_] nil)
                  core/sync-defs-to-storage!
                  (fn [& _] (throw (ex-info "sync blew up" {:type ::boom})))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"sync blew up"
            (reg/initialize-with-base-fns! storage ["core"]))
          "the original exception is rethrown"))
    (is (= 1 @closed) "the half-initialised storage is closed")))


(deftest initialize-all-syncs-each-def-set
  (let [calls (atom [])]
    (with-redefs [core/sync-primitives! (fn [_] (swap! calls conj :primitives))
                  core/register-base-fns! (fn [defs] (swap! calls conj [:register defs]))
                  core/sync-defs-to-storage!
                  (fn [_ defs ns-id-map extra] (swap! calls conj [:sync defs ns-id-map extra]))]
      (is (= ::storage (reg/initialize-all! ::storage [{:a {}} {:b {}}]))))
    (is (= [:primitives
            [:register {:a {}}] [:sync {:a {}} {} {}]
            [:register {:b {}}] [:sync {:b {}} {} {}]]
           @calls)
        "primitives once, then register+sync per def set, in order")))
