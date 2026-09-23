(ns graphden.fleet.router-body-test
  "The forward-hop's body fidelity (`graphden.fleet.router/forward-request`):
   a holder's binary and compressed responses reach the client byte-exact.
   No storage — the stub holder is addressed directly."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [graphden.fleet.router :as router]
    [org.httpkit.server :as hk])
  (:import
    (java.io
      ByteArrayOutputStream)
    (java.util.zip
      GZIPOutputStream)))


(def ^:private binary
  "Every byte value — invalid UTF-8 included, which a text decode mangles."
  (byte-array (map unchecked-byte (range 256))))


(defn- gzip
  ^bytes [^bytes bs]
  (let [out (ByteArrayOutputStream.)]
    (with-open [gz (GZIPOutputStream. out)] (GZIPOutputStream/.write gz bs))
    (ByteArrayOutputStream/.toByteArray out)))


(defn- holder
  []
  (hk/run-server
    (fn [req]
      (case (:uri req)
        "/bin" {:status 200 :headers {"Content-Type" "application/octet-stream"}
                :body (java.io.ByteArrayInputStream. binary)}
        "/gz" {:status 200 :headers {"Content-Type" "application/octet-stream"
                                     "Content-Encoding" "gzip"}
               :body (java.io.ByteArrayInputStream. (gzip binary))}
        "/echo" {:status 200
                 :headers {"Content-Type" "application/octet-stream"
                           "X-Seen-Headers" (str/join "," (sort (keys (:headers req))))}
                 :body (java.io.ByteArrayInputStream.
                         (java.io.InputStream/.readAllBytes (:body req)))}))
    {:port 0}))


(deftest forward-request-keeps-bodies-byte-exact
  (let [server (holder)
        port (:local-port (meta server))]
    (try
      (testing "a binary body crosses the hop unchanged (was decoded as text)"
        (let [resp (router/forward-request "localhost" port {:request-method :get :uri "/bin"})]
          (is (= 200 (:status resp)))
          (is (= (seq binary) (seq ^bytes (:body resp))))))
      (testing "a gzip response: the body we return matches the headers we return"
        (let [resp (router/forward-request "localhost" port {:request-method :get :uri "/gz"})]
          (is (nil? (get-in resp [:headers "content-encoding"]))
              "http-kit inflated it, so the gzip header must not ride along")
          (is (nil? (get-in resp [:headers "content-length"])))
          (is (= (seq binary) (seq ^bytes (:body resp))))))
      (finally (server)))))


(deftest forward-request-sends-a-clean-request
  ;; The body is read in full before the hop, yet the client's hop-by-hop
  ;; headers were forwarded: the holder waited for chunked framing / a
  ;; 100-continue that never came (30 s → 502). And the request body went
  ;; out as the UTF-8 String dispatch realized — a binary upload corrupted.
  (let [server (holder)
        port (:local-port (meta server))
        started (System/currentTimeMillis)]
    (try
      (let [resp (router/forward-request
                   "localhost" port
                   {:request-method :post :uri "/echo"
                    :headers {"host" "acme.example" "content-type" "application/octet-stream"
                              "transfer-encoding" "chunked" "expect" "100-continue"
                              "te" "trailers" "upgrade" "h2c" "connection" "upgrade, x-hop"
                              "x-hop" "1" "keep-alive" "timeout=5"}
                    ;; what branch-router/dispatch hands over: the decoded
                    ;; String plus the original bytes
                    :body (String. ^bytes binary "UTF-8")
                    :graphden/raw-body binary})
            seen (set (str/split (str (get-in resp [:headers "x-seen-headers"])) #","))]
        (testing "the holder answers at once"
          (is (= 200 (:status resp)))
          (is (< (- (System/currentTimeMillis) started) 10000)))
        (testing "the request body arrives byte-exact"
          (is (= (seq binary) (seq ^bytes (:body resp)))))
        (testing "hop-by-hop headers (and those Connection names) are dropped, Host kept"
          (is (empty? (filter seen ["transfer-encoding" "expect" "te" "upgrade"
                                    "x-hop" "keep-alive"])))
          (is (contains? seen "host"))
          (is (contains? seen "content-type"))))
      (finally (server)))))
