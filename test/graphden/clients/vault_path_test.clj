(ns graphden.clients.vault-path-test
  "The vault client's path contract. The KV mount is ONE flat namespace
   read and written with the PLATFORM token, and the path is concatenated
   into the request URL — so the client is where per-org isolation and
   URL safety are enforced: a tenant op must stay under `org/<its-org>/`,
   and no path may leave the KV mount (`../sys/…`). Pure: the HTTP layer
   is replaced through the thread-local `*impl-override*` seam, which
   records the path each op was allowed to reach."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.clients.vault :as vault]
    [graphden.tenancy.context :as tctx]))


(def ^:private client {:address "http://vault" :token "t"})


(defn- reached
  "Run `op` (a fn of the client) with every vault op faked; returns the
   paths the fakes were called with, or the thrown ex-data `:type`."
  [op]
  (let [seen (atom [])
        fake (fn [_client path & _] (swap! seen conj path) nil)]
    (try
      (binding [vault/*impl-override* {:get-secret fake :put-secret fake
                                       :delete-secret fake :get-metadata fake
                                       :put-metadata fake}]
        (op client))
      @seen
      (catch clojure.lang.ExceptionInfo e
        (:type (ex-data e))))))


(deftest scoped-path-test
  (testing "the platform tier stores a path as given (leading `/` dropped)"
    (is (= "db/password" (vault/scoped-path "db/password")))
    (is (= "db/password" (vault/scoped-path "/db/password"))))

  (testing "a tenant's path lands under its org prefix, idempotently"
    (tctx/with-org "acme"
                   (is (= "org/acme/db/password" (vault/scoped-path "db/password")))
                   (is (= "org/acme/db/password" (vault/scoped-path "org/acme/db/password"))))))


(deftest malformed-paths-are-refused-test
  (doseq [p ["../sys/policy/root" "a/../b" "a/./b" "a//b" "a/" "a?x=1" "a#f"
             "a%2e%2e/b" "a b" "a\\b" "a\nb"]]
    (testing (pr-str p)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid path"
            (vault/scoped-path p)))
      (is (= :vault/invalid-path (reached #(vault/get-secret % p)))
          "and never reaches the HTTP layer"))))


(deftest tenant-ops-stay-inside-the-org-prefix-test
  (tctx/with-org "acme"
                 (testing "every op on another org's / the platform's path is forbidden"
                   (doseq [p ["org/other/db/password" "db/password" "org/acmex/db"]
                           [op-name op] {"get" #(vault/get-secret % p)
                                         "put" #(vault/put-secret % p "v")
                                         "delete" #(vault/delete-secret % p)
                                         "get-metadata" #(vault/get-metadata % p)
                                         "put-metadata" #(vault/put-metadata % p {})}]
                     (is (= :vault/path-forbidden (reached op)) (str op-name " " p))))

                 (testing "the org's own prefix is reachable"
                   (is (= ["org/acme/db/password"]
                          (reached #(vault/put-secret % "org/acme/db/password" "v")))))))


(deftest platform-tier-is-unrestricted-test
  (is (= ["org/acme/db/password"]
         (reached #(vault/delete-secret % "org/acme/db/password"))))
  (is (= ["shared/smtp"] (reached #(vault/get-secret % "/shared/smtp")))))


(deftest latest-secret-reads-use-a-short-tenant-scoped-cache-test
  (vault/clear-secret-cache!)
  (let [now (atom 1000)
        calls (atom 0)
        fake-read (fn [_client _path & _]
                    (str "value-" (swap! calls inc)))
        fake-write (fn [& _] :written)]
    (binding [vault/*now-ms* #(deref now)
              vault/*impl-override* {:get-secret fake-read
                                     :put-secret fake-write
                                     :delete-secret (fn [& _] :deleted)}]
      (tctx/with-org "acme"
                     (is (= "value-1" (vault/get-secret client "org/acme/db")))
                     (is (= "value-1" (vault/get-secret client "org/acme/db"))
                         "a warm read reuses the latest value")
                     (is (= 1 @calls))
                     (vault/put-secret client "org/acme/db" "rotated")
                     (is (= "value-2" (vault/get-secret client "org/acme/db"))
                         "a successful write invalidates cached values")
                     (is (= "value-3" (vault/get-secret client "org/acme/db" 1))
                         "an explicit KV version bypasses the latest-value cache")
                     (is (= 3 @calls) "the explicit version made its own read")
                     (is (= "value-2" (vault/get-secret client "org/acme/db")))
                     (reset! now 3001)
                     (is (= "value-4" (vault/get-secret client "org/acme/db")))
                     (is (= 4 @calls) "expired entries are fetched again")
                     (vault/delete-secret client "org/acme/db")
                     (is (= "value-5" (vault/get-secret client "org/acme/db"))
                         "a successful delete invalidates cached values"))
      (tctx/with-org "beta"
                     (is (= "value-6" (vault/get-secret client "org/beta/db"))
                         "another tenant has a distinct cache key")
                     (is (= "value-7" (vault/get-secret (assoc client :token "other-token")
                                                        "org/beta/db"))
                         "different Vault credentials do not share cached values")))))
