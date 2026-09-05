(ns graphden.executor.fn-ref-test
  "The `:fn-ref` primitive end-to-end through the executor: a ref bound
   into a `:fn-ref`-typed slot hands the impl the TARGET'S ID (never a
   callable, never the target's value — the target is not evaluated),
   is not an evaluation dependency (two fns may name each other, at
   write time AND at compile time), and reaches the impl through an
   env-binding exactly like a root-slot binding."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.compile.deps :as deps]
    [graphden.executor.interface :as exec]
    [graphden.executor.runtime :as rt]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]))


(use-fixtures :once
  (setup/create-container-fixture)
  exec/with-clean-registry
  exec/with-isolated-rich-types)


(defn- graph-rows
  [storage]
  {:fns (sp/query-entities storage :fn {})
   :slots (sp/query-entities storage :slot {})
   :fn-slots (sp/query-entities storage :fn-slot {})
   :bindings (sp/query-entities storage :binding {})
   :list-items (sp/query-entities storage :binding-list-item {})})


(defn- build-consumer!
  "A `target` base-fn whose impl records every invocation, and a
   `consumer` base-fn with one `:fn-ref`-typed slot `service` whose
   impl returns whatever the slot resolved to."
  [storage suffix calls]
  (let [target-name (str "fr-target-" suffix)
        consumer-name (str "fr-consumer-" suffix)]
    (exec/register-base-fn! (keyword target-name)
                            (fn [_args _ctx] (swap! calls conj :target-ran) :target-value))
    (exec/register-base-fn! (keyword consumer-name)
                            (fn [args _ctx] {:got (rt/resolve-arg args :service)}))
    (let [target (setup/create-base-fn! storage target-name :any)
          consumer (setup/create-base-fn! storage consumer-name :any)
          slot (setup/create-slot! storage "service" :fn-ref)]
      (setup/attach-slot! storage (:id consumer) (:id slot) 0)
      {:target target :consumer consumer :slot slot})))


(deftest fn-ref-slot-passes-the-target-id-without-evaluating-it-test
  (let [storage (setup/create-branch-versioned-test-storage)
        calls (atom [])]
    (try
      (let [{:keys [target consumer slot]} (build-consumer! storage "id" calls)
            uses (setup/create-composed-fn! storage "fr-uses-id" (:id consumer))
            _ (setup/bind-ref! storage (:id uses) (:id slot) (:id target))
            ctx (setup/default-registry-ctx storage)]
        (testing "the impl receives the target's id — a plain uuid"
          (is (= {:got (:id target)} (exec/execute ctx (:id uses) {}))))
        (testing "the target was never evaluated"
          (is (= [] @calls))))
      (finally (sp/close storage)))))


(deftest fn-ref-edges-may-form-a-cycle-test
  (testing "two fns naming each other: the write-time constraint, the
            compile order and the invalidation closure all treat the
            identity edge as a non-dependency"
    (let [storage (setup/create-branch-versioned-test-storage)
          calls (atom [])]
      (try
        (let [{:keys [consumer slot]} (build-consumer! storage "cyc" calls)
              a (setup/create-composed-fn! storage "fr-cyc-a" (:id consumer))
              b (setup/create-composed-fn! storage "fr-cyc-b" (:id consumer))
              _ (setup/bind-ref! storage (:id a) (:id slot) (:id b))
              ;; Closes a → b → a. Rejected for a call edge; legal here.
              _ (setup/bind-ref! storage (:id b) (:id slot) (:id a))
              ctx (setup/default-registry-ctx storage)]
          (testing "both compile and run, each answering the other's id"
            (is (= {:got (:id b)} (exec/execute ctx (:id a) {})))
            (is (= {:got (:id a)} (exec/execute ctx (:id b) {}))))
          (testing "forward deps carry the parent but not the named fn"
            (let [{:keys [forward-deps]} (deps/build-deps-state (graph-rows storage))]
              (is (contains? (get forward-deps (:id a)) (:id consumer)))
              (is (not (contains? (get forward-deps (:id a)) (:id b))))
              (is (not (contains? (get forward-deps (:id b)) (:id a)))))))
        (finally (sp/close storage))))))


(deftest fn-ref-through-an-env-binding-test
  (testing "a `:fn-ref` slot exposed as a free arg of a ref target and bound
            by the outer fn resolves to the id, same as a root-slot binding"
    (let [storage (setup/create-branch-versioned-test-storage)
          calls (atom [])]
      (try
        (let [{:keys [target consumer slot]} (build-consumer! storage "env" calls)
              _ (exec/register-base-fn! :fr-wrap-env
                                        (fn [args _ctx] (rt/resolve-arg args :inner)))
              wrap (setup/create-base-fn! storage "fr-wrap-env" :any)
              inner-slot (setup/create-slot! storage "inner" :any)
              _ (setup/attach-slot! storage (:id wrap) (:id inner-slot) 0)
              ;; `mid` leaves `service` free; `outer` refs mid and binds
              ;; `service` by name — an env-binding on a deep slot.
              mid (setup/create-composed-fn! storage "fr-mid-env" (:id consumer))
              outer (setup/create-composed-fn! storage "fr-outer-env" (:id wrap))
              _ (setup/bind-ref! storage (:id outer) (:id inner-slot) (:id mid))
              _ (setup/bind-ref! storage (:id outer) (:id slot) (:id target))
              ctx (setup/default-registry-ctx storage)]
          (is (= {:got (:id target)} (exec/execute ctx (:id outer) {})))
          (is (= [] @calls)))
        (finally (sp/close storage))))))
