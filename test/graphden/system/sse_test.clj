(ns ^:integration ^:serial graphden.system.sse-test
  "`^:serial` — `with-redefs` root-rebinds `org.httpkit.server/send!`;
   a concurrent frame send in the integration pool is silently
   swallowed by the stub.

   End-to-end SSE invalidation round-trip in ONE process: the hub relay
   (`system.sse`) and the remote source (`storage.remote.sse`) connected over
   a real socket. Fire an event through the relay's `graphden_events` callback
   and assert the remote source turns it back into the same parsed event a
   local pod would get from Postgres."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.auth.provider :as auth]
    [graphden.storage.remote.sse :as remote-sse]
    [graphden.system.sse :as sse]
    [org.httpkit.server :as hk]))


(defn- wait-for
  "Poll `pred` up to `ms`, 20ms steps. Returns truthy pred value or nil."
  [ms pred]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (or (pred)
          (when (< (System/currentTimeMillis) deadline)
            (Thread/sleep 20)
            (recur))))))


(defn- relay-port
  [relay]
  (:local-port (meta (:server relay))))


(deftest broadcast-evicts-a-subscriber-whose-send-fails
  ;; A subscriber whose channel closed underneath us (send! throws or returns
  ;; falsey) must be dropped, and not counted as delivered, so a dead channel
  ;; doesn't linger in the fan-out set. Pure — redef send! to fail one channel.
  (let [subscribers (atom {:good "acme" :bad "acme"})]
    (with-redefs [hk/send! (fn [ch _frame _close?]
                             (if (= ch :bad)
                               (throw (Exception. "channel closed"))
                               true))]
      (let [delivered (sse/broadcast! subscribers
                                      {:kind :fn :op :invalidate :id "f" :org-id "acme"})]
        (is (= 1 delivered) "only the healthy subscriber is counted")
        (is (= {:good "acme"} @subscribers) "the failed subscriber was evicted")))))


(deftest sse-relay-round-trips-an-event-to-the-remote-source
  ;; A fake notify-listener: just the `:callbacks` atom `register!` needs. The
  ;; relay registers its broadcast callback here; firing it simulates a
  ;; `graphden_events` NOTIFY.
  (let [listener {:callbacks (atom #{})}
        relay (sse/start-relay! {:port 0 :notify-listener listener :auth-provider nil})
        received (atom [])
        source (remote-sse/start-source!
                 {:hub-url (str "http://localhost:" (relay-port relay))
                  :token "tok"
                  :on-event (fn [event] (swap! received conj event))})]
    (try
      (testing "the source connects and registers as a subscriber"
        (is (wait-for 3000 #(seq @(:subscribers relay)))
            "relay saw the SSE subscriber connect"))

      (testing "an event fired through the relay's callback reaches the source, parsed"
        (let [event {:kind :fn :op :invalidate :id "fn-123" :branch-id "br-9"}]
          ;; Fire the registered callback (what the NOTIFY listener would do).
          (doseq [cb @(:callbacks listener)] (cb event))
          (is (wait-for 3000 #(seq @received)) "source received a frame")
          (is (= event (first @received))
              "round-tripped through format-payload → SSE → parse-payload intact")))

      (testing "a full-clear event (empty id, no branch) round-trips too"
        (reset! received [])
        (let [event {:kind :fn :op :invalidate :id ""}]
          (doseq [cb @(:callbacks listener)] (cb event))
          (is (wait-for 3000 #(seq @received)))
          (is (= event (first @received)))))
      (finally
        (remote-sse/stop-source! source)
        (sse/stop-relay! relay)))))


(deftest sse-relay-requires-auth-when-a-provider-is-wired
  (let [provider (reify auth/AuthProvider
                   (authenticate
                     [_ req]
                     {:authenticated? (= "Bearer good" (get-in req [:headers "authorization"]))}))
        subscribers (atom #{})
        handler (sse/make-handler subscribers provider)]
    (testing "no / wrong token → 401, no subscriber added"
      (is (= 401 (:status (handler {:headers {}}))))
      (is (= 401 (:status (handler {:headers {"authorization" "Bearer bad"}}))))
      (is (empty? @subscribers)))))


(deftest sse-relay-fans-out-per-org
  ;; Each subscriber registers under its authenticated org; an org-tagged
  ;; event reaches only that org's subscribers, a nil-org (public) event
  ;; reaches everyone.
  (let [provider (reify auth/AuthProvider
                   (authenticate
                     [_ req]
                     (let [tok (get-in req [:headers "authorization"])]
                       (condp = tok
                         "Bearer acme" {:authenticated? true :org "acme"}
                         "Bearer beta" {:authenticated? true :org "beta"}
                         {:authenticated? false}))))
        listener {:callbacks (atom #{})}
        relay (sse/start-relay! {:port 0 :notify-listener listener :auth-provider provider})
        acme-got (atom []) beta-got (atom [])
        mk (fn [token sink]
             (remote-sse/start-source!
               {:hub-url (str "http://localhost:" (relay-port relay))
                :token token
                :on-event (fn [e] (swap! sink conj e))}))
        acme-src (mk "acme" acme-got)
        beta-src (mk "beta" beta-got)]
    (try
      (is (wait-for 3000 #(= 2 (count @(:subscribers relay)))) "both subscribers connected")
      (let [fire (fn [event] (doseq [cb @(:callbacks listener)] (cb event)))]
        (testing "an acme-tagged event reaches only acme"
          (fire {:kind :fn :op :invalidate :id "f1" :org-id "acme"})
          (is (wait-for 2000 #(seq @acme-got)))
          (is (= "acme" (:org-id (first @acme-got))))
          (Thread/sleep 200)
          (is (empty? @beta-got) "beta did NOT get acme's event"))
        (testing "a nil-org (public) event reaches everyone"
          (reset! acme-got []) (reset! beta-got [])
          (fire {:kind :fn :op :invalidate :id "pub"})
          (is (wait-for 2000 #(seq @acme-got)))
          (is (wait-for 2000 #(seq @beta-got)))
          (is (= "pub" (:id (first @acme-got))))
          (is (= "pub" (:id (first @beta-got))))))
      (finally
        (remote-sse/stop-source! acme-src)
        (remote-sse/stop-source! beta-src)
        (sse/stop-relay! relay)))))
