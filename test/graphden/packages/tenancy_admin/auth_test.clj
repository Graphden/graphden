(ns graphden.packages.tenancy-admin.auth-test
  "The tenancy-admin auth base-fns (invoke-login / signup / logout / logout-all)
   — thin dispatchers to the injectable `:user-ops` seam (migrated out of
   app.admin via the route-collection seam §6). A wrong seam key here silently
   breaks login/signup/logout in production, so pin the dispatch + the
   no-seam→nil contract directly. Loaded by load-file the same way the package
   loader does, exercised over a mock ctx seam."
  (:require
    [clojure.test :refer [deftest is testing]]))


(def ^:private impls-path "resources/packages/tenancy-admin/auth/impls.clj")


(deftest invoke-login-dispatches-to-user-ops-login
  (load-file impls-path)
  (let [invoke (resolve 'graphden.packages.tenancy-admin.auth.impls/invoke-login)
        seen (atom nil)
        ctx {:user-ops {:login (fn [_c u p _req] (reset! seen [u p]) {:token "T" :user u})}}]
    (testing "calls (:login user-ops) with username + password, returns the session map"
      (is (= {:token "T" :user "alice"} (invoke {:username "alice" :password "pw"} ctx)))
      (is (= ["alice" "pw"] @seen)))
    (testing "no :user-ops seam (single-tenant / no addon) → nil"
      (is (nil? (invoke {:username "alice" :password "pw"} {}))))))


(deftest invoke-signup-dispatches-with-org-and-request
  (load-file impls-path)
  (let [invoke (resolve 'graphden.packages.tenancy-admin.auth.impls/invoke-signup)
        seen (atom nil)
        ctx {:user-ops {:signup (fn [_c u p o req] (reset! seen [u p o req]) {:token "T"})}}]
    (testing "calls (:signup user-ops) with username / password / org / request (IP for rate-limit)"
      (is (= {:token "T"}
             (invoke {:username "bob" :password "pw" :org "acme" :request {:ip "1.2.3.4"}} ctx)))
      (is (= ["bob" "pw" "acme" {:ip "1.2.3.4"}] @seen)))
    (testing "no seam → nil"
      (is (nil? (invoke {:username "bob" :password "pw" :org "acme" :request {}} {}))))))


(deftest invoke-logout-and-logout-all-dispatch
  (load-file impls-path)
  (let [logout (resolve 'graphden.packages.tenancy-admin.auth.impls/invoke-logout)
        logout-all (resolve 'graphden.packages.tenancy-admin.auth.impls/invoke-logout-all)
        seen (atom [])
        ctx {:user-ops {:logout (fn [_c req] (swap! seen conj [:logout req]) true)
                        :logout-all (fn [_c] (swap! seen conj [:logout-all]) 3)}}]
    (testing "invoke-logout → (:logout user-ops) with the request; returns the deleted? bool"
      (is (true? (logout {:request {:bearer "b"}} ctx)))
      (is (= [:logout {:bearer "b"}] (first @seen))))
    (testing "invoke-logout-all → (:logout-all user-ops); returns the count deleted"
      (is (= 3 (logout-all {} ctx))))
    (testing "no seam → nil for both"
      (is (nil? (logout {:request {}} {})))
      (is (nil? (logout-all {} {}))))))
