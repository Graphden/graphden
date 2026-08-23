(ns graphden.cli-test
  "The graph⇄git CLI (`graphden.cli`): opt parsing, and the export→dir→
   import round-trip against a stub hub — the CLI's whole contract is
   'lay the HTTP bundle out with git-format, post it back intact'."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [graphden.cli :as cli]
    [graphden.packages.records.wire :as wire]
    [org.httpkit.server :as hk])
  (:import
    (java.nio.file
      Files)))


(deftest parse-opts-handles-flags-values-and-positionals
  (is (= {:args ["DIR"] :url "http://x" :token "t" :target "b" :create true :prune true}
         (#'cli/parse-opts ["DIR" "--url" "http://x" "--token" "t"
                            "--target" "b" "--create" "--prune"]))))


(def ^:private fixture-bundle
  {:fns [{:name :cli-a :namespace "cli.demo" :parent :add :args {:nums [1 2]}}
         {:name :cli-b :parent :const :args {:value "root"}}]
   :namespaces ["cli.demo"]
   :secrets []
   :secret-paths-included? false})


(deftest export-then-import-round-trips-over-http
  (let [import-req (atom nil)
        handler (fn [req]
                  (condp = (:uri req)
                    "/api/export/graph"
                    {:status 200 :headers {"Content-Type" "application/edn"}
                     :body (pr-str fixture-bundle)}
                    "/api/import/graph"
                    (do (reset! import-req {:query (:query-string req)
                                            :auth (get-in req [:headers "authorization"])
                                            :body (slurp (:body req))})
                        {:status 200 :headers {"Content-Type" "application/json"}
                         :body "{\"ok\":true}"})
                    {:status 404 :body ""}))
        stop (hk/run-server handler {:port 0})
        url (str "http://localhost:" (:local-port (meta stop)))
        dir (str (Files/createTempDirectory "gd-cli-test" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (testing "export lays the snapshot out on disk"
        (is (zero? (cli/export! {:url url :token "tok" :out dir :branch "main"})))
        (is (java.io.File/.exists (io/file dir "graphden.edn")))
        (is (java.io.File/.exists (io/file dir "fns/cli.demo.edn")))
        (is (java.io.File/.exists (io/file dir "fns/_root.edn")))
        (is (str/includes? (slurp (io/file dir "graphden.edn")) ":branch \"main\"")))
      (testing "import reads the snapshot back and posts the SAME defs"
        (is (zero? (cli/import! {:args [dir] :url url :token "tok"
                                 :target "imp/cli" :create true :prune true})))
        (let [{:keys [query auth body]} @import-req]
          (is (= "target=imp/cli&create=true&prune=true" query))
          (is (= "Bearer tok" auth))
          (is (= (set (:fns fixture-bundle))
                 (set (:fns (edn/read-string {:readers wire/wire-readers} body))))
              "the posted bundle is def-identical to what export received")))
      (testing "a stale file from the previous snapshot is cleaned on re-export"
        (spit (io/file dir "fns/ghost.edn") "{:namespace \"ghost\" :fns []}")
        (is (zero? (cli/export! {:url url :token "tok" :out dir})))
        (is (not (java.io.File/.exists (io/file dir "fns/ghost.edn")))
            "export = the snapshot; deleted namespaces become git deletions"))
      (finally (stop)))))


(deftest import-propagates-a-server-refusal
  (let [handler (fn [_] {:status 404 :body "{\"ok\":false,\"reason\":\"branch-not-found\"}"})
        stop (hk/run-server handler {:port 0})
        url (str "http://localhost:" (:local-port (meta stop)))
        dir (str (Files/createTempDirectory "gd-cli-ref" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (io/make-parents (io/file dir "fns/x.edn"))
      (spit (io/file dir "fns/cli.demo.edn") "{:namespace \"cli.demo\" :fns []}")
      (is (= 1 (cli/import! {:args [dir] :url url :token "t" :target "x"}))
          "a non-200 from the server is exit code 1")
      (finally (stop)))))


(deftest push-and-pull-transfer-between-two-stub-instances
  ;; push: local → hub as push/<branch> (create+prune); pull: hub → local
  ;; as hub/<branch>. Assert the wire calls each direction makes.
  (let [hub-import (atom nil)
        local-import (atom nil)
        mk-stub (fn [import-sink]
                  (hk/run-server
                    (fn [req]
                      (condp = (:uri req)
                        "/api/export/graph"
                        {:status 200 :headers {"Content-Type" "application/edn"}
                         :body (pr-str fixture-bundle)}
                        "/api/import/graph"
                        (do (reset! import-sink {:query (:query-string req)
                                                 :auth (get-in req [:headers "authorization"])})
                            {:status 200 :body "{\"ok\":true}"})
                        {:status 404 :body ""}))
                    {:port 0}))
        hub (mk-stub hub-import)
        local (mk-stub local-import)
        hub-url (str "http://localhost:" (:local-port (meta hub)))
        local-url (str "http://localhost:" (:local-port (meta local)))
        base {:local-url local-url :local-token "lt"
              :hub-url hub-url :hub-token "ht"}]
    (try
      (testing "push lands on the HUB as push/main, pruned, hub bearer"
        (is (zero? (cli/push! base)))
        (is (= {:query "target=push/main&create=true&prune=true"
                :auth "Bearer ht"} @hub-import))
        (is (nil? @local-import) "push never writes locally"))
      (testing "pull lands LOCALLY as hub/main with the local bearer"
        (is (zero? (cli/pull! (assoc base :no-prune true))))
        (is (= {:query "target=hub/main&create=true"
                :auth "Bearer lt"} @local-import)))
      (finally (hub) (local)))))


(deftest bundle-diff-classifies-added-removed-changed
  (let [current [{:namespace "a" :name :keep :parent :add :args {:nums [1]}}
                 {:namespace "a" :name :gone :parent :add :args {:nums [2]}}
                 {:namespace "a" :name :edit :parent :add :args {:nums [3]}}]
        incoming [{:namespace "a" :name :keep :parent :add :args {:nums [1]}}
                  {:namespace "a" :name :edit :parent :add :args {:nums [9]}}
                  {:namespace "a" :name :new :parent :add :args {:nums [4]}}]
        d (cli/bundle-diff current incoming)]
    (is (= [:new] (map :name (:added d))))
    (is (= [:gone] (map :name (:removed d))))
    (is (= [:edit] (:changed d)))
    (testing "a re-export with no real change is silent"
      (is (= {:added [] :removed [] :changed []}
             (cli/bundle-diff current current))))))
