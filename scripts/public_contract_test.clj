(ns public-contract-test
  (:require
    [babashka.fs :as fs]
    [babashka.process :as process]
    [clojure.string :as str]
    [clojure.test :refer [deftest is run-tests testing]]
    [graphden.packages.records :as records]
    [public-contract :as contract]))


(def rank
  {:namespace "app.marketplace" :name :semver-rank
   :args {:version {:type :text}} :lambda-params [:version] :return-type :int})


(def identity-definition
  {:namespace "core" :name :identity
   :args {:value {:type :text}} :return-type :int})


(def reader-definition
  {:namespace "app.marketplace" :name :_rank-reader :parent :identity
   :args {:value {:as :version}}})


(def composed-rank
  {:namespace "app.marketplace" :name :semver-rank
   :parent :_rank-reader :lambda-params [:version] :return-type :int})


(defn- compare-rank
  [candidate]
  (contract/compare-definitions [identity-definition rank]
                                [identity-definition reader-definition candidate]))


(deftest graph-extraction-must-preserve-public-own-slot-identity
  (let [id (records/fn-id "app.marketplace" :semver-rank)
        slot (records/slot-id id :version)
        report (compare-rank composed-rank)]
    (is (= 1 (:checked report)))
    (is (= [{:fn-id id :function :app.marketplace/semver-rank :source-file nil
             :kind :own-slot-removed :severity :breaking
             :old {:name "version" :type-fn-id (records/primitive-fn-id :text) :required true}
             :slot-id slot :slot-name "version"}]
           (:breaking report)))
    (testing "The same public own slot bridges to the new graph implementation"
      (let [preserved (compare-rank (assoc composed-rank :args (:args rank)))]
        (is (= 1 (:checked preserved)))
        (is (empty? (:changes preserved)))
        (is (contains? (get-in preserved [:new id :slots]) slot))))))


(deftest output-record-fields-are-not-public-input-slots
  (let [old (assoc rank :return-type {:fns [:list :any]})
        new (assoc old :return-type :jsonb)
        report (contract/compare-definitions [old] [new])
        id (records/fn-id "app.marketplace" :semver-rank)]
    (is (= #{(records/slot-id id :version)} (set (keys (get-in report [:old id :slots])))))
    (is (= [:return-type-changed] (mapv :kind (:changes report))))
    (is (empty? (:breaking report)))))


(deftest metadata-and-private-changes-do-not-recheck-unchanged-roots
  (let [unparsed {:namespace "fixture" :name :unmodified :parent :missing-parent}
        old [rank reader-definition unparsed]
        new [(assoc rank :description "A better description" :source-file "moved/fns.edn")
             (assoc reader-definition :args {:value {:value "internal change"}})
             unparsed]]
    (is (= {:checked 0 :old {} :new {} :changes [] :breaking []}
           (contract/compare-definitions old new)))))


(deftest public-root-removal-includes-namespace-identity
  (let [report (contract/compare-definitions [rank] [(assoc rank :namespace "other")])]
    (is (= [:public-fn-removed] (mapv :kind (:breaking report))))
    (is (= [:app.marketplace/semver-rank] (mapv :function (:breaking report)))))
  (is (empty? (:breaking (contract/compare-definitions
                           [(assoc rank :name :_private)] [])))))


(deftest declared-contract-deltas-do-not-pretend-to-prove-subtyping
  (let [old (assoc-in rank [:args :version :required] false)
        new (assoc rank :args {:version {:type :int :required true}}
                   :lambda-params [] :return-type :text)
        report (contract/compare-definitions [old] [new])]
    (is (= #{:slot-type-changed :slot-required-changed :lambda-params-changed :return-type-changed}
           (set (map :kind (:changes report)))))
    (is (= [:slot-required-changed] (mapv :kind (:breaking report))))
    (is (every? #(= :review (:severity %))
                (remove #(= :slot-required-changed (:kind %)) (:changes report))))))


(deftest newly-exposed-own-input-is-not-proven-new-required-input
  (let [old [identity-definition reader-definition composed-rank]
        new [identity-definition reader-definition (assoc composed-rank :args (:args rank))]
        report (contract/compare-definitions old new)]
    (is (= [:own-slot-added] (mapv :kind (:changes report))))
    (is (empty? (:breaking report)))))


(deftest baseline-selection-is-explicit-and-ci-aware
  (is (= {:base "ci-commit"} (contract/cli-options [] "ci-commit")))
  (is (= {:base "explicit" :candidate "candidate"}
         (contract/cli-options ["--base" "explicit" "--candidate" "candidate"] "ci-commit")))
  (is (= {:base "HEAD^"} (contract/cli-options [] "")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Expected --base"
        (contract/cli-options ["--unknown" "x"] nil)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Expected --base"
        (contract/cli-options ["--base"] nil))))


(defn- git!
  [root & args]
  (str/trim (:out (process/check @(process/process (into ["git"] args)
                                                   {:dir (str root) :out :string :err :string})))))


(defn- write-package!
  [root definitions]
  (let [path (fs/path root "resources/packages/fixture/main/fns.edn")]
    (fs/create-dirs (fs/parent path))
    (spit (str path) (pr-str {:namespace "app.marketplace"
                              :fns (mapv #(dissoc % :namespace) definitions)}))))


(deftest git-candidate-and-working-tree-checks-use-the-requested-baseline
  (let [root (fs/create-temp-dir {:prefix "graphden-public-contract-"})
        script (str (fs/canonicalize "scripts/public_contract.clj"))
        classpath (str (fs/canonicalize "src") ":" (fs/canonicalize "scripts"))]
    (try
      (git! root "init" "-q")
      (git! root "config" "user.name" "Contract test")
      (git! root "config" "user.email" "contract@example.invalid")
      (write-package! root [identity-definition rank])
      (git! root "add" ".")
      (git! root "commit" "-qm" "baseline")
      (let [base (git! root "rev-parse" "HEAD")]
        (write-package! root [identity-definition reader-definition composed-rank])
        (is (= [:own-slot-removed]
               (mapv :kind (:breaking (contract/check-revisions root base nil)))))
        (git! root "add" ".")
        (git! root "commit" "-qm" "candidate")
        (let [result @(process/process ["bb" "--classpath" classpath script "--candidate" "HEAD"]
                                       {:dir (str root) :out :string :err :string
                                        :extra-env {"GRAPHDEN_CI_SINCE" base}})]
          (is (= 1 (:exit result)) (:err result))
          (is (str/includes? (:out result) ":own-slot-removed")))
        (write-package! root [identity-definition reader-definition (assoc composed-rank :args (:args rank))])
        (is (empty? (:breaking (contract/check-revisions root base nil))))
        (is (= [:own-slot-removed]
               (mapv :kind (:breaking (contract/check-revisions root base "HEAD"))))))
      (is (thrown? Exception (contract/check-revisions root "missing-baseline" nil)))
      (finally (fs/delete-tree root)))))


(let [{:keys [fail error]} (run-tests 'public-contract-test)]
  (when (pos? (+ fail error)) (System/exit 1)))
