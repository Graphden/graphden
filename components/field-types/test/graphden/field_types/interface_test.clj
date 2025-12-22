(ns graphden.field-types.interface-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.field-types.interface :as ft]))


(deftest types-test
  (testing "all expected types are defined"
    (is (= #{:uuid :text :int :bool :numeric :timestamptz :jsonb :bytes}
           ft/supported-types)))

  (testing "each type has metadata with description"
    (doseq [type-kw ft/supported-types]
      (is (contains? ft/types type-kw)
          (str "Type " type-kw " should be in types map"))
      (is (string? (get-in ft/types [type-kw :description]))
          (str "Type " type-kw " should have a description string")))))
