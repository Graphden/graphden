(ns graphden.packages.git-format-test
  "The git-facing text layout (`packages.git-format`): byte-determinism,
   the module-file shape, and the text-level fixpoint over the full
   package corpus — `files->bundle ∘ bundle->files` must be
   parse-identical to the bundle it started from."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [graphden.executor.composition.deps :as deps]
    [graphden.packages.export :as export]
    [graphden.packages.git-format :as gf]
    [graphden.packages.loader :as loader]
    [graphden.packages.records.parse :as parse]))


(def ^:private fixture
  [{:name :alpha :namespace "acme.demo" :parent :add :args {:nums [1 2]}}
   {:name :beta :namespace "acme.demo" :parent :alpha
    :args {:nums {:append [3]}} :description "extends alpha"}
   {:name :gamma :namespace "acme.other" :parent :const :args {:value "s"}}
   {:name :rooty :parent :const :args {:value 42}}])


(deftest lays-out-one-module-file-per-namespace-plus-manifest
  (let [files (gf/bundle->files fixture {:branch "main"})]
    (testing "file set: one per namespace, root defs in _root, manifest"
      (is (= #{"fns/acme.demo.edn" "fns/acme.other.edn" "fns/_root.edn" "graphden.edn"}
             (set (keys files)))))
    (testing "a module file is the fns.edn module shape"
      (let [demo (get files "fns/acme.demo.edn")]
        (is (str/includes? demo "{:namespace \"acme.demo\""))
        (is (str/includes? demo ":name :alpha"))
        (is (not (str/includes? demo ":namespace \"acme.demo\"\n :fns\n [{:name :alpha\n   :namespace"))
            "per-def :namespace is folded into the module header")))
    (testing "defs inside a file sort by name"
      (let [demo (get files "fns/acme.demo.edn")]
        (is (< (str/index-of demo ":name :alpha")
               (str/index-of demo ":name :beta")))))
    (testing "the manifest records format, files and the caller's meta"
      (let [manifest (get files "graphden.edn")]
        (is (str/includes? manifest (str ":format " gf/format-version)))
        (is (str/includes? manifest ":branch \"main\""))))))


(deftest round-trips-the-fixture-exactly
  (let [files (gf/bundle->files fixture)
        back (gf/files->bundle files)]
    (is (= (set fixture) (set back))
        "files->bundle returns the same defs (order may differ — set compare)")))


(deftest output-is-byte-deterministic
  (testing "same input twice → identical bytes"
    (is (= (gf/bundle->files fixture) (gf/bundle->files fixture))))
  (testing "shuffled input order → identical bytes (layout sorts)"
    (is (= (gf/bundle->files fixture)
           (gf/bundle->files (vec (reverse fixture)))))))


(deftest refuses-a-newer-format-and-a-moved-module
  (testing "a manifest from the future is refused, not misread"
    (is (thrown-with-msg? Exception #"newer format"
          (gf/files->bundle {"graphden.edn" (str "{:format " (inc gf/format-version) "}")}))))
  (testing "a module file whose :namespace disagrees with its path is refused"
    (is (thrown-with-msg? Exception #"does not match its file"
          (gf/files->bundle
            {"fns/acme.demo.edn" "{:namespace \"acme.moved\" :fns []}"})))))


(deftest corpus-text-fixpoint
  ;; The whole first-party corpus survives graph → text files → graph:
  ;; parse(files->bundle(bundle->files(export))) == parse(export). This is
  ;; the property the git storage story rests on.
  (let [packages (loader/load-packages ["core" "web" "app" "registry" "mcp"])
        all-defs (vec (concat (map (fn [[nm d]] (assoc d :name nm)) (:base-fn-defs packages))
                              (:fn-defs packages)))
        sorted (deps/topological-sort all-defs)
        bundle (export/records->fn-defs (parse/parse-module sorted))
        files (gf/bundle->files bundle)
        back (gf/files->bundle files)]
    (testing "nothing gained or lost through the text layout"
      (is (= (count bundle) (count back)))
      (is (= (set bundle) (set back))
          "the text layout is lossless at the def level"))
    (testing "the corpus re-parses identically after the text round-trip"
      (is (= (set (parse/parse-module (vec bundle)))
             (set (parse/parse-module (vec back))))))
    (testing "byte stability on the full corpus"
      (is (= files (gf/bundle->files bundle))))))
