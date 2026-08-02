(ns graphden.tenancy.app-route-test
  "Unit coverage for the :app-route read helpers + its platform-forbidden status."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.tenancy.app-route :as app-route]
    [graphden.tenancy.storage :as ts]))


(deftest normalize-label-lowercases-trims-and-nils-blank
  (testing "case + surrounding whitespace are folded"
    (is (= "shop" (app-route/normalize-label "  SHOP ")))
    (is (= "docs" (app-route/normalize-label "Docs"))))
  (testing "blank / nil → nil (no default app row)"
    (is (nil? (app-route/normalize-label nil)))
    (is (nil? (app-route/normalize-label "")))
    (is (nil? (app-route/normalize-label "   ")))))


(deftest app-route-is-platform-forbidden
  ;; A tenant must not read or write routing rows — host-hijack / enumeration.
  (is (contains? ts/tenant-forbidden-entities :app-route)))
