(ns ci-priority-core-test
  (:require
    [babashka.fs :as fs]
    [babashka.process :as p]
    [ci-priority-core :as priority]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [clojure.test :refer [deftest is run-tests]]))


(deftest exact-frontend-hints-never-filter-browser-suite
  (let [changed #{"resources/packages/app/editor/editor-undo.js"}
        all (priority/browser-order nil)
        ordered (priority/browser-order changed)]
    (is (= "edit-undo.test.js" (first ordered)))
    (is (= (frequencies all) (frequencies ordered)))
    (is (some #{"tools/runtime-test/undo-journal.test.js"}
              (priority/runtime-first changed)))))


(deftest broad-dependencies-retain-every-affected-candidate
  (let [changed #{"src/graphden/versioning/storage/resolution.clj"
                  "resources/packages/app/editor-branches/fns.edn"
                  "resources/packages/app/editor/editor-branch-context.js"}
        manual {:browser ["edit-fn-versions-restore.test.js"]
                :integration ["graphden.crud.branches-graph-test"]}
        plan (priority/plan changed manual)]
    (is (> (count (priority/integration-first changed)) 20))
    (is (= (set (priority/integration-first changed))
           (set (:integration plan)))
        "all affected namespaces precede the unrelated remainder")
    (is (= "graphden.crud.branches-graph-test" (first (:integration plan))))
    (is (= (set (concat (:browser manual) (priority/browser-first changed)))
           (set (:browser plan)))
        "manual choices and every automatic candidate run early")
    (is (= "edit-fn-versions-restore.test.js" (first (:browser plan))))
    (is (= "edit-account-menu-graph.test.js" (second (:browser plan)))
        "direct editor consumers precede broad package and API matches")
    (is (<= (count (:browser plan)) (count (priority/browser-order changed))))
    (is (= (count (:integration plan))
           (count (distinct (:integration plan)))))))


