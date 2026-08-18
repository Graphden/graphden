(ns ^:serial graphden.crud.debug-capture-test
  "Unit tests for the «catch next request» trap (crud.debug-capture):
   arm/disarm/status lifecycle, the atomic one-shot consume, org +
   branch keying, prefix / infra-path matching, TTL expiry, and the
   captured run's sanitization (credential headers and Set-Cookie
   never reach the persisted row).

   ^:serial — the trap registry is a process-global atom (that is the
   design: runtime-only state, like `*traced-fn-ids*`), so parallel
   siblings would race the shared registry. Every test disarms what
   it arms."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.crud.debug-capture :as dbg]
    [graphden.crud.fn-execution.lookup :as lookup]
    [graphden.crud.fn-execution.persist :as persist]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tc]))


(defn- req
  ([uri] (req uri nil))
  ([uri extra]
   (merge {:request-method :get :uri uri :headers {}} extra)))


(deftest arm-status-disarm-roundtrip-test
  (let [branch-id (random-uuid)]
    (try
      (testing "unarmed by default"
        (is (nil? (dbg/trap-status branch-id))))
      (let [trap (dbg/arm! branch-id {:path-prefix "/shop" :ttl-ms 5000})]
        (testing "arm returns the trap and status sees it"
          (is (= "/shop" (:path-prefix trap)))
          (is (false? (:capture-values? trap)))
          (is (= trap (dbg/trap-status branch-id))))
        (testing "re-arm replaces"
          (let [trap2 (dbg/arm! branch-id {:capture-values? true})]
            (is (nil? (:path-prefix trap2)) "blank prefix → catch-all")
            (is (true? (:capture-values? trap2)))
            (is (= trap2 (dbg/trap-status branch-id))))))
      (testing "disarm removes; second disarm reports false"
        (is (true? (dbg/disarm! branch-id)))
        (is (nil? (dbg/trap-status branch-id)))
        (is (false? (dbg/disarm! branch-id))))
      (finally (dbg/disarm! branch-id)))))


(deftest consume-is-one-shot-test
  (let [branch-id (random-uuid)]
    (try
      (let [trap (dbg/arm! branch-id {:path-prefix "/shop"})]
        (is (= trap (dbg/consume-trap! branch-id (req "/shop/cart")))
            "first matching request claims the trap")
        (is (nil? (dbg/consume-trap! branch-id (req "/shop/cart")))
            "second request finds it consumed")
        (is (nil? (dbg/trap-status branch-id))))
      (finally (dbg/disarm! branch-id)))))


(deftest consume-respects-prefix-and-infra-exclusion-test
  (let [branch-id (random-uuid)]
    (try
      (testing "explicit prefix: non-matching uri leaves the trap armed"
        (dbg/arm! branch-id {:path-prefix "/shop"})
        (is (nil? (dbg/consume-trap! branch-id (req "/blog"))))
        (is (some? (dbg/trap-status branch-id)))
        (dbg/disarm! branch-id))
      (testing "catch-all skips editor-infra paths but takes app paths"
        (dbg/arm! branch-id {})
        (doseq [uri ["/api/execute" "/partials/tests" "/assets/x.js"
                     "/events/stream" "/auth/login" "/version"]]
          (is (nil? (dbg/consume-trap! branch-id (req uri)))
              (str uri " must not consume a catch-all trap")))
        (is (some? (dbg/consume-trap! branch-id (req "/"))))
        (is (nil? (dbg/trap-status branch-id))))
      (testing "explicit infra prefix targets it deliberately"
        (dbg/arm! branch-id {:path-prefix "/api/execute"})
        (is (some? (dbg/consume-trap! branch-id (req "/api/execute")))))
      (finally (dbg/disarm! branch-id)))))


(deftest consume-branch-and-org-isolation-test
  (let [branch-a (random-uuid)
        branch-b (random-uuid)]
    (try
      (dbg/arm! branch-a {})
      (testing "another branch's request never fires the trap"
        (is (nil? (dbg/consume-trap! branch-b (req "/"))))
        (is (some? (dbg/trap-status branch-a))))
      (testing "another ORG's request never fires the trap"
        (binding [tc/*current-org* "other-org"]
          (is (nil? (dbg/consume-trap! branch-a (req "/"))))
          (is (nil? (dbg/trap-status branch-a)) "status is org-keyed too"))
        (is (some? (dbg/trap-status branch-a))))
      (finally (dbg/disarm! branch-a)))))


(deftest consume-expired-trap-is-dropped-test
  (let [branch-id (random-uuid)]
    (try
      (let [trap (dbg/arm! branch-id {:ttl-ms 1000})]
        ;; Rewind the expiry instead of sleeping: the registry is the
        ;; unit under test, not the clock.
        (#'dbg/force-expire-for-test! branch-id trap)
        (is (nil? (dbg/consume-trap! branch-id (req "/"))))
        (is (nil? (dbg/trap-status branch-id)) "expired ≡ unarmed"))
      (finally (dbg/disarm! branch-id)))))


(deftest run-captured-sanitizes-and-persists-test
  (let [branch-id (random-uuid)
        row-id (random-uuid)
        writes (atom nil)
        args-written (atom nil)
        response {:status 200
                  :headers {"Content-Type" "text/html"
                            "Set-Cookie" "session=hunter2"}
                  :body "ok"}
        request (req "/shop/cart"
                     {:request-method :post
                      :query-string "q=1"
                      :headers {"authorization" "Bearer tok"
                                "Cookie" "session=abc"
                                "x-api-key" "k"
                                "accept" "text/html"}
                      :body "payload"})]
    (with-redefs [lookup/resolve-fn-version-id (fn [_ _] (random-uuid))
                  lookup/free-arg-slot-map-cached (fn [_ _] {:request (random-uuid)})
                  persist/create-pending-row! (fn [& _] {:id row-id})
                  persist/persist-args! (fn [_ _ args _] (reset! args-written args))
                  persist/write-finished! (fn [_ id outcome] (reset! writes [id outcome]))
                  sp/update-entity (fn [& _] nil)]
      (testing "the response returns unchanged (capture is invisible to the caller)"
        (is (= response
               (dbg/run-captured! {:capture-values? false} {} (random-uuid)
                                  request (constantly response)))))
      (let [[id outcome] @writes]
        (is (= row-id id))
        (is (= :succeeded (:status outcome)))
        (testing "Set-Cookie stripped from the persisted response"
          (is (= {"Content-Type" "text/html"}
                 (get-in outcome [:result :headers]))))
        (testing "credential request headers stripped from the persisted arg"
          (let [captured (:request @args-written)]
            (is (= {"accept" "text/html"} (:headers captured)))
            (is (= "post" (:request-method captured)))
            (is (= "payload" (:body captured))))))
      (testing "a throwing handler rethrows AND persists the failure"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"boom"
              (dbg/run-captured! {} {} (random-uuid) request
                                 (fn [] (throw (ex-info "boom" {:k 1}))))))
        (let [[_ outcome] @writes]
          (is (= :failed (:status outcome)))
          (is (= "boom" (:error outcome)))))
      (testing "a persist failure never breaks the response"
        (with-redefs [persist/create-pending-row! (fn [& _] (throw (ex-info "db down" {})))]
          (is (= response
                 (dbg/run-captured! {} {} (random-uuid)
                                    request (constantly response)))))))))
