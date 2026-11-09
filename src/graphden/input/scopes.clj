(ns graphden.input.scopes
  "Derived reference-use graph. No declaration cloning or expression execution."
  (:require
    [graphden.input.context :as context]))


(defn- reference-entries
  [node]
  (->> (:bindings node) vals
       (filter #(= :ref (get-in % [:expression :kind])))
       (sort-by (comp str :binding-id))))


(defn- overlays-for
  [rows binding-id]
  (into []
        (comp (filter #(= binding-id (first (:scope-path %))))
              (map #(update % :scope-path (fn [path] (subvec path 1)))))
        rows))


(defn- validate-use-paths!
  [node entries]
  (let [by-id (into {} (map (juxt :binding-id identity)) entries)]
    (doseq [row (:scoped-rows node)]
      (let [entry (get by-id (first (:scope-path row)))]
        (when-not entry
          (context/reject! :unknown-reference-scope {:binding-id (:id row)}))
        (when (= :identity (get-in entry [:reference :mode]))
          (context/reject! :identity-reference-scope {:binding-id (:id row)}))))))


(defn analyze
  "analyze-node receives [target-fn-id [root-fn-id use-path] overlay-rows].
   The returned declared/remaining contracts remain per scope. Required
   addresses aggregate only evaluated value/producer uses; unresolved callable
   routing and cycles prevent complete coverage rather than guessing inputs."
  [root-fn-id analyze-node]
  (let [nodes (volatile! {})
        edges (volatile! [])
        gaps (volatile! [])
        budget (volatile! 4096)]
    (letfn [(visit
              [target path overlays ancestors]
              (when (or (neg? (vswap! budget dec)) (> (count path) 64))
                (context/reject! :reference-analysis-budget {}))
              (let [scope [root-fn-id path]
                    node (analyze-node target scope overlays)
                    entries (reference-entries node)]
                (validate-use-paths! node entries)
                (vswap! nodes assoc path (dissoc node :scoped-rows))
                (doseq [{:keys [address binding-id expression reference]} entries]
                  (let [mode (:mode reference)
                        ref-id (:fn-id expression)
                        child-path (conj path binding-id)
                        edge {:from address :to [root-fn-id child-path]
                              :binding-id binding-id :target-fn-id ref-id :mode mode}]
                    (when-not (= :identity mode)
                      (vswap! edges conj edge))
                    (cond
                      (contains? #{:value :callable-producer} mode)
                      (if (contains? (conj ancestors target) ref-id)
                        (vswap! gaps conj {:code :reference-cycle :address address :target-fn-id ref-id})
                        (visit ref-id child-path (overlays-for (:scoped-rows node) binding-id)
                               (conj ancestors target)))
                      (= :graph-callable mode)
                      (vswap! gaps conj {:code :capture-routing-required :address address})
                      (= :unresolved mode)
                      (vswap! gaps conj {:code :reference-mode-required :address address}))))))]
      (visit root-fn-id [] [] #{})
      (let [ordered (sort-by (comp (partial mapv str) key) @nodes)
            local-diagnostics (into []
                                    (comp (mapcat (comp :diagnostics val))
                                          (remove #(contains? #{:scoped-analysis-required
                                                                :reference-availability-required}
                                                              (:code %))))
                                    ordered)
            diagnostics (into local-diagnostics @gaps)
            complete? (empty? diagnostics)]
        {:fn-id root-fn-id :root-scope [root-fn-id []]
         :scopes @nodes :dependencies @edges
         :required-addresses (into [] (mapcat (comp :required-addresses val)) ordered)
         :diagnostics diagnostics :status (if complete? :checked :incomplete)
         :coverage {:recursive-availability (and (empty? @gaps)
                                                 (not-any? #(contains? #{:metadata-analysis-required :projection-analysis-required
                                                                         :resolver-analysis-required} (:code %))
                                                           diagnostics))
                    :type-checker (every? #(get-in % [:coverage :type-checker]) (vals @nodes))}}))))
