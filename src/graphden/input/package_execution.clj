(ns graphden.input.package-execution
  "Explicit offline execution boundary for prepared, verified package source.
   Does not register implementations or select a production compiler."
  (:require
    [graphden.input.analysis :as analysis]
    [graphden.input.compile :as compile]
    [graphden.input.ir :as ir]
    [graphden.input.source-snapshot :as snapshot]))


(defn compile-package
  "Freeze and check a package closure, then lower its named residual inputs.
   package comes from source-snapshot/package-index. roots are frozen rich
   entries by base UUID; implementations are explicit base UUID -> function.
   Both must originate in the same prepared package runtime. Returns evidence
   and an ordinary executor closure, without publishing either globally."
  [package root roots implementations]
  (let [{:keys [context options]} (snapshot/freeze-closure package [root] roots)
        implementations (update-vals
                          (:implementations options)
                          (fn [{:keys [base-fn-id] :as entry}]
                            (let [impl (get implementations base-fn-id)]
                              (when-not (fn? impl)
                                (ir/reject! :resolved-implementation-required {:base-fn-id base-fn-id}))
                              (assoc entry :impl impl))))
        checked (analysis/analyze-effective-input context root options)]
    {:context context :analysis checked
     :invoke (compile/compile-named checked {:context context :implementations implementations})}))
