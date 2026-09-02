(ns ^:integration graphden.packages.web.response-cache-test
  "End-to-end tests for the immutable-response cache now that it is
   composed from the generic state primitives (`:cell` / `:swap` /
   `:deref`) instead of a bespoke atom + `cache-put` impl.

   `:response-cache-get` and `:response-cache-put-if!` are golden
   fn-defs (web/http), so we execute them BY NAME and assert the same
   contract the old base-fns had:

   - `put-if!` with `:when? true` stores; a later `get` hits.
   - `put-if!` with `:when? false` is a no-op but still returns `:value`.
   - the cell PERSISTS across separate `execute` calls (store in one,
     read in another).
   - the flush-all-at-64 capacity eviction keeps the map bounded.

   Each test uses its own key namespace so the shared cell can't cross-
   talk between deftests.

   BLIND SPOT (measured 2026-07-17): these execute `put-if!` and `get`
   BY NAME, so both resolve to compile-all's SINGLE shared bake of
   `:response-cache-cell` and the roundtrip always works. They do NOT
   compile the composed handler wrap (`:_app-cached` = cache→encode→
   realize→handler), where the store and lookup are separate SUBTREES of
   one compiled fn — and a structural change there can make the `:cell`
   bake TWO atoms (store one, read the other), silently killing every
   immutable-asset cache hit while these tests stay green. See the
   `DO NOT \"dedup\" the encode` note in `app/server/fns.edn`."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.test-infra.exec-harness :as eh :refer [*context*]]))


(use-fixtures :once
  (setup/create-container-fixture)
  (eh/exec-fixture (str (ns-name *ns*))))


(def ^:private a-response
  {:status 200 :headers {"Content-Type" "application/javascript"} :body "cached-bytes"})


(deftest put-then-get-roundtrips-across-executes
  (testing "store under a key, then a SEPARATE execute reads it back (cell persists)"
    (let [put (eh/fn-id "response-cache-put-if!")
          get-id (eh/fn-id "response-cache-get")
          k   ["/rc-roundtrip" "get-id" ""]]
      (is (nil? (exec/execute *context* get-id {:key k}))
          "miss before anything is stored")
      (is (= a-response
             (exec/execute *context* put {:key k :value a-response :when? true}))
          "put-if! returns the value it stored")
      (is (= a-response (exec/execute *context* get-id {:key k}))
          "a later execute hits the persisted cell"))))


(deftest put-if-false-does-not-store-but-returns-value
  (testing "`:when? false` is a no-op but still passes the value through"
    (let [put (eh/fn-id "response-cache-put-if!")
          get-id (eh/fn-id "response-cache-get")
          k   ["/rc-noop" "get-id" ""]]
      (is (= a-response
             (exec/execute *context* put {:key k :value a-response :when? false}))
          "returns value even when not storing")
      (is (nil? (exec/execute *context* get-id {:key k}))
          "nothing was stored → miss"))))


(deftest capacity-eviction-keeps-the-cell-bounded
  (testing "flush-all-at-64 keeps the cache map from growing without bound"
    (let [put     (eh/fn-id "response-cache-put-if!")
          current (eh/fn-id "_response-cache-current")]
      (dotimes [i 70]
        (exec/execute *context* put {:key [(str "/rc-evict-" i)]
                                     :value a-response
                                     :when? true}))
      (is (<= (count (exec/execute *context* current {})) 64)
          "the map never exceeds capacity — graph-visible eviction fired"))))
