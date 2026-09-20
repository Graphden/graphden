(ns graphden.crud.seal-rej-test
  "The seal rules over a VIEW of the graph (`ancestor-seal-rej`) — the
   walk `packages.sync/sync-bundle!` runs over an MCP / registry bundle
   before it lands. Pure: the view is two fns over maps, no storage."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.crud.validation :as v]))


(def ^:private base (random-uuid))
(def ^:private mid (random-uuid))
(def ^:private leaf (random-uuid))
(def ^:private slot (random-uuid))


(defn- view
  [bindings]
  {:parents-of {leaf [mid] mid [base] base []}
   :binding-of (fn [fid sid] (get bindings [fid sid]))})


(deftest a-valued-ancestor-is-final-for-a-scalar-write-only
  (let [v (view {[mid slot] {:fn-id mid :slot-id slot :value 1 :value-present true}})]
    (is (= :constraint-violation/value-override
           (:type (v/ancestor-seal-rej v leaf slot {})))
        "binding the slot the parent valued is refused")
    (is (nil? (v/ancestor-seal-rej v leaf slot {:list-write? true}))
        "a LIST write extends the ancestor's items — never an override")
    (is (nil? (v/ancestor-seal-rej v mid slot {}))
        "the sealer's own binding is not its own ancestor")))


(deftest a-ref-counts-as-a-value
  (let [v (view {[base slot] {:fn-id base :slot-id slot :ref-fn-id (random-uuid)}})]
    (is (= :constraint-violation/value-override
           (:type (v/ancestor-seal-rej v leaf slot {})))
        "found two levels up, through mid")))


(deftest terminal-and-list-closed
  (let [v (view {[base slot] {:fn-id base :slot-id slot :terminal true}})]
    (is (= :constraint-violation/terminal-seal (:type (v/ancestor-seal-rej v leaf slot {}))))
    (is (= :constraint-violation/terminal-seal (:type (v/ancestor-seal-rej v leaf slot {:list-write? true})))
        "a seal refuses list writes too"))
  (let [v (view {[mid slot] {:fn-id mid :slot-id slot :list-append true :list-closed true}})]
    (is (= :constraint-violation/list-closed (:type (v/ancestor-seal-rej v leaf slot {:list-write? true}))))
    (is (nil? (v/ancestor-seal-rej v leaf slot {}))
        "closed is about appending; a non-list write on the slot is judged by the other rules"))
  (testing "a binding that only renames / describes / flags nothing seals nothing"
    (is (nil? (v/ancestor-seal-rej (view {[mid slot] {:fn-id mid :slot-id slot :description "x"}})
                                   leaf slot {})))))


(deftest the-reason-is-the-api-path-s-wording
  (let [v (view {[mid slot] {:fn-id mid :slot-id slot :terminal true}})
        rej (v/ancestor-seal-rej v leaf slot {})]
    (is (= (v/seal-reasons :constraint-violation/terminal-seal) (:reason rej)))))
