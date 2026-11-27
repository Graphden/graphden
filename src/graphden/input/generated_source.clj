(ns graphden.input.generated-source
  "Offline transport for checked declarations shared by normalized source owners."
  (:require
    [graphden.input.migration :as migration]
    [graphden.input.query-lookup-inline :as query-inline]
    [graphden.input.query-lookup-package :as query]
    [graphden.input.response-signature :as response-signature]
    [graphden.input.source-origins :as origins]
    [graphden.input.source-snapshot :as snapshot]
    [graphden.input.staged-module :as staged]
    [graphden.packages.records.ids :as ids])
  (:import
    (java.nio.charset
      StandardCharsets)))


(defn- reject!
  [code]
  (throw (ex-info "Cannot verify generated source" {:type :input/generated-source :code code})))


(defn- source-hash
  [form]
  (ids/digest-hex "SHA-256" (migration/stable-edn (dissoc form :source-file :source-line))))


(defn- inline-origin
  [package owner {:keys [inline-site proof generated-ids]}]
  (let [{:keys [fn-id root-owner-id enclosing-owner-id source-path declaration]}
        (origins/child-at @(:inline-origins package) owner inline-site)]
    (when-not (and (= owner root-owner-id enclosing-owner-id) (= inline-site source-path)
                   (uuid? fn-id) (= fn-id (:owner-id proof) (get-in package [:declarations fn-id :fn-id]))
                   (some #{fn-id} generated-ids) (nil? (get-in package [:originals fn-id]))
                   (= fn-id (ids/fn-id (:namespace declaration) (:name declaration))))
      (reject! :unverified-generated-inline-origin))
    {:fn-id fn-id :root-owner-id root-owner-id :enclosing-owner-id enclosing-owner-id
     :source-path source-path :source-sha256 (source-hash declaration)}))


(defn- check-owner
  [package owner roots]
  (let [package (if (query/cached package owner roots) package
                    (or (query/certify package owner roots) (query/certify package owner roots query-inline/prepare)
                        (query/certify package owner roots response-signature/prepare)))
        expansion (query/cached package owner roots)
        {:keys [generated-ids]} expansion
        kind (get-in package [:closure-baselines owner :kind])]
    (when-not (and (#{:query-lookup :query-lookup-inline :response-signature-test} kind)
                   (seq generated-ids) (every? uuid? generated-ids)
                   (= (count generated-ids) (count (set generated-ids))))
      (reject! :generated-source-owner-required))
    (let [origin (when (= :query-lookup-inline kind) (inline-origin package owner expansion))]
      (when (some #(or (contains? roots %)
                       (and (not= % (:fn-id origin)) (contains? (:declarations package) %)))
                  generated-ids)
        (reject! :generated-source-name-collision))
      (let [normalized (reduce #(snapshot/with-certified-owner %1 %2 roots) (:package expansion) generated-ids)]
        (cond-> (assoc-in package [:query-lookups owner :package] normalized)
          origin (assoc-in [:query-lookups owner :inline-product-origin] origin))))))


(defn- add-product
  [products normalized id]
  (let [{:keys [proposed verification]} (get-in normalized [:closure-baselines id])]
    (when-not (and (= :authored-source-equivalent verification)
                   (or (nil? (:namespace proposed)) (string? (:namespace proposed))) (simple-keyword? (:name proposed))
                   (= id (ids/fn-id (:namespace proposed) (:name proposed))))
      (reject! :unverified-generated-declaration))
    (when (and (contains? products id) (not (migration/same-edn? (get products id) proposed)))
      (reject! :generated-source-name-collision))
    (assoc products id proposed)))


(defn- collect-source
  [package owner-ids roots]
  (when-not (and (coll? owner-ids) (seq owner-ids) (every? uuid? owner-ids)
                 (= (count owner-ids) (count (set owner-ids))))
    (reject! :generated-source-owner-required))
  (let [owners (sort owner-ids)
        package (reduce #(check-owner %1 %2 roots) package owners)
        entries (select-keys (:query-lookups package) owners)
        products (reduce (fn [products {:keys [package generated-ids]}]
                           (reduce #(add-product %1 package %2) products generated-ids))
                         {} (vals entries))
        modules (mapv (fn [[module forms]]
                        {:namespace module :input-format 2
                         :fns (mapv #(dissoc % :namespace) (sort-by :name forms))})
                      (sort-by key (group-by :namespace (vals products))))
        ledger (mapv (fn [owner]
                       (let [{:keys [original proposed generated-ids inline-product-origin]} (get entries owner)]
                         (cond-> {:owner-id owner :source-sha256 (source-hash original) :proposal-sha256 (source-hash proposed)
                                  :generated-ids (vec (sort generated-ids))}
                           inline-product-origin (assoc :inline-origin inline-product-origin))))
                     owners)]
    {:package package :artifact {:artifact-format 1 :owners ledger :modules modules}}))


(defn emit
  "Produce checksum-pinned UTF-8 EDN for an explicit set of verified original
   owners. Each generated declaration is emitted once in its true namespace."
  [package owner-ids roots]
  (let [artifact (:artifact (collect-source package owner-ids roots))
        text (migration/stable-edn artifact)]
    {:sha256 (ids/digest-hex "SHA-256" text) :bytes (String/.getBytes text StandardCharsets/UTF_8)}))


(defn- replay-products
  [normalized generated-ids modules]
  (reduce
    (fn [context {module-ns :namespace :keys [fns]}]
      (let [selected (filterv #(contains? generated-ids (ids/fn-id module-ns (:name %))) fns)]
        (if (seq selected)
          (let [original (mapv #(let [id (ids/fn-id module-ns (:name %))]
                                  (dissoc (first (get-in context [:originals id])) :namespace))
                               selected)
                replayed (snapshot/with-staged-module context {:namespace module-ns :fns original}
                                                      {:namespace module-ns :input-format 2 :fns selected})]
            (update replayed :staged-proposals #(merge (:staged-proposals context) %)))
          context)))
    normalized modules))


(defn install
  "Replay pinned sidecar bytes against trusted package sources, native signatures
   and an independently supplied owner set. Return a package for ordinary
   compile-staged-module. Generated functions remain private to their owners."
  [original owner-ids roots visible {payload :bytes :keys [sha256]}]
  (let [received (staged/read-pinned payload sha256)
        {:keys [artifact package]} (collect-source original owner-ids roots)]
    (when-not (migration/same-edn? artifact received)
      (reject! :generated-source-ledger-mismatch))
    (reduce (fn [context {:keys [owner-id generated-ids]}]
              (let [entry (get-in context [:query-lookups owner-id])
                    required (into (get-in entry [:proof :original-targets]) (:donors entry))]
                (when-not (and (set? visible) (contains? visible owner-id) (every? visible required))
                  (reject! :generated-source-not-visible))
                (-> context
                    (update-in [:query-lookups owner-id :package]
                               replay-products (set generated-ids) (:modules received))
                    (assoc-in [:query-lookups owner-id :generated-source] {:sha256 sha256}))))
            package (:owners received))))
