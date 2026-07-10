(ns ^:integration graphden.system.sse-test
  "End-to-end SSE invalidation round-trip in ONE process: the hub relay
   (`system.sse`) and the remote source (`storage.remote.sse`) connected over
   a real socket. Fire an event through the relay's `graphden_events` callback
   and assert the remote source turns it back into the same parsed event a
   local pod would get from Postgres."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.auth.provider :as auth]
    [graphden.storage.remote.sse :as remote-sse]
    [graphden.system.sse :as sse]))


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
