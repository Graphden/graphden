(ns graphden.packages.tenancy-admin.my-app-test
  "The tenancy-admin self-serve base-fn (invoke-set-org-handler), migrated out
   of app.admin via the route-collection seam (§6). Loaded by load-file the
   same way the package loader does, exercised over a fake ctx seam."
  (:require
    [clojure.test :refer [deftest is testing]]))


(def ^:private impls-path "resources/packages/tenancy-admin/my-app/impls.clj")


(deftest invoke-set-org-handler-calls-the-ctx-seam
  (load-file impls-path)
  (let [invoke (resolve 'graphden.packages.tenancy-admin.my-app.impls/invoke-set-org-handler)
        fid (random-uuid)
        called (atom nil)
        ctx {:set-org-handler (fn [_c fnid] (reset! called fnid) :ok)}]
    (testing "calls the injected seam with the parsed uuid"
      (is (= :ok (invoke {:fn-id (str fid)} ctx)))
      (is (= fid @called)))
    (testing "no seam (single-tenant / no addon) → nil"
      (is (nil? (invoke {:fn-id (str fid)} {}))))))
