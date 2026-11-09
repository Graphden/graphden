(ns ^:serial structured-input-dry-run-test
  "Run: bb --classpath src:scripts scripts/structured_input_dry_run_test.clj."
  (:require
    [babashka.fs :as fs]
    [babashka.process :as process]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [clojure.test :refer [deftest is run-tests]]
    [graphden.types.core.shapes :as shapes]
    [structured-input-dry-run :as dry]
    [structured-input-inventory :as inventory]))


(defn- git!
  [root & args]
  (process/check @(process/process (into ["git"] args) {:dir (str root) :out :string :err :string})))


(defn- write!
  [root path value]
  (let [file (fs/path root path)]
    (fs/create-dirs (fs/parent file))
    (spit (str file) (pr-str value))))


(defn- package!
  [root name dependencies modules]
  (write! root (str "resources/packages/" name "/package.edn")
          {:name name :dependencies dependencies :modules (mapv first modules)})
  (doseq [[module ns definitions] modules]
    (write! root (str "resources/packages/" name "/" module "/fns.edn")
            {:namespace ns :fns definitions})))


(defn- with-repository
  [f]
  (let [root (fs/create-temp-dir {:prefix "structured-source-dryrun-"})]
    (try
      (git! root "init" "--quiet")
      (git! root "-c" "user.name=Dry Run Test" "-c" "user.email=dryrun@example.invalid"
            "-c" "commit.gpgsign=false" "commit" "--allow-empty" "-m" "fixture")
      (f root [{:label "fixture" :root root}])
      (finally (fs/delete-tree root)))))