(defn- fixture-test-names
  [prefix n]
  (mapv #(format "demo.%s-%02d-test" prefix %) (range n)))


(defn- fixture-test-path
  [name]
  (str "test/" (str/replace name #"[.-]" {"." "/" "-" "_"}) ".clj"))


(defn- fixture-priority-plans
  "Exercise the public planner against real namespace forms in an isolated repo."
  [requests & [extra-files]]
  (let [dir (fs/create-temp-dir {:prefix "priority-distance-"})
        scripts (str (fs/absolutize "scripts"))
        files (into {"src/demo/source.clj" "(ns demo.source)"
                     "src/demo/no_direct.clj" "(ns demo.no-direct)"
                     "src/demo/mid.clj" "(ns demo.mid (:require [demo.source] [demo.no-direct]))"
                     "src/demo/deep.clj" "(ns demo.deep (:require [demo.mid]))"}
                    (for [[prefix n dependency] [["changed" 25 nil]
                                                 ["direct" 25 "demo.source"]
                                                 ["indirect" 10 "demo.mid"]
                                                 ["deep" 15 "demo.deep"]
                                                 ["unrelated" 1 nil]]
                          name (fixture-test-names prefix n)]
                      [(fixture-test-path name)
                       (str "(ns ^{:integration true"
                            (when (= prefix "changed")
                              " :ci/package-source-modules #{[\"demo\" \"module\"]}")
                            "} " name
                            (when dependency (str " (:require [" dependency "])"))
                            ") (deftest example-test (is true))")]))]
    (try
      (doseq [[path body] (merge files extra-files)]
        (let [file (fs/path dir path)]
          (fs/create-dirs (fs/parent file))
          (spit (str file) body)))
      (let [result (p/shell {:dir (str dir) :out :string :err :string :continue true}
                            "bb" "-cp" scripts "-e"
                            (str "(require '[ci-priority-core :as p]) "
                                 "(prn (mapv (fn [[changed manual]] "
                                 "{:plan (p/plan changed (p/manual-hints manual)) "
                                 ":all (p/integration-first changed)}) "
                                 (pr-str requests) "))"))]
        (when-not (zero? (:exit result))
          (throw (ex-info "Priority fixture process failed" result)))
        (edn/read-string (:out result)))
      (finally (fs/delete-tree dir)))))


(deftest early-wave-preserves-changed-and-direct-tests-beyond-twenty
  (let [changed (fixture-test-names "changed" 25)
        direct (fixture-test-names "direct" 25)
        indirect (fixture-test-names "indirect" 10)
        deep (fixture-test-names "deep" 15)
        paths (set (map fixture-test-path changed))
        [{mixed :plan all :all} {source-only :plan} {test-only :plan}]
        (fixture-priority-plans
          [[(conj paths "src/demo/source.clj")
            "integration:demo.direct-24-test,integration:demo.deep-14-test,integration:demo.direct-24-test"]
           [#{"src/demo/source.clj"} nil]
           [paths nil]])]
    (is (= [["demo.direct-24-test"] ["demo.deep-14-test"] changed
            (vec (butlast direct)) indirect (vec (butlast deep))]
           (:integration-layers mixed))
        "manual order and dedup survive across every dependency layer")
    (is (= (vec (concat changed direct indirect deep)) all)
        "the complete dependency roster stays unbounded")
    (is (= (mapcat identity (:integration-layers mixed)) (:integration mixed)))
    (is (= (count (:integration mixed)) (count (distinct (:integration mixed)))))
    (is (= [direct indirect deep] (:integration-layers source-only))
        "source-only changes start at actual distance one, with no changed-test layer")
    (is (= [changed] (:integration-layers test-only))
        "all directly changed tests remain early even without source changes")))


(deftest missing-nearby-test-layers-retain-every-deeper-consumer
  (let [[{:keys [plan all]}]
        (fixture-priority-plans [[#{"src/demo/no_direct.clj"} nil]])
        indirect (fixture-test-names "indirect" 10)
        deep (fixture-test-names "deep" 15)]
    (is (= [indirect deep] (:integration-layers plan))
        "distance still orders the wave without truncating its deeper layer")
    (is (= (vec (concat indirect deep)) all))))


(deftest explicit-package-consumers-are-not-truncated
  (doseq [{:keys [plan]}
          (fixture-priority-plans
            [[#{"resources/packages/demo/module/fns.edn"} nil]
             [#{"resources/packages/demo/module/impls.clj"} nil]])]
    (is (= (fixture-test-names "changed" 25) (:integration plan)))))


(defn- fixture-browser-plans
  [requests]
  (let [dir (fs/create-temp-dir {:prefix "priority-browser-wave-"})
        scripts (str (fs/absolutize "scripts"))
        files (into {"tools/browser-test/narrow.js" "module.exports = {};"
                     "tools/browser-test/broad.js" "module.exports = {};"
                     "tools/browser-test/edit-narrow.test.js" "require('./narrow');"
                     "tools/browser-test/edit-unrelated.test.js" "void 0;"}
                    (for [n (range 30)]
                      [(format "tools/browser-test/edit-broad-%02d.test.js" n)
                       "require('./broad');"]))]
    (try
      (doseq [[path body] files]
        (let [file (fs/path dir path)]
          (fs/create-dirs (fs/parent file))
          (spit (str file) body)))
      (let [result (p/shell {:dir (str dir) :out :string :err :string :continue true}
                            "bb" "-cp" scripts "-e"
                            (str "(require '[ci-priority-core :as p]) "
                                 "(prn (mapv (fn [[changed encoded]] "
                                 "(let [manual (p/manual-hints encoded)] "
                                 "{:early (:browser (p/plan changed manual)) "
                                 ":all (p/browser-order changed (:browser manual))})) "
                                 (pr-str requests) "))"))]
        (when-not (zero? (:exit result))
          (throw (ex-info "Browser priority fixture process failed" result)))
        (edn/read-string (:out result)))
      (finally (fs/delete-tree dir)))))


(deftest browser-wave-size-follows-affected-files-without-a-fixed-limit
  (let [narrow #{"tools/browser-test/narrow.js"}
        broad (conj narrow "tools/browser-test/broad.js")
        tests (mapv #(format "edit-broad-%02d.test.js" %) (range 30))
        changed-tests (set (map #(str "tools/browser-test/" %) tests))
        [small large unrelated direct manual :as results]
        (fixture-browser-plans
          [[narrow nil] [broad nil]
           [(into narrow (map #(str "docs/unrelated-" % ".md") (range 100))) nil]
           [changed-tests nil]
           [broad (str "browser:edit-broad-29.test.js,browser:edit-unrelated.test.js,"
                       "browser:edit-broad-29.test.js")]])]
    (is (= ["edit-narrow.test.js"] (:early small)))
    (is (= (into #{"edit-narrow.test.js"} tests) (set (:early large))))
    (is (= (:early small) (:early unrelated))
        "unrelated paths do not inflate the affected wave")
    (is (= tests (:early direct)) "every directly changed browser file runs early")
    (is (= ["edit-broad-29.test.js" "edit-unrelated.test.js"]
           (subvec (:early manual) 0 2)))
    (doseq [{:keys [early all]} results]
      (let [tail (remove (set early) all)]
        (is (= (frequencies (:all small)) (frequencies all)))
        (is (= early (vec (take (count early) all))))
        (is (= (count early) (count (distinct early))))
        (is (= all (vec (concat early tail)))
            "the early wave and its exact complement execute every file once")))))


(deftest broad-tutorial-changes-keep-execution-and-ui-contracts-early
  (let [changed (into #{"tools/browser-test/tutorial-tour-helpers.js"
                        "resources/packages/app/ui-tutorial-catalog/fns.edn"}
                      (map str (fs/glob "tools/browser-test" "edit-tutorial-tour*.test.js")))
        early (:browser (priority/plan changed))
        all (priority/browser-order changed)]
    (is (> (count early) 20))
    (doseq [file ["edit-execution-contracts.test.js" "edit-ui-components.test.js"]]
      (is (some #{file} early) file))
    (is (= all (vec (concat early (remove (set early) all)))))
    (is (= (frequencies (priority/browser-order nil)) (frequencies all)))))


(deftest changed-browser-test-runs-first-but-unknown-editor-keeps-all
  (let [changed #{"tools/browser-test/edit-a11y-audit.test.js"}
        unknown #{"resources/packages/app/editor/components.css"}]
    (is (= "edit-a11y-audit.test.js" (first (priority/browser-order changed))))
    (is (= (priority/browser-order nil) (priority/browser-order unknown)))
    (is (= (frequencies (priority/browser-order nil))
           (frequencies (priority/browser-order changed))))))


(deftest dynamic-source-readers-enter-early-unit-layers
  (is (some #{"graphden.test-hygiene-guard-test"}
            (second (priority/unit-layers
                      #{"test/graphden/types/branch_market_policy_contract_test.clj"}))))
  (let [first-layer (first (priority/unit-layers
                             #{"src/graphden/crud/fn_execution.clj"}))]
    (doseq [guard ["graphden.open-core-seam-test"
                   "graphden.id-resolution-guard-test"
                   "graphden.large-forms-guard-test"
                   "graphden.packages.graph-entry-points-test"]]
      (is (some #{guard} first-layer) guard))))


(deftest matching-editor-stem-prioritizes-related-browser-files
  (let [ordered (priority/browser-order
                  #{"resources/packages/app/editor/editor-secrets.js"})]
    (is (= ["edit-secrets-list.test.js" "edit-secrets-panel.test.js"
            "edit-secrets-rotate.test.js"]
           (subvec ordered 0 3)))
    (is (= (frequencies (priority/browser-order nil)) (frequencies ordered)))))


(deftest root-catalog-impact-includes-consumers-with-different-file-names
  (doseq [source ["resources/packages/app/editor/editor-root.js"
                  "resources/packages/app/editor/editor-tour-picker.js"
                  "resources/packages/app/ui-tutorial-catalog/fns.edn"
                  "tools/browser-test/tutorial-tour-helpers.js"]]
    (let [first-files (set (priority/browser-first #{source}))]
      (is (contains? first-files "edit-tutorial-tour-picker.test.js") source)
      (is (contains? first-files "edit-tutorial-tour-queues.test.js") source)
      (is (= (frequencies (priority/browser-order nil))
             (frequencies (priority/browser-order #{source}))) source)))
  (is (contains? (set (priority/browser-first
                        #{"resources/packages/app/editor/editor-root.js"}))
                 "edit-account-menu-graph.test.js")))


(deftest exact-catalog-role-consumers-precede-broad-editor-matches
  (let [changed #{"resources/packages/app/editor/editor-tour.js"
                  "resources/packages/app/ui-tutorial-catalog/fns.edn"}
        ordered (priority/browser-order changed)]
    (is (= ["edit-ui-components-selective.test.js" "edit-ui-components.test.js"]
           (subvec ordered 0 2)))
    (is (= (frequencies (priority/browser-order nil)) (frequencies ordered)))))


(deftest package-module-without-ui-prefix-keeps-browser-order-valid
  (let [source "resources/packages/app/editor/fns.edn"
        ordered (priority/browser-order #{source})]
    (is (= (frequencies (priority/browser-order nil)) (frequencies ordered)))))


(deftest shipped-component-contracts-link-graphs-to-explicit-js-consumers
  (let [keymap #{"resources/packages/app/ui-keymap/fns.edn"}
        appearance #{"resources/packages/app/ui-appearance/fns.edn"}
        keymap-first (priority/browser-first keymap)]
    (is (= "edit-keymap-graph.test.js" (first keymap-first)))
    (is (= "edit-appearance-graph.test.js"
           (first (priority/browser-first appearance))))
    (is (not-any? #{"edit-a11y-audit.test.js"} keymap-first)
        "a plain mention of keymap does not make an unrelated test early")
    (is (= (frequencies (priority/browser-order nil))
           (frequencies (priority/browser-order keymap))))))


(deftest package-http-routes-prioritize-browser-consumers
  (doseq [source ["resources/packages/registry/registry/fns.edn"
                  "resources/packages/registry/registry/impls.clj"
                  "src/graphden/packages/pin_usage.clj"]]
    (let [early (set (priority/browser-first #{source}))]
      (is (contains? early "edit-tutorial-package-lifecycle.test.js") source)
      (is (contains? early "edit-packages-panel.test.js") source)
      (is (= (frequencies (priority/browser-order nil))
             (frequencies (priority/browser-order #{source}))) source))))


(deftest transitive-backend-api-dependencies-prioritize-browser-contracts
  ;; Registry impls require pin-usage, which requires graph-refs. No browser
  ;; file imports this backend helper or shares its filename.
  (let [changed #{"src/graphden/crud/graph_refs.clj"}
        early (priority/browser-first changed)
        plan (priority/plan changed)
        ordered (priority/browser-order changed)]
    (is (some #{"edit-tutorial-package-lifecycle.test.js"} early))
    (is (some #{"edit-packages-panel.test.js"} (:browser plan)))
    (is (not-any? #{"edit-secrets-rotate.test.js" "edit-undo.test.js"} early)
        "unrelated API and editor contracts do not become early")
    (is (< (count early) 20) "the concrete helper has a narrow API fanout")
    (is (= (frequencies (priority/browser-order nil)) (frequencies ordered))))
  (let [changed #{"src/graphden/executor/defbase.clj"}]
    (is (= (priority/browser-first changed) (:browser (priority/plan changed)))
        "a common backend dependency retains its complete affected wave"))
  (is (= [] (priority/browser-first #{"src/graphden/no_such_file.clj"}))))


(deftest short-api-roots-select-exact-ui-contracts
  (let [changed #{"resources/packages/app/ui-components/impls.clj"}
        early (:browser (priority/plan changed))]
    (doseq [file ["edit-personal-configuration.test.js"
                  "edit-ui-components-selective.test.js" "edit-server-call.test.js"]]
      (is (some #{file} early) file))
    (is (not-any? #{"edit-ui-graph-theme.test.js" "edit-undo.test.js"} early))
    (is (= (priority/browser-first changed) early))
    (is (= (frequencies (priority/browser-order nil))
           (frequencies (priority/browser-order changed))))))


(deftest dynamic-api-routes-preserve-segments-and-literal-suffixes
  (let [paths #{"/api/branches/:ref/diff" "/api/ui/configuration/apply"}
        matches? (fn [path]
                   (boolean (#'priority/literal-api-consumer?
                             paths (str "fetch('" path "');"))))]
    (doseq [path ["/api/branches/x/diff" "/api/branches/x/diff?full=1"
                  "/api/branches/x/diff#entry" "/api/ui/configuration/apply"]]
      (is (matches? path) path))
    (doseq [path ["/api/branches/x/propose" "/api/branches/x/y/diff"
                  "/api/branches//diff" "/api/branches/x/diff-extra"
                  "/api/ui/configuration/apply-extra"]]
      (is (not (matches? path)) path))))


(deftest exact-route-consumers-follow-local-helper-imports
  (let [dir (fs/create-temp-dir {:prefix "priority-api-"})
        scripts (str (fs/absolutize "scripts"))
        files {"resources/packages/demo/endpoint/fns.edn"
               "{:namespace \"demo.endpoint\" :fns [{:name :route :args {:path \"/ui/configuration/apply\" :handler :handler}}]}"
               "tools/browser-test/helper.js" "fetch('/api/ui/configuration/apply');"
               "tools/browser-test/indirect.js" "require('./helper');"
               "tools/browser-test/edit-helper.test.js" "require('./indirect');"
               "tools/browser-test/edit-unrelated.test.js" "fetch('/api/ui/theme/evaluate');"
               "tools/browser-test/edit-boundary.test.js" "fetch('/api/ui/configuration/apply-extra');"}]
    (try
      (doseq [[path body] files]
        (let [file (fs/path dir path)]
          (fs/create-dirs (fs/parent file))
          (spit (str file) body)))
      (let [result (p/shell {:dir (str dir) :out :string :err :string :continue true}
                            "bb" "-cp" scripts "-e"
                            "(require '[ci-priority-core :as p]) (prn (p/browser-first #{\"resources/packages/demo/endpoint/impls.clj\"}))")]
        (is (zero? (:exit result)) (:err result))
        (is (= ["edit-helper.test.js"] (edn/read-string (:out result)))))
      (finally (fs/delete-tree dir)))))


(deftest shared-popover-impact-includes-the-personal-theme-walk
  (doseq [source ["resources/packages/app/editor/editor-edit-modes.js"
                  "resources/packages/web/runtime/graphden-popover.js"]]
    (let [first-files (set (priority/browser-first #{source}))]
      (is (contains? first-files "edit-ui-components.test.js") source)
      (is (contains? first-files "edit-arg-value.test.js") source)
      (is (= (frequencies (priority/browser-order nil))
             (frequencies (priority/browser-order #{source}))) source))))


(deftest package-handler-route-impact-reaches-value-form-browser-walk
  (let [changed #{"resources/packages/app/forms/fns.edn"}
        early (:browser (priority/plan changed))]
    (is (some #{"edit-ui-components.test.js"} early)
        "the form package owns the route handler even though the route is declared elsewhere")
    (is (some #{"edit-color-values.test.js"} early))
    (is (= (frequencies (priority/browser-order nil))
           (frequencies (priority/browser-order changed))))))


(deftest narrow-changed-browser-helper-precedes-broad-helper
  (let [changed #{"tools/browser-test/tutorial-tour-helpers.js"
                  "tools/browser-test/tutorial-ui-components-helpers.js"
                  "resources/packages/app/forms/fns.edn"}
        positions (zipmap (priority/browser-first changed) (range))
        early (:browser (priority/plan changed))]
    (is (some #{"edit-ui-components.test.js"} early))
    (is (< (positions "edit-ui-components.test.js")
           (positions "edit-tutorial-tour.test.js")))))


(deftest row-actions-owner-contract-prioritizes-its-browser-consumers
  (let [source "resources/packages/app/editor/editor-row-actions.js"
        early (priority/browser-first #{source})
        positions (zipmap early (range))]
    (is (contains? positions "edit-canvas-card-personal.test.js"))
    (is (contains? positions "edit-row-actions-pin.test.js"))
    (is (< (positions "edit-canvas-card-personal.test.js")
           (positions "edit-tutorial-tour-picker.test.js")))
    (is (= (frequencies (priority/browser-order nil))
           (frequencies (priority/browser-order #{source}))))))


(deftest local-browser-helper-changes-reach-transitive-test-consumers
  (doseq [source ["tools/browser-test/tutorial-actions.js"
                  "tools/browser-test/checked-action.js"]]
    (let [early (priority/browser-first #{source})]
      (is (some #{"edit-tutorial-tour.test.js"} early) source)
      (is (some #{"edit-tutorial-tour-args.test.js"} early) source)
      (is (= (frequencies (priority/browser-order nil))
             (frequencies (priority/browser-order #{source}))) source)))
  (let [early (priority/browser-first
                #{"tools/browser-test/tutorial-tour-helpers.js"})]
    (is (some #{"edit-tutorial-tour.test.js"} early))))


(deftest browser-links-follow-unique-globals-and-specific-selectors
  (let [source "resources/packages/app/editor/editor-feature.js"
        consumer "resources/packages/app/editor/editor-consumer.js"
        test-path "tools/browser-test/edit-feature.test.js"
        indirect-test "tools/browser-test/edit-consumer.test.js"
        sources {source "function featureGlobal() {}\nconst selector = 'gd-specific-widget';"
                 consumer "function consumeFeature() { featureGlobal(); }"
                 test-path "document.querySelector('.gd-specific-widget');"
                 indirect-test "consumeFeature();"}
        graph (#'priority/browser-source-graph sources [source consumer] [test-path indirect-test])
        distances (#'priority/dependency-distances (:reverse graph) #{source})]
    (is (= 1 (get distances test-path)) "the selector links the source directly to its test")
    (is (= 1 (get distances consumer)) "a unique global links dependent modules")
    (is (= 2 (get distances indirect-test)) "a test of the dependent module is reachable"))
  (let [source-a "resources/packages/app/editor/editor-a.js"
        source-b "resources/packages/app/editor/editor-b.js"
        test-path "tools/browser-test/edit-ambiguous.test.js"
        sources {source-a "function sharedGlobal() {}"
                 source-b "function sharedGlobal() {}"
                 test-path "sharedGlobal();"}
        graph (#'priority/browser-source-graph sources [source-a source-b] [test-path])]
    (is (nil? (get (#'priority/dependency-distances (:reverse graph) #{source-a}) test-path))
        "an ambiguous owner must not invent a dependency")))


(deftest backend-priority-covers-direct-and-transitive-consumers
  (let [layers (priority/unit-layers #{"src/graphden/types/core.clj"})
        direct (first layers)
        indirect (second layers)
        names (vec (mapcat identity layers))]
    (is (some #{"graphden.types.core-test"} direct))
    (is (some #{"graphden.crud.validation-test"} indirect))
    (is (> (count layers) 2))
    (is (= (count names) (count (distinct names))))
    (is (= names (priority/unit-first #{"src/graphden/types/core.clj"}))))
  (is (some #{"graphden.layout.data-test"}
            (priority/unit-first #{"src/graphden/layout/data.clj"})))
  (let [layers (priority/unit-layers
                 #{"src/graphden/types/core.clj"
                   "test/graphden/types/core_test.clj"})]
    (is (some #{"graphden.types.core-test"} (first layers)))
    (is (some #{"graphden.open-core-seam-test"} (second layers)))
    (is (some #{"graphden.types.check-test"} (second layers))))
  (doseq [path ["test/graphden/e2e_test.clj"
                "test/graphden/perf/scenarios_test.clj"
                "test/graphden/accounts/routes_test.clj"
                "test/graphden/storage/postgres/constraints_test.clj"]]
    (is (= ["graphden.test-hygiene-guard-test"]
           (priority/unit-first #{path})) path))
  (is (= [] (priority/unit-first #{"src/graphden/no_such_file.clj"})))
  (is (= 5 (count (priority/unit-first
                    #{"test/graphden/types/core_test.clj"
                      "test/graphden/types/check_test.clj"
                      "test/graphden/types/rules_test.clj"
                      "test/graphden/types/folds_test.clj"})))))


(deftest backend-integration-focus-selects-real-pg-tests
  (is (= ["graphden.accounts.routes-test"]
         (priority/integration-first #{"test/graphden/accounts/routes_test.clj"})))
  (is (= ["graphden.storage.postgres.constraints-test"]
         (priority/integration-first
           #{"test/graphden/storage/postgres/constraints_test.clj"})))
  (is (< 1 (count (priority/integration-first #{"src/graphden/types/core.clj"}))))
  (is (= [] (priority/integration-first #{"test/graphden/types/core_test.clj"}))))


(deftest literal-dynamic-loads-and-package-fixtures-enter-the-early-graph
  ;; compile-eager intentionally breaks a load cycle via requiring-resolve.
  (is (contains? (:deps (#'priority/namespace-entry
                         "src/graphden/executor/compile_eager.clj"))
                 'graphden.executor.compile-runtime))
  (is (contains? (:packages (#'priority/namespace-entry
                             "test/graphden/packages/core/hof_test.clj"))
                 ["core" "hof"]))
  (is (contains? (:fn-packages (#'priority/namespace-entry
                                "test/graphden/packages/core/hof_test.clj"))
                 ["core" "hof"]))
  (is (= ["graphden.large-forms-guard-test"
          "graphden.packages.core.hof-test"
          "graphden.types.branch-market-policy-contract-test"
          "graphden.types.crud-apply-policy-contract-test"
          "graphden.types.explorer-view-contract-test"
          "graphden.types.registry-receipt-policy-contract-test"
          "graphden.types.small-policy-contract-test"
          "graphden.types.taint-propagate-guard-test"]
         (priority/unit-first
           #{"resources/packages/core/hof/impls.clj"})))
  (let [layers (priority/unit-layers
                 #{"resources/packages/core/hof/impls.clj"})]
    (is (some #{"graphden.types.taint-propagate-guard-test"} (first layers)))
    (is (= (count (mapcat identity layers))
           (count (distinct (mapcat identity layers))))))
  (is (not-any? #{"graphden.types.taint-propagate-guard-test"}
                (priority/unit-first
                  #{"resources/packages/core/hof/fns.edn"})))
  (is (some #{"graphden.packages.core.hof-test"}
            (priority/unit-first
              #{"resources/packages/core/hof/fns.edn"}))
      "a package graph edit must prioritize tests using its impls fixture"))


(deftest declared-package-source-consumer-runs-before-late-integration
  (let [entry (#'priority/namespace-entry
               "test/graphden/executor/compile_packages_test.clj")]
    (is (contains? (:packages entry) ["web" "http"]))
    (is (contains? (:fn-packages entry) ["web" "http"])))
  (doseq [source ["resources/packages/web/http/fns.edn"
                  "resources/packages/web/http/impls.clj"]]
    (is (some #{"graphden.executor.compile-packages-test"}
              (priority/integration-first #{source}))
        source)))


(deftest runtime-order-preserves-full-roster-once
  (let [all (priority/runtime-order nil)
        changed #{"tools/runtime-test/undo-journal.test.js"}
        ordered (priority/runtime-order changed)]
    (is (= "tools/runtime-test/undo-journal.test.js" (first ordered)))
    (is (= (frequencies all) (frequencies ordered)))
    (is (every? #(= 1 %) (vals (frequencies ordered))))
    (is (= all (priority/runtime-order #{"unknown"})))))


(deftest manual-priority-precedes-derived-tests-without-a-second-run
  (let [manual (priority/manual-hints
                 "runtime:org-switcher.test.js,browser:edit-appearance-graph.test.js,unit:graphden.executor.builtin-plans-test,unit:graphden.editor.component-config-test,runtime:org-switcher.test.js")
        runtime (priority/runtime-order #{"tools/runtime-test/undo-journal.test.js"}
                                        (:runtime manual))
        browser (priority/browser-order #{"tools/browser-test/edit-a11y-audit.test.js"}
                                        (:browser manual))
        plan (priority/plan #{"test/graphden/types/core_test.clj"} manual)]
    (is (= ["org-switcher.test.js"] (:runtime manual)))
    (is (= ["tools/runtime-test/org-switcher.test.js"
            "tools/runtime-test/undo-journal.test.js"] (subvec runtime 0 2)))
    (is (= ["edit-appearance-graph.test.js" "edit-a11y-audit.test.js"]
           (subvec browser 0 2)))
    (is (= "graphden.executor.builtin-plans-test" (first (:unit plan))))
    (is (= ["graphden.executor.builtin-plans-test"
            "graphden.editor.component-config-test"]
           (first (:unit-layers plan))))
    (is (= (:unit manual) (:unit-manual plan)))
    (is (= (frequencies (priority/runtime-order nil)) (frequencies runtime)))
    (is (= (frequencies (priority/browser-order nil)) (frequencies browser)))
    (is (= (count (:unit plan)) (count (distinct (:unit plan)))))))


(deftest alias-keywords-before-tests-preserve-priority-and-suite-tags
  (let [fixtures {"alias-unit" ["" "(deftest example (is true))"]
                  "alias-mixed" ["" "(deftest unit (is true)) (deftest ^:integration pg (is true))"]
                  "alias-integration-ns" ["^:integration " "(deftest example (is true))"]
                  "alias-integration-var" ["" "(deftest ^:integration example (is true))"]
                  "alias-integration-form" ["" "^:integration (deftest example (is true))"]
                  "alias-perf" ["^:perf " "(deftest example (is true))"]
                  "alias-container" ["" "(deftest ^:container-e2e example (is true))"]}
        files (into {}
                    (map (fn [[stem [ns-meta tests]]]
                           [(fixture-test-path (str "demo." stem "-test"))
                            (str "(ns " ns-meta "demo." stem "-test"
                                 " (:require [unavailable.storage :as sp]))"
                                 " (def fixture #(hash-map ::sp/candidate-count 1 ::local true)) "
                                 tests)]))
                    fixtures)
        changed (set (keys files))
        [{automatic :plan} {manual :plan}]
        (fixture-priority-plans
          [[changed nil]
           [changed "unit:demo.alias-unit-test,integration:demo.alias-integration-var-test"]]
          files)]
    (is (= ["demo.alias-mixed-test" "demo.alias-unit-test"] (:unit automatic)))
    (is (= ["demo.alias-integration-form-test" "demo.alias-integration-ns-test"
            "demo.alias-integration-var-test" "demo.alias-mixed-test"]
           (:integration automatic)))
    (is (= ["demo.alias-unit-test" "demo.alias-mixed-test"] (:unit manual)))
    (is (= ["demo.alias-integration-var-test" "demo.alias-integration-form-test"
            "demo.alias-integration-ns-test" "demo.alias-mixed-test"]
           (:integration manual)))))


(deftest manual-priority-rejects-typos-before-running
  (is (thrown? Exception (priority/manual-hints "unit:bad/name")))
  (is (thrown? Exception (priority/runtime-order nil ["missing.test.js"])))
  (is (thrown? Exception (priority/browser-order nil ["missing.test.js"])))
  (is (thrown? Exception (priority/plan nil {:unit ["missing.namespace-test"]}))))


(deftest fresh-bb-task-executes-full-roster-once-in-its-own-directory
  ;; A real child BB process compiles the task with ONLY bb.edn's :requires.
  ;; Evaluating its body in this test ns would mask a missing priority alias.
  (let [dir (fs/create-temp-dir)
        log (str (fs/path dir "node-calls.tsv"))
        stub (str (fs/path dir "node"))
        runtime-dir (str (fs/canonicalize "tools/runtime-test"))
        expected (set (map (fn [path] [runtime-dir "1" (str (fs/file-name path))])
                           (fs/glob "tools/runtime-test" "*.test.js")))]
    (try
      (spit stub "#!/usr/bin/env bash\nprintf '%s\\t%s\\t%s\\n' \"$PWD\" \"$#\" \"$1\" >> \"$CI_JS_TASK_CALL_LOG\"\n")
      (p/shell "chmod" "+x" stub)
      (let [result (p/shell {:out :string :err :string :continue true
                             :extra-env {"PATH" (str dir (System/getProperty "path.separator") (System/getenv "PATH"))
                                         "CI_JS_TASK_CALL_LOG" log "GRAPHDEN_CI_SINCE" ""
                                         "GRAPHDEN_CI_FIRST_TESTS" "runtime:org-switcher.test.js"}}
                            "bb" "test-js")
            calls (if (fs/exists? log) (mapv #(str/split % #"\t") (str/split-lines (slurp log))) [])]
        (is (zero? (:exit result)) (str (:out result) (:err result)))
        (is (= "org-switcher.test.js" (last (first calls))))
        (is (= expected (set calls)))
        (is (= (count expected) (count calls))))
      (finally (fs/delete-tree dir)))))


(let [{:keys [fail error]} (run-tests 'ci-priority-core-test)]
  (when (pos? (+ fail error)) (System/exit 1)))
