(ns ^:integration graphden.test-infra.shared-bootstrap-test
  "The golden DB and the type-check sweep are cached per RESOLVED package
   set: two name lists that load the same packages in the same order are
   one bootstrap, not two. `platform-tests-test`'s `[core storage web
   app-base app registry mcp]` used to build a second golden and a second
   ~30 s sweep beside `[core web app registry mcp]`."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.test-infra.shared-bootstrap :as sb]))


(deftest equivalent-package-lists-share-one-golden-test
  (let [short-form (sb/ensure-golden! ["core" "web" "app"])
        spelled-out (sb/ensure-golden! ["core" "storage" "web" "app-base" "app"])]
    (testing "the fully spelled-out list reuses the golden of its short form"
      (is (identical? short-form spelled-out)))
    (testing "a different set is still its own golden"
      (is (not= (:db-name short-form)
                (:db-name (sb/ensure-golden! ["core" "web"])))))))


(deftest set-key-is-the-resolved-load-order-test
  (let [set-key @#'sb/set-key]
    (is (= ["core" "storage" "web" "app-base" "app" "registry" "mcp"]
           (set-key ["core" "web" "app" "registry" "mcp"])
           (set-key ["core" "storage" "web" "app-base" "app" "registry" "mcp"]))
        "the sweep cache shares the golden's key, so it dedups the same way")))
