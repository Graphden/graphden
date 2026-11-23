(ns structured-input-loader-replay
  "Replay the unchanged arithmetic package source and one complete staged
   module through the ordinary loader. Run in its own JVM with
   clojure -X:input-source-migration structured-input-loader-replay/check
   :artifact '\"/path/to/migration.edn\"' :checkout '\"/path/to/core\"'."
  (:require
    [babashka.fs :as fs]
    [babashka.process :as process]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [graphden.input.source-primitive :as primitive]
    [graphden.input.staged-module :as staged]
    [graphden.packages.loader :as loader]
    [graphden.packages.records.ids :as ids]
    [structured-input-inventory :as inventory]
    [structured-input-stage :as stage])
  (:import
    (clojure.lang
      Compiler
      RT)
    (java.net
      URL
      URLClassLoader)))


(def ^:private source-head "5100ed970c070162175f651d375a7a7557d40851")
(def ^:private module-path "packages/core/arithmetic/fns.edn")
(def ^:private target-sha "8f2959ace71a4b308e3f4201d158eb1e735330711eeedca0bcd8528a17d8ebd4")


(defn- expect!
  [expected actual]
  (when-not (= expected actual)
    (throw (ex-info "Package loader replay changed" {:expected expected :actual actual}))))


(defn- outcome
  [load!]
  (try (load!) :loaded
       (catch clojure.lang.ExceptionInfo error
         (select-keys (ex-data error) [:type :code]))))


(defn- on-package-classpath
  [resources f]
  ;; The package itself is an immutable git archive. Resolve its resources
  ;; exclusively from those directories, including optional impls files.
  (let [urls (into-array URL (map #(-> % fs/file .toURI .toURL) resources))]
    (with-open [loader (proxy [URLClassLoader] [urls (RT/baseLoader)]
                         (getResource
                           [name]
                           (if (str/starts-with? name "packages/core/")
                             (proxy-super findResource name)
                             (proxy-super getResource name))))]
      (with-bindings {Compiler/LOADER loader} (f)))))


(defn- original!
  []
  (let [definitions (:base-fn-defs (loader/load-packages ["core"]))
        floor (:floor definitions)
        value ((:impl floor) {:number -2.25} {})
        witnesses (into [] (keep (fn [[name {impl :impl}]]
                                   (when (identical? impl (:impl floor)) name))) definitions)]
    (expect! ["core.arithmetic" :int -3 [:floor]] [(:namespace floor) (:return-type floor) value witnesses])
    {:floor value :registrations witnesses}))


(defn- candidate!
  []
  (let [root (ids/fn-id "core.arithmetic" :floor)
        module (with-open [stream (io/input-stream (io/resource module-path))]
                 (staged/read-pinned (.readAllBytes stream) target-sha))
        entries (:fns module)
        owners (set (map #(ids/fn-id (:namespace module) (:name %)) entries))
        admission {:module ["core" "arithmetic"] :fn-id root :abi-fn-id root :sha256 target-sha}
        ordinary (outcome #(loader/load-packages ["core"]))
        in-place (outcome #(loader/load-packages ["core"] {:primitive-input admission}))
        sibling (outcome #(primitive/prepare-literal-root module (ids/fn-id "core.arithmetic" :add) owners))]
    (expect! [20 20 root] [(count owners) (count (remove #(or (:parent %) (:parents %)) entries))
                           (:fn-id (primitive/prepare module root))])
    (expect! {:type :package-error/input-format :code :unsupported-input-format} ordinary)
    (expect! {:type :package-error/primitive-admission} in-place)
    (expect! {:type :input/staged-primitive :code :staged-literal-parent-unsupported} sibling)
    {:module-sha256 target-sha :roots (count owners) :ordinary ordinary :original-abi in-place :sibling sibling}))


(defn check
  [{:keys [artifact checkout]}]
  (let [ledger (inventory/read-edn artifact (slurp artifact :encoding "UTF-8"))
        source-identity (some #(when (= "core" (:label %)) (:source-identity %)) (get-in ledger [:report :source-identities]))
        path (str "resources/" module-path)
        module (filterv #(and (= "core" (:repository %)) (= path (:path %))) (:modules ledger))]
    (expect! [source-head 1 :verified target-sha]
             [(:git-head source-identity) (count module) (:status (first module)) (get-in module [0 :patch :target-sha256])])
    (fs/with-temp-dir [tmp {:prefix "graphden-loader-replay-"}]
                      (let [archive (fs/path tmp "core.zip")
                            original (fs/path tmp "original")
                            candidate (fs/path tmp "candidate")
                            resources (fs/path original "resources")]
                        (process/shell "git" "-C" checkout "archive" "--format=zip" (str "--output=" archive)
                                       source-head "resources/packages/core")
                        (fs/create-dirs original)
                        (fs/unzip archive original)
                        (stage/stage! (assoc ledger :modules module) "core" original candidate)
                        (let [legacy (on-package-classpath [resources] original!)
                              staged (on-package-classpath [(fs/path candidate "resources") resources] candidate!)]
                          (prn {:source-head source-head :legacy legacy :staged staged}))))))
