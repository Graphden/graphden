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


(deftest parse-opts-valueless-flags-and-empty-argv
  (is (= {:args []} (#'cli/parse-opts [])))
  (is (= {:args [] :no-prune true :dry-run true :include-secret-paths true}
         (#'cli/parse-opts ["--no-prune" "--dry-run" "--include-secret-paths"]))
      "value-less flags parse to true and consume ONE token"))


(deftest missing-required-options-are-a-usage-error
  (let [e (try (cli/export! {:url "http://x" :token "" :other "y"})
               (catch clojure.lang.ExceptionInfo e e))]
    (is (= :cli/usage (:type (ex-data e)))
        "usage errors carry :cli/usage so -main exits 2, not 1")
    (is (= "missing required option(s): --token --out" (ex-message e))
        "blank counts as missing; every missing flag is listed by its CLI name")))


(deftest fail-prints-to-stderr-and-returns-the-code
  (let [err (java.io.StringWriter.)]
    (binding [*err* err]
      (is (= 2 (#'cli/fail! 2 "boom"))))
    (is (= "boom\n" (str err)))))


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


(deftest diff-and-dry-run-preview-without-writing
  ;; One stub instance whose export flips mid-test: export! snapshots the
  ;; ORIGINAL bundle to disk, then the server moves on — diff! / --dry-run
  ;; must print the snapshot-vs-server delta and never touch /api/import.
  (let [bundle (atom fixture-bundle)
        imports (atom 0)
        handler (fn [req]
                  (condp = (:uri req)
                    "/api/export/graph"
                    {:status 200 :headers {"Content-Type" "application/edn"}
                     :body (pr-str @bundle)}
                    "/api/import/graph"
                    (do (swap! imports inc) {:status 200 :body "{\"ok\":true}"})
                    {:status 404 :body ""}))
        stop (hk/run-server handler {:port 0})
        url (str "http://localhost:" (:local-port (meta stop)))
        dir (str (Files/createTempDirectory "gd-cli-diff" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (is (zero? (cli/export! {:url url :token "t" :out dir})))
      ;; server now has: cli-a CHANGED, cli-b gone, cli-new added
      (reset! bundle {:fns [{:name :cli-a :namespace "cli.demo" :parent :add :args {:nums [9]}}
                            {:name :cli-new :parent :const :args {:value 1}}]})
      (testing "diff! prints the snapshot-vs-branch preview and returns 0"
        (let [out (with-out-str
                    (is (zero? (cli/diff! {:args [dir] :url url :token "t"}))))]
          (is (str/includes? out "diff vs branch 'main':"))
          (is (str/includes? out "+ added:   1"))
          (is (str/includes? out "- removed: 1"))
          (is (str/includes? out "~ changed: 1"))
          (is (str/includes? out "+ :cli-b") "the snapshot's cli-b is new to the server")
          (is (str/includes? out "- :cli-new"))
          (is (str/includes? out "~ :cli-a"))))
      (testing "import --dry-run prints the same preview instead of posting"
        (let [out (with-out-str
                    (is (zero? (cli/import! {:args [dir] :url url :token "t"
                                             :target "main" :dry-run true}))))]
          (is (str/includes? out "diff vs branch 'main':"))))
      (is (zero? @imports) "no write ever reached /api/import/graph")
      (finally (stop)))))


(deftest import-from-an-empty-dir-is-a-usage-error
  (let [dir (str (Files/createTempDirectory "gd-cli-empty" (make-array java.nio.file.attribute.FileAttribute 0)))
        e (try (cli/import! {:args [dir] :url "http://unused" :token "t" :target "b"})
               (catch clojure.lang.ExceptionInfo e e))]
    (is (= :cli/usage (:type (ex-data e))))
    (is (str/includes? (ex-message e) "no snapshot under"))))


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
             (cli/bundle-diff current current))))
    (testing "≥2 added AND ≥2 removed — sort by [ns name], never compare the
              maps themselves (that ClassCastExceptions), nil ns must not NPE"
      (let [cur [{:namespace "a" :name :r1 :parent :add :args {}}
                 {:namespace "b" :name :r2 :parent :add :args {}}]
            incoming [{:namespace "z" :name :a2 :parent :add :args {}}
                      {:namespace nil :name :a1 :parent :add :args {}}]
            d2 (cli/bundle-diff cur incoming)]
        (is (= [:a1 :a2] (map :name (:added d2))) "added sorted by [ns name]; nil ns first, no crash")
        (is (= [:r1 :r2] (map :name (:removed d2))) "removed sorted, no crash")))))
