(ns graphden.input.package-execution
  "Explicit offline execution boundary for prepared, verified package source.
   Does not register implementations or select a production compiler."
  (:require
    [graphden.input.analysis :as analysis]
    [graphden.input.compile :as compile]
    [graphden.input.ir :as ir]
    [graphden.input.list-execution :as list-execution]
    [graphden.input.source-snapshot :as snapshot]
    [graphden.input.staged-module :as staged]))


(defn- compile-frozen
  [{:keys [context options]} root implementations]
  (let [implementations (update-vals
                          (:implementations options)
                          (fn [{:keys [base-fn-id generated-list] :as entry}]
                            (let [impl (if generated-list
                                         (list-execution/compile-arguments (:operands generated-list))
                                         (get implementations base-fn-id))]
                              (when-not (fn? impl)
                                (ir/reject! :resolved-implementation-required {:base-fn-id base-fn-id}))
                              (assoc entry :impl impl))))
        checked (analysis/analyze-effective-input context root options)]
    {:context context :analysis checked
     :invoke (compile/compile-named checked {:context context :implementations implementations})}))


(defn compile-package
  "Freeze and check a package closure, then lower its named residual inputs.
   package comes from source-snapshot/package-index. roots are frozen rich
   entries by base UUID; implementations are explicit base UUID -> function.
   Both must originate in the same prepared package runtime. Returns evidence
   and an ordinary executor closure, without publishing either globally."
  [package root roots implementations]
  (compile-frozen (snapshot/freeze-closure package [root] roots) root implementations))


(defn- require-visible-root!
  [root visible-fn-ids]
  (when-not (and (set? visible-fn-ids) (contains? visible-fn-ids root))
    (ir/reject! :staged-function-not-visible {})))


(defn compile-staged-module
  "Offline replay of one staged module against original package preparation.
   Supply the trusted legacy module and package index, the staged bytes and
   their expected receipt SHA-256, and the caller's visible executable UUIDs.
   Only a root from the staged module can be compiled here. Dependencies
   outside the module still come from the caller's visible prepared package.
   No registration, package loading, or storage changes occur."
  [package root roots implementations {payload :bytes :keys [legacy-module sha256 visible-fn-ids]}]
  (require-visible-root! root visible-fn-ids)
  (let [module (staged/read-pinned payload sha256)
        package (snapshot/with-staged-module package legacy-module module)]
    (when-not (contains? (:staged-proposals package) root)
      (ir/reject! :staged-root-required {}))
    (let [frozen (snapshot/freeze-closure package [root] roots)
          executable (get-in frozen [:options :implementations])]
      (when-not (every? (fn [[target-id {:keys [base-fn-id generated-list]}]]
                          ;; The verified authored owner grants access to its
                          ;; generated list, never to its separate operand refs.
                          (if generated-list
                            (and (= target-id base-fn-id) (contains? visible-fn-ids (:owner-id generated-list)))
                            (and (contains? visible-fn-ids target-id)
                                 (contains? visible-fn-ids base-fn-id))))
                        executable)
        (ir/reject! :staged-function-not-visible {}))
      (assoc (compile-frozen frozen root implementations)
             :staged-source {:sha256 sha256 :namespace (:namespace module)}))))


(defn compile-staged-primitive
  "Read a staged file and compile one visible, independent primitive root.
   Supply the expected receipt checksum and the package's frozen signatures
   and implementations. Declaration and field identities come from the v2
   source alone. Roots requiring bindings or dependency preparation are blocked."
  [root roots implementations {payload :bytes :keys [sha256 visible-fn-ids]}]
  (require-visible-root! root visible-fn-ids)
  (let [module (staged/read-pinned payload sha256)
        frozen (snapshot/freeze-primitive module root roots)]
    (assoc (compile-frozen frozen root implementations)
           :staged-source {:sha256 sha256 :namespace (:namespace module)})))


(defn compile-staged-literal-root
  "Compile one visible v2 root with scalar literal bindings and a visible
   primitive parent declared in the same pinned file."
  [root roots implementations {payload :bytes :keys [sha256 visible-fn-ids]}]
  (require-visible-root! root visible-fn-ids)
  (let [module (staged/read-pinned payload sha256)
        frozen (snapshot/freeze-literal-root module root roots visible-fn-ids)]
    (assoc (compile-frozen frozen root implementations)
           :staged-source {:sha256 sha256 :namespace (:namespace module)})))
