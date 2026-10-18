(ns artifact-identity-test
  (:require
    [artifact-identity]
    [babashka.fs :as fs]
    [babashka.process :as p]
    [clojure.test :refer [deftest is run-tests]])
  (:import
    (java.util.zip
      ZipEntry
      ZipOutputStream)))


(defn- git!
  [dir & args]
  (let [result (apply p/shell {:dir dir :out :string :err :string :continue true}
                      "git" args)]
    (when-not (zero? (:exit result))
      (throw (ex-info "Fixture git command failed" {:args args :err (:err result)})))))


(defn- commit!
  [dir]
  (git! dir "add" ".")
  (git! dir "-c" "user.name=Artifact Test" "-c" "user.email=artifact@example.invalid"
        "commit" "-qm" "fixture"))


(defn- jar!
  [path manifest]
  (with-open [zip (ZipOutputStream. (java.io.FileOutputStream. path))]
    (ZipOutputStream/.putNextEntry zip (ZipEntry. artifact-identity/manifest-path))
    (ZipOutputStream/.write zip (String/.getBytes (str (pr-str manifest) "\n") "UTF-8"))
    (ZipOutputStream/.closeEntry zip)))


(deftest shipped-docs-and-deps-invalidate-old-jar
  (let [dir (str (fs/create-temp-dir {:prefix "artifact-inputs-"}))
        jar (str dir "/old.jar")]
    (try
      (git! dir "init" "-q")
      (fs/create-dirs (str dir "/src"))
      (fs/create-dirs (str dir "/resources/packages/mcp/mcp"))
      (spit (str dir "/src/app.clj") "(ns app)\n")
      (spit (str dir "/resources/packages/mcp/mcp/ai-context.md") "old\n")
      (spit (str dir "/deps.edn") "{:deps {}}\n")
      (commit! dir)
      (let [first-inputs (artifact-identity/core-inputs dir)]
        (jar! jar first-inputs)
        (is (= first-inputs (:inputs (artifact-identity/verify-jar! jar first-inputs))))
        (spit (str dir "/resources/packages/mcp/mcp/ai-context.md") "new\n")
        (is (= "dirty" (:core-sha (artifact-identity/core-inputs dir)))
            "A local preview builds, but cannot claim a release SHA")
        (commit! dir)
        (let [docs-inputs (artifact-identity/core-inputs dir)]
          (is (not= (:source-digest first-inputs) (:source-digest docs-inputs)))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"differ from source"
                (artifact-identity/verify-jar! jar docs-inputs)))
          (spit (str dir "/deps.edn") "{:deps {example/x {:mvn/version \"1\"}}}\n")
          (commit! dir)
          (let [deps-inputs (artifact-identity/core-inputs dir)]
            (is (not= (:deps-digest docs-inputs) (:deps-digest deps-inputs)))
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"differ from source"
                  (artifact-identity/verify-jar! jar deps-inputs))))))
      (finally (fs/delete-tree dir)))))


(deftest missing-manifest-is-rejected
  (let [path (str (fs/create-temp-file {:prefix "artifact-empty-" :suffix ".jar"}))]
    (try
      (with-open [zip (ZipOutputStream. (java.io.FileOutputStream. path))]
        (ZipOutputStream/.putNextEntry zip (ZipEntry. "other.txt"))
        (ZipOutputStream/.closeEntry zip))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exactly one"
            (artifact-identity/read-jar-manifest path)))
      (finally (fs/delete-if-exists path)))))


(deftest hidden-worktree-edit-cannot-claim-committed-sha
  (let [dir (str (fs/create-temp-dir {:prefix "artifact-hidden-"}))]
    (try
      (git! dir "init" "-q")
      (fs/create-dirs (str dir "/src"))
      (spit (str dir "/src/app.clj") "(ns app)\n")
      (spit (str dir "/deps.edn") "{:deps {}}\n")
      (commit! dir)
      (git! dir "update-index" "--assume-unchanged" "src/app.clj")
      (spit (str dir "/src/app.clj") "(ns changed)\n")
      (is (= "dirty" (:core-sha (artifact-identity/core-inputs dir))))
      (finally (fs/delete-tree dir)))))


(deftest archive-without-git-keeps-local-build-identity
  (let [dir (str (fs/create-temp-dir {:prefix "artifact-archive-"}))]
    (try
      (fs/create-dirs (str dir "/src"))
      (spit (str dir "/src/app.clj") "(ns app)\n")
      (spit (str dir "/deps.edn") "{:deps {}}\n")
      (is (= "unversioned" (:core-sha (artifact-identity/core-inputs dir))))
      (finally (fs/delete-tree dir)))))


(let [{:keys [fail error]} (run-tests 'artifact-identity-test)]
  (when (pos? (+ fail error)) (System/exit 1)))
