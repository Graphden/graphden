(ns graphden.packages.storage.protocol-test
  "Validation tests for storage/protocol — verifies the :Storage
   type-row + :postgres-storage-impl fn-def load through the package
   loader and parse into the expected records.

   This is the first protocol-as-type-row in the codebase (the pattern
   from PHILOSOPHY § Self-Describing System → Protocols via type-row);
   the test exists primarily to surface any loader / parser gap around
   `:fn`-typed record fields."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.packages.loader :as loader]
    [graphden.types.core :as types]))


(use-fixtures :each
  (fn [t]
    (types/clear-aliases!)
    (t)
    (types/clear-aliases!)))


(deftest storage-package-loads
  (testing "loader accepts storage/protocol + storage/pg"
    (let [{:keys [fn-defs base-fn-defs]} (loader/load-packages ["core" "storage"])]
      (testing "base-fns from storage/pg surface in base-fn-defs"
        (is (contains? base-fn-defs :pg-query))
        (is (contains? base-fn-defs :pg-execute))
        (is (contains? base-fn-defs :pg-tx)))

      (testing ":Storage type-row appears as a fn-def"
        (let [storage-def (some #(when (= :Storage (:name %)) %) fn-defs)]
          (is (some? storage-def) ":Storage was emitted by the loader")
          (is (contains? storage-def :type)
              ":Storage carries the :type record map (declaration syntax)")
          (is (= #{:query :execute :tx} (set (keys (:type storage-def))))
              "three abstract operations expressed as record fields")))

      (testing ":postgres-storage-impl parents to :Storage and binds slots"
        (let [impl (some #(when (= :postgres-storage-impl (:name %)) %) fn-defs)]
          (is (some? impl) ":postgres-storage-impl was emitted")
          (is (= :Storage (:parent impl)) "parents to the protocol type-row")
          (is (= {:query :pg-query
                  :execute :pg-execute
                  :tx :pg-tx}
                 (:args impl))
              "each abstract slot bound to its concrete base-fn"))))))
