(ns structured-input-inventory-test
  "Focused source inventory tests; run with bb scripts/structured_input_inventory_test.clj."
  (:require
    [babashka.fs :as fs]
    [babashka.process :as process]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [clojure.test :refer [deftest is run-tests testing]]
    [structured-input-inventory :as inventory]))


(defn- git!
  [root & args]
  (process/check @(process/process (into ["git"] args) {:dir (str root) :out :string :err :string})))


(defn- write-file!
  [root path content]
  (let [file (fs/path root path)]
    (fs/create-dirs (fs/parent file))
    (spit (str file) content)))


(defn- commit!
  [root]
  (git! root "add" ".")
  (git! root "-c" "user.name=Inventory Test" "-c" "user.email=inventory@example.invalid"
        "-c" "commit.gpgsign=false" "commit" "-m" "fixture"))


(defn- with-repository
  [f]
  (let [root (fs/create-temp-dir {:prefix "structured-input-inventory-"})]
    (try
      (git! root "init" "--quiet")
      (write-file! root "resources/packages/demo/package.edn"
                   (pr-str {:name "demo" :version "1.0.0" :dependencies [] :modules ["main"]}))
      (write-file! root "resources/packages/demo/main/fns.edn"
                   (pr-str {:namespace "demo.main"
                            :fns [{:name :base :args {:x {:type :int :required false :value 9}}
                                   :return-type :int}
                                  {:name :child :parent :base :args {:x {:value 4}}}]}))
      (commit! root)
      (f root)
      (finally (fs/delete-tree root)))))


(defn- scan
  [root]
  (inventory/inventory [{:label "fixture" :root root}]))


(deftest canonical-corpus-and-module-reconciliation
  (with-repository
    (fn [root]
      (write-file! root "target/classes/packages/demo/copied/fns.edn" "not EDN")
      (write-file! root "external-packages/other/resources/packages/demo/main/fns.edn" "not EDN")
      (write-file! root "resources/packages/demo/extra/fns.edn" "{:namespace \"demo.extra\" :fns []}")
      (write-file! root "resources/packages/orphan/main/fns.edn" "{:fns []}")
      (write-file! root "resources/packages/demo/package.edn"
                   "{:name \"demo\" :modules [\"missing\" \"main\" \"main\"]}")
      (let [report (scan root)
            repo (first (:repositories report))
            package (first (:packages repo))]
        (is (= {:packages 1 :files 3 :definitions 2} (:totals report)))
        (is (= {:base-declaration 1 :single-parent 1} (:definition-kinds repo)))
        (is (= ["resources/packages/demo/missing/fns.edn"] (:missing package)))
        (is (= ["resources/packages/demo/extra/fns.edn"] (:unlisted package)))
        (is (= ["main"] (:duplicate-modules package)))
        (is (= ["resources/packages/orphan/main/fns.edn"] (:modules-without-package repo)))
        (is (= 4 (count (get-in repo [:source-identity :file-checksums]))))
        (is (= :syntax-only (get-in report [:coverage :classification])))))))


(deftest syntax-traversal-preserves-data-boundaries
  (let [forms (inventory/definition-forms
                {:name :root :parent :list :lambda-params []
                 :args {:payload {:value {:parent :fake :args {:x {:secret-path "payload"}}}}
                        :inline {:parent :get :args {:key {:value :id} :coll {:as :record}}}
                        :items {:append [{:parent :const :args {:value false}}] :closed true}
                        :data {:arbitrary {:parent :fake}}
                        :vector [1 :unresolved]}})]
    (is (= 2 (:inline-anonymous-definitions forms)))
    (is (= 2 (:value forms)))
    (is (= 1 (:as forms)))
    (is (= 1 (:literal-map-candidates forms)))
    (is (= 1 (:bare-vector-candidates forms)))
    (is (= 1 (:bare-keyword-candidates forms)))
    (is (= 1 (:lambda-params-empty forms)))
    (is (= 2 (:lambda-params-absent forms)))
    (is (nil? (:secret-path forms))))
  (let [forms (inventory/definition-forms
                {:name :base :args {:x {:type :int :value nil :required false}}})]
    (is (= 1 (:spec-defaults forms)))
    (is (= 1 (:optional-specs forms)))
    (is (nil? (:value forms)))))


(deftest stable-report-and-content-identity
  (with-repository
    (fn [root]
      (let [before (scan root)
            text (inventory/report-text before)
            source-id (get-in before [:repositories 0 :source-identity])]
        (testing "Repeated scans and EDN serialization are stable and portable"
          (is (= text (inventory/report-text (scan root))))
          (is (= before (edn/read-string text)))
          (is (not (str/includes? text (str root))))
          (is (false? (:dirty? source-id))))
        (write-file! root "resources/packages/demo/main/fns.edn"
                     "{:namespace \"demo.main\" :fns []}\n")
        (let [after (get-in (scan root) [:repositories 0 :source-identity])]
          (testing "Uncommitted bytes change the inventory without changing HEAD"
            (is (= (:git-head source-id) (:git-head after)))
            (is (true? (:dirty? after)))
            (is (not= (:file-checksums source-id) (:file-checksums after)))
            (is (not= (:input-sha256 source-id) (:input-sha256 after)))))))))


(deftest multiple-roots-and-cli-output
  (with-repository
    (fn [root]
      (with-repository
        (fn [other]
          (let [roots [{:label "z" :root root} {:label "a" :root other}]
                report (inventory/inventory roots)
                cli (process/check @(process/process ["bb" "scripts/structured_input_inventory.clj"
                                                      "--repo" "z" (str root) "--repo" "a" (str other)]
                                                     {:out :string :err :string}))]
            (is (= ["a" "z"] (mapv :label (:repositories report))))
            (is (= {:packages 2 :files 2 :definitions 4} (:totals report)))
            (is (= report (inventory/inventory (reverse roots))))
            (is (= report (edn/read-string (:out cli))))))))))


(deftest invalid-input-is-an-error
  (doseq [source ["#=(System/exit 0)" "#untrusted/tag {}" "{} {}" "" "{:fns [}"]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cannot read inventory EDN"
          (inventory/read-edn "fixture.edn" source))))
  (doseq [args [[] ["--repo"] ["--repo" "x"] ["--unknown" "x" "."]]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Usage:|Expected --repo"
          (inventory/cli-repositories args))))
  (with-repository
    (fn [root]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"distinct labels"
            (inventory/inventory [{:label "x" :root root} {:label "x" :root root}])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"roots must be distinct"
            (inventory/inventory [{:label "x" :root root} {:label "y" :root (fs/path root ".")}])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"existing directory"
            (scan (fs/path root "absent"))))
      (write-file! root "resources/packages/demo/package.edn"
                   "{:name \"demo\" :modules [\"../elsewhere\"]}")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"relative module paths"
            (scan root))))))


(deftest concurrent-input-change-invalidates-report
  (with-repository
    (fn [root]
      (write-file! root "unrelated.txt" "already dirty")
      (let [original-read inventory/read-edn
            changed? (atom false)]
        (with-redefs [inventory/read-edn
                      (fn [path content]
                        (let [result (original-read path content)]
                          (when (and (str/ends-with? path "/fns.edn")
                                     (compare-and-set! changed? false true))
                            (write-file! root path "{:namespace \"changed\" :fns []}"))
                          result))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"inputs changed during scan"
                (scan root))))))))


(let [{:keys [fail error]} (run-tests 'structured-input-inventory-test)]
  (when (pos? (+ fail error)) (System/exit 1)))
