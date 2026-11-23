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
  (let [thread (Thread/currentThread)
        original (.getContextClassLoader thread)
        urls (into-array URL (map #(-> % fs/file .toURI .toURL) resources))]
    (with-open [loader (proxy [URLClassLoader] [urls original]
                         (getResource
                           [name]
                           (if (str/starts-with? name "packages/core/")
                             (proxy-super findResource name)
                             (proxy-super getResource name))))]
      (try
        (.setContextClassLoader thread loader)
        (f)
        (finally (.setContextClassLoader thread original))))))


(def ^:private inputs
  {:add {:nums [10 3 2]} :sub {:nums [10 3 2]} :mul {:nums [10 3 2]} :div {:nums [12 3 2]}
   :min {:nums [10 -3 2]} :max {:nums [10 -3 2]}
   :floor {:number -2.25} :ceil {:number -2.25} :sqrt {:number 9} :pow {:base 2 :exponent 5}
   :mod {:dividend 13 :divisor 5} :quot {:dividend 13 :divisor 5} :neg {:number 3} :abs {:number -3}
   :round {:number 2.76 :decimals 1} :neq {:values ["one" 2]} :lt {:nums [1 2]} :lte {:nums [1 1]}
   :gt {:nums [3 2]} :gte {:nums [3 3]}})


(defn- execute-module
  [packages]
  (let [entries (into {} (filter (fn [[_ d]] (= "core.arithmetic" (:namespace d)))) (:base-fn-defs packages))]
    (expect! (set (keys inputs)) (set (keys entries)))
    (into (sorted-map)
          (map (fn [[n args]]
                 (let [d (get entries n)]
                   [n {:result ((:impl d) args {}) :args (:args d) :return (:return-type d)
                       :metadata (disj (set (keys d)) :local-type-rules)
                       :inferred (when-let [rule (:return-type-rule d)]
                                   (rule (update-vals args (fn [v] {:type (if (vector? v) [:list :int] :int)}))
                                         (:return-type d)))}])))
          inputs)))


(def ^:private admission
  {:module ["core" "arithmetic"] :in-place-abi :core.arithmetic :sha256 target-sha})


(defn- candidate!
  []
  (let [root (ids/fn-id "core.arithmetic" :floor)
        module (with-open [stream (io/input-stream (io/resource module-path))]
                 (staged/read-pinned (.readAllBytes stream) target-sha))
        entries (:fns module)
        owners (set (map #(ids/fn-id (:namespace module) (:name %)) entries))
        alias-admission {:module ["core" "arithmetic"] :fn-id root :abi-fn-id root :sha256 target-sha}
        ordinary (outcome #(loader/load-packages ["core"]))
        in-place (outcome #(loader/load-packages ["core"] {:primitive-input alias-admission}))
        ;; A different registered native is only a negative control. The
        ;; whole-module check runs before comparing these implementations.
        control (assoc alias-admission :abi-fn-id (ids/fn-id "core.arithmetic" :ceil))
        sibling (outcome #(loader/load-packages ["core"] {:primitive-input control}))]
    (expect! [20 20 root] [(count owners) (count (remove #(or (:parent %) (:parents %)) entries))
                           (:fn-id (primitive/prepare module root))])
    (expect! {:type :package-error/input-format :code :unsupported-input-format} ordinary)
    (expect! {:type :package-error/primitive-admission} in-place)
    (expect! {:type :input/staged-primitive :code :staged-literal-parent-unsupported} sibling)
    (let [packages (loader/load-packages ["core"] {:primitive-input admission})]
      {:packages packages :executions (execute-module packages)
       :module-sha256 target-sha :roots (count owners) :ordinary ordinary :original-abi in-place :distinct-abi-control sibling})))


(defn- reject-changed-impl!
  [tmp resources]
  (let [changed (fs/path tmp "changed")
        file (fs/path changed "packages/core/arithmetic/impls.clj")]
    (fs/create-dirs (fs/parent file))
    (spit (str file) "(throw (ex-info \"Unexpected implementation evaluation\" {}))")
    (expect! {:type :package-error/primitive-admission}
             (on-package-classpath (cons changed resources)
                                   #(outcome (fn [] (loader/load-packages ["core"] {:primitive-input admission})))))))


(defn check
  [{:keys [artifact checkout postgres]}]
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
                        (let [legacy (on-package-classpath [resources] #(execute-module (loader/load-packages ["core"])))
                              staged (on-package-classpath [(fs/path candidate "resources") resources] candidate!)
                              proof (when postgres
                                      (on-package-classpath [resources]
                                                            #((requiring-resolve 'graphden.test-infra.arithmetic-replay/check)
                                                              (:packages staged) inputs)))]
                          (reject-changed-impl! tmp [(fs/path candidate "resources") resources])
                          (expect! legacy (:executions staged))
                          (expect! [-3 15] (mapv #(get-in legacy [% :result]) [:floor :add]))
                          (prn {:source-head source-head :staged (cond-> (dissoc staged :executions :packages) postgres (assoc :postgres proof))
                                :results (update-vals legacy :result)}))))))
