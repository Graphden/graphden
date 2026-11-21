(ns graphden.packages.primitive-input
  "A deployment-supplied checksum admits a pure native primitive and verified literal descendants."
  (:require
    [clojure.java.io :as io]
    [graphden.input.analysis :as analysis]
    [graphden.input.pinned-list :as lists]
    [graphden.input.source-primitive :as primitive]
    [graphden.input.source-snapshot :as snapshot]
    [graphden.input.staged-module :as staged]
    [graphden.packages.owned :as owned]
    [graphden.packages.records.ids :as ids]
    [graphden.types.core :as types]))


(defprotocol ^:private PrimitiveProof

  (receipt-evidence [receipt expected-seal]))


(deftype ^:private PrimitiveReceipt
  [seal parsed abi-id staged children list-module]

  PrimitiveProof

  (receipt-evidence
    [_ expected-seal]
    (when (identical? seal expected-seal)
      {:parsed parsed :abi-fn-id abi-id :module staged :children children :list-module list-module})))


(defonce ^:private seal (Object.))


(defn- reject!
  []
  (throw (ex-info "Package primitive admission failed" {:type :package-error/primitive-admission})))


(defn- require-receipt!
  [receipt]
  (or (when (instance? PrimitiveReceipt receipt)
        (receipt-evidence receipt seal))
      (reject!)))


(defn- literal-descendants
  [{module-ns :namespace :as source} root]
  (when-not (every? (comp simple-keyword? :name) (:fns source)) (reject!))
  (let [ids (mapv #(ids/fn-id module-ns (:name %)) (:fns source))
        visible (set ids)]
    (when (or (not= (count ids) (count visible)) (not (contains? visible root))) (reject!))
    (mapv (fn [id]
            (let [[parent child] (primitive/prepare-literal-root source id visible)]
              (when-not (= root (:fn-id parent)) (reject!))
              child))
          (remove #{root} ids))))


(defn read-module
  "Preflight exactly the pinned classpath module before any package code runs."
  [package module {:keys [fn-id abi-fn-id sha256 record-contract list-contract]}]
  (let [path (str "packages/" package "/" module "/fns.edn")
        resource (io/resource path)]
    (when-not (and resource (uuid? fn-id) (uuid? abi-fn-id) (not= fn-id abi-fn-id)) (reject!))
    (let [source (with-open [stream (io/input-stream resource)]
                   (staged/read-pinned (java.io.InputStream/.readAllBytes stream) sha256))]
      (when-not (every? #{:input-format :namespace :description :fns} (keys source))
        (reject!))
      (when (and list-contract record-contract) (reject!))
      (let [list-module (when list-contract (lists/prepare source fn-id list-contract))
            parsed (if list-module (first (:definitions list-module)) (primitive/prepare source fn-id record-contract))
            definition (:source parsed)
            children (if list-module (subvec (:definitions list-module) 1) (literal-descendants source fn-id))
            receipt (PrimitiveReceipt. seal parsed abi-fn-id source children list-module)]
        (when (seq (:effects definition)) (reject!))
        {:ns-path (:namespace source) :ns-description (:description source)
         :fns (into [(assoc definition :source-file path)]
                    (map (fn [child]
                           (with-meta (assoc (:source child) :source-file path) {::literal-descendant receipt})))
                    children)
         :primitive receipt}))))


(defn literal-descendant?
  "Recognize a child that will be written with its boot-owned parent."
  [definition]
  (when-let [receipt (::literal-descendant (meta definition))]
    (let [{:keys [module children]} (require-receipt! receipt)
          module-ns (:namespace module)]
      (when-not (some #(= (assoc (:source %) :namespace module-ns) (dissoc definition :source-file))
                      children)
        (reject!))
      true)))


(defn- declaration
  "Recover the preflighted rows only for their declared package owner."
  [name definition]
  (when-let [receipt (::source (meta definition))]
    (let [{:keys [parsed] :as admitted} (require-receipt! receipt)]
      (when-not (= (:fn-id parsed) (ids/fn-id (:namespace definition) name)) (reject!))
      admitted)))


(defn declarations
  [definitions]
  (into {} (keep (fn [[name definition]]
                   (when-let [source (declaration name definition)] [name source]))) definitions))


(defn check-implementations!
  "Before publication or writes, a selected native must be the independently
   loaded reference implementation. Runtime overrides cannot change either."
  [sources definitions overrides]
  (doseq [[name source] sources]
    (let [[reference-name reference]
          (some (fn [[n d :as entry]] (when (= (:abi-fn-id source) (ids/fn-id (:namespace d) n)) entry))
                definitions)
          impl (get-in definitions [name :impl])]
      (when-not (and (not (contains? sources reference-name))
                     (fn? impl) (identical? impl (:impl reference))
                     (identical? impl (get overrides name impl))
                     (identical? impl (get overrides reference-name impl))
                     (= (get-in source [:parsed :source :return-type]) (:return-type reference))
                     (= (into {} (map (juxt :name :type)) (get-in source [:parsed :contract :fields]))
                        (update-vals (:args reference) :type))
                     (empty? (:effects reference)))
        (reject!)))))


(defn checked-records
  "Validate the independently registered package ABI and exact native function
   identity. Both entries must come from the current registration."
  [{:keys [parsed abi-fn-id module children list-module]} impls signatures]
  (let [id (:fn-id parsed)
        signature (get signatures id)
        witness (get signatures abi-fn-id)
        impl (get impls (:name signature))
        declared-args (into {} (map (juxt :name (comp types/resolve-alias :type))) (get-in parsed [:contract :fields]))
        declared-return (types/resolve-alias (get-in parsed [:source :return-type]))
        abi-keys [:args :return :effects :return-type-rule :slot-types-rule :nav-types-rule
                  :taint-propagate? :lazy-seq-args :lambda-params :compile-time-value?]]
    (when-not (and (owned/owned-impl? id impl) (owned/owned-impl? abi-fn-id impl)
                   (identical? impl (get impls (:name witness)))
                   (= #{} (:effects witness)) (set? (:execution witness)) (contains? (:execution witness) :jvm-server)
                   (= (:args signature) declared-args)
                   (= (:return signature) declared-return)
                   (= (select-keys signature abi-keys) (select-keys witness abi-keys)))
      (reject!))
    (if list-module
      ((requiring-resolve 'graphden.input.pinned-list-runtime/checked-declarations) list-module id signature impl)
      (do
        (doseq [{owner :fn-id} children]
          (let [{:keys [context options]} (snapshot/freeze-literal-root module owner signatures #{id owner})
                result (analysis/analyze-effective-input context owner options)]
            (when-not (= :checked (:status result)) (reject!))))
        ;; Explicitly clear removed defaults when resync updates existing rows.
        (into (mapv (fn [row]
                      (if (and (= :fn-slot (:kind row)) (= (:input-type-id parsed) (:fn-id row)))
                        (merge {:input-default nil} row)
                        row)) (:declaration-records parsed))
              (comp (mapcat :declaration-records) (remove (set (:declaration-records parsed))) (distinct))
              children)))))
