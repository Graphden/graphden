(ns graphden.input.package-execution
  "Explicit offline execution boundary for prepared, verified package source.
   Does not register implementations or select a production compiler."
  (:require
    [graphden.input.analysis :as analysis]
    [graphden.input.compile :as compile]
    [graphden.input.form-query :as form-query]
    [graphden.input.generated-source :as generated]
    [graphden.input.ir :as ir]
    [graphden.input.list-builder :as builder]
    [graphden.input.list-execution :as list-execution]
    [graphden.input.metadata :as metadata]
    [graphden.input.query-lookup-inline :as query-inline]
    [graphden.input.query-lookup-package :as query]
    [graphden.input.response-signature :as response-signature]
    [graphden.input.source-snapshot :as snapshot]
    [graphden.input.staged-module :as staged]
    [graphden.packages.records.ids :as ids]
    [graphden.types.core :as types]))


(defn- compile-frozen
  [{:keys [context options]} checked implementations]
  (let [implementations (update-vals
                          (:implementations options)
                          (fn [{:keys [base-fn-id generated-list] :as entry}]
                            (let [impl (if generated-list
                                         (list-execution/compile-arguments (:operands generated-list))
                                         (get implementations base-fn-id))]
                              (when-not (fn? impl)
                                (ir/reject! :resolved-implementation-required {:base-fn-id base-fn-id}))
                              (cond-> (assoc entry :impl impl)
                                generated-list (assoc :compile-delayed-list list-execution/compile-delayed-arguments)))))]
    {:context context :analysis checked
     :invoke (compile/compile-named checked {:context context :implementations implementations
                                             :detached-closures? true})}))


