(ns graphden.fleet.router-body-test
  "The forward-hop's body fidelity (`graphden.fleet.router/forward-request`):
   a holder's binary and compressed responses reach the client byte-exact.
   No storage — the stub holder is addressed directly."
  (:require
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
               :body (java.io.ByteArrayInputStream. (gzip binary))}))
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
