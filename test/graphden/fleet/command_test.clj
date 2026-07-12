(ns graphden.fleet.command-test
  "Directed cell-command transport (`graphden.fleet.command`, docs/FLEET_RFC.md
   §6.3). URL parse/build are pure; the auth-gated server seam + the ACK-gated
   client are exercised in-JVM by redefining `cr/load-cell!` / `cr/evict-cell!`
   and `http/request` — no container, no second pod."
  (:require
    [cheshire.core :as json]
    [clojure.test :refer [deftest is testing]]
    [graphden.executor.compile-runtime :as cr]
    [graphden.fleet.command :as cmd]
    [org.httpkit.client :as http]))


(def ^:private ROOT #uuid "00000000-0000-0000-0000-0000000000e1")
(def ^:private TOKEN "s3cret-internal")


(deftest parse-command-uri-recognises-only-valid-commands
  (testing "a well-formed load/evict path parses to op + uuid"
    (is (= {:op :load :root-fn-id ROOT}
           (cmd/parse-command-uri (str cmd/path-prefix "load/" ROOT))))
    (is (= {:op :evict :root-fn-id ROOT}
           (cmd/parse-command-uri (str cmd/path-prefix "evict/" ROOT)))))
  (testing "non-command paths → nil (dispatch falls through)"
    (is (nil? (cmd/parse-command-uri "/api/branches")))
    (is (nil? (cmd/parse-command-uri "/internal/fleet/other/x"))))
  (testing "unknown op or malformed uuid → nil"
    (is (nil? (cmd/parse-command-uri (str cmd/path-prefix "drop/" ROOT))))
    (is (nil? (cmd/parse-command-uri (str cmd/path-prefix "load/not-a-uuid"))))
    (is (nil? (cmd/parse-command-uri (str cmd/path-prefix "load/"))))))


(defn- req
  [method uri token]
  (cond-> {:request-method method :uri uri}
    token (assoc :headers {"authorization" (str "Bearer " token)})))


(deftest server-seam-gates-on-the-internal-token
  (let [handler (cmd/make-command-handler TOKEN)
        ctx {}]
    (testing "a non-command request → nil (falls through to app/editor)"
      (is (nil? (handler ctx (req :get "/api/branches" TOKEN))))
      (is (nil? (handler ctx (req :post "/api/execute" TOKEN)))))
    (testing "a command with a wrong / missing token → 401, never runs"
      (with-redefs [cr/load-cell! (fn [_ _] (throw (AssertionError. "must not run unauthorized")))]
        (is (= 401 (:status (handler ctx (req :post (str cmd/path-prefix "load/" ROOT) "wrong")))))
        (is (= 401 (:status (handler ctx (req :post (str cmd/path-prefix "load/" ROOT) nil)))))))
    (testing "an unset internal token fail-closes (denies even a blank bearer)"
      (let [open (cmd/make-command-handler nil)]
        (is (= 401 (:status (open ctx (req :post (str cmd/path-prefix "load/" ROOT) "")))))))))


(deftest server-seam-load-runs-and-reports-fn-count
  (let [handler (cmd/make-command-handler TOKEN)
        ctx {:tag :the-ctx}]
    (testing "authorized load compiles the cell → 200 + loaded count"
      (with-redefs [cr/load-cell! (fn [c root]
                                    (is (= ctx c) "the pod's own ctx is used")
                                    (is (= ROOT root))
                                    #{root :a :b})]
        (let [resp (handler ctx (req :post (str cmd/path-prefix "load/" ROOT) TOKEN))]
          (is (= 200 (:status resp)))
          (is (= {"ok" true "loaded" 3} (json/parse-string (:body resp)))))))
    (testing "an empty load (cell not in this shard) → 409 so the move aborts"
      (with-redefs [cr/load-cell! (fn [_ _] #{})]
        (is (= 409 (:status (handler ctx (req :post (str cmd/path-prefix "load/" ROOT) TOKEN)))))))
    (testing "authorized evict → 200 + evicted count (idempotent)"
      (with-redefs [cr/evict-cell! (fn [_ _] #{:a :b})]
        (let [resp (handler ctx (req :post (str cmd/path-prefix "evict/" ROOT) TOKEN))]
          (is (= 200 (:status resp)))
          (is (= {"ok" true "evicted" 2} (json/parse-string (:body resp)))))))))


(deftest client-send-command-is-ack-gated
  (testing "a 200 ack → true; the URL + bearer are well-formed"
    (with-redefs [http/request (fn [opts]
                                 (is (= (str "http://pod-b:8080" cmd/path-prefix "load/" ROOT)
                                        (:url opts)))
                                 (is (= "Bearer tok" (get-in opts [:headers "Authorization"])))
                                 (is (= :post (:method opts)))
                                 (future {:status 200 :body "{}"}))]
      (is (true? (cmd/send-command "pod-b" 8080 "tok" :load ROOT)))))
  (testing "a 409/401 or any non-200 → false (controller aborts the move)"
    (with-redefs [http/request (fn [_] (future {:status 409 :body "{}"}))]
      (is (false? (cmd/send-command "pod-b" 8080 "tok" :load ROOT))))
    (with-redefs [http/request (fn [_] (future {:status 401 :body "{}"}))]
      (is (false? (cmd/send-command "pod-b" 8080 "tok" :load ROOT)))))
  (testing "a transport error → false, not a throw"
    (with-redefs [http/request (fn [_] (future {:error (java.net.ConnectException. "refused")}))]
      (is (false? (cmd/send-command "pod-b" 8080 "tok" :evict ROOT))))))


(deftest directed-seams-close-over-port-and-token
  (let [seen (atom nil)]
    (with-redefs [http/request (fn [opts] (reset! seen (:url opts)) (future {:status 200 :body "{}"}))]
      (let [load-on ((cmd/directed-load 9000 "t") "pod-x" ROOT)]
        (is (true? load-on))
        (is (= (str "http://pod-x:9000" cmd/path-prefix "load/" ROOT) @seen)))
      (let [evict-on ((cmd/directed-evict 9000 "t") "pod-y" ROOT)]
        (is (true? evict-on))
        (is (= (str "http://pod-y:9000" cmd/path-prefix "evict/" ROOT) @seen))))))
