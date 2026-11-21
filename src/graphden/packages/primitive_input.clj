(ns graphden.packages.primitive-input
  "A deployment-supplied checksum admits one pure primitive with an existing native ABI."
  (:require
    [clojure.java.io :as io]
    [graphden.input.source-primitive :as primitive]
    [graphden.input.staged-module :as staged]
    [graphden.packages.owned :as owned]
    [graphden.packages.records.ids :as ids]))


(defprotocol ^:private PrimitiveProof

  (receipt-evidence [receipt expected-seal]))


(deftype ^:private PrimitiveReceipt
  [seal parsed abi-id]

  PrimitiveProof

  (receipt-evidence
    [_ expected-seal]
    (when (identical? seal expected-seal)
      {:parsed parsed :abi-fn-id abi-id})))


(defonce ^:private seal (Object.))


(defn- reject!
  []
  (throw (ex-info "Package primitive admission failed" {:type :package-error/primitive-admission})))


(defn read-module
  "Preflight exactly the pinned classpath module before any package code runs."
  [package module {:keys [fn-id abi-fn-id sha256]}]
  (let [path (str "packages/" package "/" module "/fns.edn")
        resource (io/resource path)]
    (when-not (and resource (uuid? fn-id) (uuid? abi-fn-id) (not= fn-id abi-fn-id)) (reject!))
    (let [source (with-open [stream (io/input-stream resource)] (staged/read-pinned (java.io.InputStream/.readAllBytes stream) sha256))]
      (when-not (and (= 1 (count (:fns source))) (every? #{:input-format :namespace :description :fns} (keys source)))
        (reject!))
      (let [parsed (primitive/prepare source fn-id)
            definition (:source parsed)]
        (when (seq (:effects definition)) (reject!))
        {:ns-path (:namespace source) :ns-description (:description source) :fns [(assoc definition :source-file path)]
         :primitive (PrimitiveReceipt. seal parsed abi-fn-id)}))))


(defn- declaration
  "Recover the preflighted rows only for their declared package owner."
  [name definition]
  (when-let [receipt (::source (meta definition))]
    (let [{:keys [parsed] :as admitted}
          (when (instance? PrimitiveReceipt receipt)
            (receipt-evidence receipt seal))]
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
      (when-not (and (fn? impl) (identical? impl (:impl reference))
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
  [{:keys [parsed abi-fn-id]} impls signatures]
  (let [id (:fn-id parsed)
        signature (get signatures id)
        witness (get signatures abi-fn-id)
        impl (get impls (:name signature))
        abi-keys [:args :return :effects :return-type-rule :slot-types-rule :nav-types-rule
                  :taint-propagate? :lazy-seq-args :lambda-params :compile-time-value?]]
    (when-not (and (owned/owned-impl? id impl) (owned/owned-impl? abi-fn-id impl)
                   (identical? impl (get impls (:name witness)))
                   (= #{} (:effects witness)) (set? (:execution witness)) (contains? (:execution witness) :jvm-server)
                   (= (:args signature) (into {} (map (juxt :name :type)) (get-in parsed [:contract :fields])))
                   (= (:return signature) (get-in parsed [:source :return-type]))
                   (= (select-keys signature abi-keys) (select-keys witness abi-keys)))
      (reject!))
    (:declaration-records parsed)))