(defn- package-result
  [report name]
  (first (filter #(= name (:name %)) (:packages report))))


(defn- codes
  [report]
  (set (map :code (:blockers report))))


(def identity-fn
  {:name :id :args {:x :any} :return-type :any})


(deftest imports-freeze-reference-context-before-sibling-collisions
  (with-repository
    (fn [root roots]
      (package! root "base" [] [["main" "base" [identity-fn {:name :same :args {} :return-type :any}]]])
      (package! root "a" ["base"] [["main" "a" [{:name :reader :parent :id :args {:x :same}}]]])
      (package! root "b" ["base"] [["main" "b" [{:name :same :args {} :return-type :any}]]])
      (package! root "client" ["a" "b"] [["main" "client" [{:name :call :parent :base/id
                                                            :args {:x :a/reader}}]]])
      (let [report (dry/dry-run roots)]
        (is (= :prepared (:status (package-result report "client"))))
        (is (= {:supported 5 :blocked 0} (:counts report)))
        (is (empty? (:blockers report)))))))


(deftest all-modules-of-package-share-forward-reference-context
  (with-repository
    (fn [root roots]
      (package! root "forward" [] [["first" "fwd" [{:name :child :parent :id :args {:x 1}}]]
                                   ["later" "fwd" [identity-fn]]])
      (let [report (dry/dry-run roots)]
        (is (= {:supported 2 :blocked 0} (:counts report)))
        (is (true? (:coverage-complete? report)))))))


(deftest unrelated-package-cannot-satisfy-undeclared-reference
  (with-repository
    (fn [root roots]
      (package! root "provider" [] [["main" "provider" [identity-fn]]])
      (package! root "consumer" [] [["main" "consumer" [{:name :bad :parent :provider/id}]]])
      (package! root "missing" ["absent"] [["main" "missing" [{:name :pending :parent :provider/id}]]])
      (let [report (dry/dry-run roots)]
        (is (= :blocked (:status (package-result report "consumer"))))
        (is (contains? (codes report) :definition-preparation-failed))
        (is (contains? (codes report) :missing-package-dependency))
        (is (= {:supported 1 :blocked 2} (:counts report)))))))


(deftest independent-preparation-errors-and-cycles-are-all-reported
  (with-repository
    (fn [root roots]
      (package! root "bad" [] [["main" "bad" [{:name :one :parent :missing/one}
                                              {:name :two :parent :missing/two}]]])
      (package! root "cycle-a" ["cycle-b"] [["main" "a" [identity-fn]]])
      (package! root "cycle-b" ["cycle-a"] [["main" "b" [identity-fn]]])
      (let [report (dry/dry-run roots)]
        (is (= 2 (count (:blockers (package-result report "bad")))))
        (is (= 2 (count (filter #(= :cyclic-package-dependency (:code %)) (:blockers report)))))
        (is (= {:supported 0 :blocked 4} (:counts report)))))))


(deftest report-is-deterministic-payload-free-and-cli-readable
  (with-repository
    (fn [root roots]
      (package! root "demo" [] [["main" "demo" [identity-fn
                                                {:name :child :parent :id :args {:x {:value "PRIVATE-PAYLOAD"}}}]]])
      (let [manifest (inventory/inventory roots)
            report (dry/dry-run roots manifest)
            serialized (inventory/report-text report)
            cli (process/check @(process/process ["bb" "--classpath" "src:scripts"
                                                  "scripts/structured_input_dry_run.clj"
                                                  "--repo" "fixture" (str root)]
                                                 {:out :string :err :string}))]
        (is (= serialized (inventory/report-text (dry/dry-run roots))))
        (is (= report (edn/read-string (:out cli))))
        (is (not (str/includes? serialized "PRIVATE-PAYLOAD")))
        (is (not (str/includes? serialized (str root))))
        (is (= 64 (count (:report-sha256 report))))))))


(deftest checksum-and-inventory-drift-invalidate-results
  (with-repository
    (fn [root roots]
      (package! root "demo" [] [["main" "demo" [identity-fn]]])
      (let [manifest (inventory/inventory roots)
            path "resources/packages/demo/main/fns.edn"
            read-source dry/checked-source
            changed? (atom false)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Source differs"
              (dry/checked-source root path "incorrect-sha")))
        (with-redefs [dry/checked-source
                      (fn [r p checksum]
                        (let [value (read-source r p checksum)]
                          (when (and (= path p) (compare-and-set! changed? false true))
                            (write! root path {:namespace "demo" :fns []}))
                          value))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Inventory changed"
                (dry/dry-run roots))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"pinned manifest"
              (dry/dry-run roots manifest)))))))


(deftest marker-registries-are-isolated-from-process-and-unrelated-packages
  (with-repository
    (fn [root roots]
      (let [before (shapes/markers-snapshot)]
        (package! root "marker" [] [["main" "marker" [{:name :custom-label :marker {:hide-result? true}}]]])
        (package! root "consumer" ["marker"] [["main" "consumer" [{:name :typed
                                                                   :args {:x [:custom-label :text]}
                                                                   :return-type :text}]]])
        (package! root "unrelated" [] [["main" "unrelated" [{:name :typed
                                                             :args {:x [:custom-label :text]}
                                                             :return-type :text}]]])
        (let [report (dry/dry-run roots)]
          (is (= {:supported 1 :blocked 0} (:counts (package-result report "consumer"))))
          (is (= {:supported 0 :blocked 1} (:counts (package-result report "unrelated"))))
          (is (= before (shapes/markers-snapshot))))))))


(deftest explicit-roots-support-imports-and-namespace-less-modules
  (with-repository
    (fn [provider _]
      (with-repository
        (fn [consumer _]
          (package! provider "provider" [] [["main" nil [identity-fn]]])
          (package! consumer "consumer" ["provider"] [["main" "consumer"
                                                       [{:name :child :parent :id :args {:x false}}]]])
          (let [roots [{:label "provider" :root provider} {:label "consumer" :root consumer}]
                report (dry/dry-run roots)]
            (is (= {:supported 2 :blocked 0} (:counts report)))
            (is (= report (dry/dry-run (reverse roots))))))))))


(deftest duplicate-packages-and-orphans-retain-source-coverage
  (with-repository
    (fn [one _]
      (with-repository
        (fn [two _]
          (package! one "duplicate" [] [["main" "one" [identity-fn]]])
          (package! two "duplicate" [] [["main" "two" [identity-fn (assoc identity-fn :name :second)]]])
          (write! one "resources/packages/orphan/main/fns.edn" {:namespace "orphan" :fns [identity-fn]})
          (let [report (dry/dry-run [{:label "one" :root one} {:label "two" :root two}])]
            (is (= {:supported 0 :blocked 4} (:counts report)))
            (is (true? (:coverage-complete? report)))
            (is (= #{:duplicate-package-name :module-without-package} (codes report)))))))))


(deftest duplicate-root-identities-do-not-collapse-by-import-order
  (with-repository
    (fn [root roots]
      (package! root "a" [] [["main" nil [identity-fn]]])
      (package! root "b" [] [["main" "" [identity-fn]]])
      (package! root "consumer" ["a" "b"] [["main" "consumer" [{:name :child :parent :id}]]])
      (let [report (dry/dry-run roots)]
        (is (= :blocked (:status (package-result report "consumer"))))
        (is (contains? (codes report) :duplicate-visible-definition))))))


(deftest malformed-dependency-specifications-are-safe-blockers
  (with-repository
    (fn [root roots]
      (package! root "number" 7 [["main" "number" [identity-fn]]])
      (package! root "map" {"private" "PRIVATE-DEPENDENCY-DATA"} [["main" "map" [identity-fn]]])
      (let [report (dry/dry-run roots)]
        (is (= #{:unsupported-package-dependencies} (codes report)))
        (is (= {:supported 0 :blocked 2} (:counts report)))
        (is (not (str/includes? (inventory/report-text report) "PRIVATE-DEPENDENCY-DATA")))))))


(let [{:keys [fail error]} (run-tests 'structured-input-dry-run-test)]
  (when (pos? (+ fail error)) (System/exit 1)))