(def ^:private zipmap-list-slots
  (set (map #(ids/slot-id (ids/fn-id "core.collections" :zipmap) %) [:keys :vals])))


(defn- checked-dictionary-producers
  [checked]
  (into #{} (keep (fn [{:keys [from to mode]}]
                    (when (= :value mode)
                      (let [node (get-in checked [:scopes (second (first from))])
                            binding (get-in node [:bindings from])]
                        (when (= [:map :text :text] (metadata/asserted-read-type node binding (get-in binding [:reference :actual-type])))
                          (second to))))))
        (:dependencies checked)))


(defn- check-legacy-invocation
  "Append remains open for composition. At V1 invocation the final unused
   zipmap continuation contributes no items. Specialize only an unresolved
   public tail of that exact generated use, then rerun the ordinary checker.
   Staged modules specialize only tails beneath an explicitly checked dictionary read."
  [{:keys [context options]} root staged?]
  (let [checked (analysis/analyze-effective-input context root options)
        producers (when staged? (checked-dictionary-producers checked))
        ends (into {}
                   (for [{:keys [name kind type required whole-bindable? origin-field-id address]}
                         (when (= :checked (:status checked)) (vals (get-in checked [:public-input :routes])))
                         :when (and (= :input-field kind) (= :tail name) (false? required) whole-bindable?
                                    (types/list-type? type) (types/type-var? (types/list-elem type)))
                         :let [[scope path] address
                               node (get-in checked [:scopes (second scope)])
                               id (:fn-id node)
                               {:keys [owner-id target-id tail] :as fragment} (get-in context [:source-list-fragments id])
                               abi (:signature-source node)]
                         :when (and (= :open tail) (zipmap-list-slots target-id) (uuid? owner-id)
                                    (= id (:builder-id fragment) (:base-fn-id abi))
                                    (= owner-id (get-in abi [:generated-list :owner-id]))
                                    (true? (get-in abi [:generated-list :infer-return?]))
                                    (= path [origin-field-id] [(:tail-id fragment)] (get-in abi [:argument-paths :tail]))
                                    (or (not staged?) (some #(= % (vec (take (count %) (second scope)))) producers))
                                    (= (builder/origin owner-id target-id)
                                       (select-keys fragment [:owner-id :target-id :binding-id :builder-id :tail-id])))]
                     [scope (builder/empty-tail-binding fragment)]))]
    (if (seq ends)
      (analysis/analyze-effective-input context root (assoc options :legacy-list-ends ends))
      checked)))


(defn- compile-normalized
  [{:keys [package roots proof generated-source]} owner implementations]
  (let [frozen (snapshot/freeze-source-analysis package [owner] roots)
        frozen (if-let [prior (:form-callback proof)]
                 (let [{:keys [frozen proof]} (form-query/checked-form package frozen owner)]
                   (when-not (= prior proof) (ir/reject! :form-source-mismatch {}))
                   frozen)
                 frozen)
        checked (analysis/analyze-effective-input (:context frozen) owner (:options frozen))
        compiled (compile-frozen frozen checked implementations)]
    (cond-> (assoc compiled :input-origin proof)
      generated-source (assoc :generated-source generated-source))))


(defn compile-package
  "Freeze and check a package closure, then lower its named residual inputs.
   package comes from source-snapshot/package-index. roots are frozen rich
   entries by base UUID; implementations are explicit base UUID -> function.
   Both must originate in the same prepared package runtime. Returns evidence
   and an ordinary executor closure, without publishing either globally.
   :invoke always uses the structured V2 surface. :input-origin, when
   present, records legacy identities for offline differential replay."
  [package root roots implementations]
  (if-let [expansion (or (query/cached package root roots)
                         (query/prepare package root roots)
                         (query-inline/prepare package root roots)
                         (form-query/prepare package root roots)
                         (response-signature/prepare package root roots))]
    (compile-normalized expansion root implementations)
    (let [frozen (snapshot/freeze-source-analysis package [root] roots)]
      (compile-frozen frozen (check-legacy-invocation frozen root false) implementations))))


(defn- require-visible-root!
  [root visible-fn-ids]
  (when-not (and (set? visible-fn-ids) (contains? visible-fn-ids root))
    (ir/reject! :staged-function-not-visible {})))


(defn- private-replay-targets
  [package owners]
  (into #{}
        (comp (mapcat #(get-in package [:query-lookups % :visibility]))
              (remove #(contains? (:declarations package) %)))
        owners))


(defn compile-staged-module
  "Offline replay of one staged module against original package preparation.
   Supply the trusted legacy module and package index, the staged bytes and
   their expected receipt SHA-256, and the caller's visible executable UUIDs.
   Only a root from the staged module can be compiled here. Dependencies
   outside the module still come from the caller's visible prepared package.
   An optional generated-source receipt supplies private declarations for
   normalized owners reached by the checked root. No registration, package
   loading, or storage changes occur."
  [package root roots implementations {payload :bytes :keys [legacy-module sha256 visible-fn-ids generated-source]}]
  (require-visible-root! root visible-fn-ids)
  (let [module (staged/read-pinned payload sha256)
        owners (:owner-ids generated-source)
        package (cond-> package generated-source
                        (generated/install owners roots visible-fn-ids generated-source))
        package (reduce (fn [p {:keys [name]}]
                          (let [id (ids/fn-id (:namespace legacy-module) name)]
                            (if (query/cached p id roots)
                              p
                              (or (query/certify p id roots) (query/certify p id roots query-inline/prepare) (query/certify p id roots form-query/prepare)
                                  (query/certify p id roots response-signature/prepare) p))))
                        package (:fns legacy-module))
        package (snapshot/with-staged-module package legacy-module module)]
    (when-not (contains? (:staged-proposals package) root)
      (ir/reject! :staged-root-required {}))
    (if-let [expansion (get-in package [:query-lookups root])]
      (do
        (when (and generated-source (not (some #{root} owners)))
          (ir/reject! :generated-source-owner-required {}))
        (when-not (every? visible-fn-ids (into (get-in expansion [:proof :original-targets]) (:donors expansion)))
          (ir/reject! :staged-function-not-visible {}))
        (assoc (compile-normalized expansion root implementations)
               :staged-source {:sha256 sha256 :namespace (:namespace module)}))
      (let [frozen (snapshot/freeze-source-analysis package [root] roots)
            executable (get-in frozen [:options :implementations])
            reached-owners (filter executable owners)
            ;; Verified source expansion also introduces anonymous branches.
            ;; Original declarations and all native donors still require visibility.
            visible-targets (into visible-fn-ids (private-replay-targets package reached-owners))]
        (when (and generated-source (empty? reached-owners))
          (ir/reject! :generated-source-owner-required {}))
        (doseq [[target-id {:keys [base-fn-id generated-list]}] executable]
          ;; The checked owner grants visibility to its generated list.
          ;; Each operand reference is still checked independently.
          (when-not (if generated-list
                      (and (= target-id base-fn-id) (contains? visible-targets (:owner-id generated-list)))
                      (and (contains? visible-targets target-id) (contains? visible-fn-ids base-fn-id)))
            (ir/reject! :staged-function-not-visible {:fn-id target-id :base-fn-id base-fn-id})))
        (cond-> (assoc (compile-frozen frozen (check-legacy-invocation frozen root true) implementations)
                       :staged-source {:sha256 sha256 :namespace (:namespace module)})
          generated-source (assoc :generated-source (select-keys generated-source [:sha256])))))))


(defn compile-staged-primitive
  "Read a staged file and compile one visible, independent primitive root.
   Supply the expected receipt checksum and the package's frozen signatures
   and implementations. Declaration and field identities come from the v2
   source alone. Roots requiring bindings or dependency preparation are blocked."
  [root roots implementations {payload :bytes :keys [sha256 visible-fn-ids]}]
  (require-visible-root! root visible-fn-ids)
  (let [module (staged/read-pinned payload sha256)
        frozen (snapshot/freeze-primitive module root roots)]
    (assoc (compile-frozen frozen (analysis/analyze-effective-input (:context frozen) root (:options frozen)) implementations)
           :staged-source {:sha256 sha256 :namespace (:namespace module)})))


(defn compile-staged-literal-root
  "Compile one visible v2 root with scalar literal bindings and a visible
   primitive parent declared in the same pinned file."
  [root roots implementations {payload :bytes :keys [sha256 visible-fn-ids]}]
  (require-visible-root! root visible-fn-ids)
  (let [module (staged/read-pinned payload sha256)
        frozen (snapshot/freeze-literal-root module root roots visible-fn-ids)]
    (assoc (compile-frozen frozen (analysis/analyze-effective-input (:context frozen) root (:options frozen)) implementations)
           :staged-source {:sha256 sha256 :namespace (:namespace module)})))
