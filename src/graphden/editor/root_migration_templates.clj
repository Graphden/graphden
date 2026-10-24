(ns graphden.editor.root-migration-templates
  "Deterministic graph declarations for migrating the finite pre-root
   component selection. Old callback identities remain referenced; their
   bodies and inherited edits are never copied into the new HOF cohort."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [graphden.editor.component-config :as config]
    [graphden.editor.root-legacy :as legacy]
    [graphden.packages.rebase :as rebase]))


(defn- refuse!
  []
  (throw (ex-info "Legacy root migration is unavailable"
                  {:type :browser-plan/unsupported :reason :invalid-configuration})))


(defn overrides
  "Return only authored role identities that differ from shipped callbacks.
   Missing roles inherit the shared root."
  [configuration]
  (when-not (map? configuration) (refuse!))
  ;; The ordinary configuration also contains components outside this root
  ;; bridge. Keep their checked selection intact for the later migration.
  (when-not (every? (set (keys config/component-contracts)) (keys configuration))
    (refuse!))
  (into (sorted-map)
        (keep (fn [[component {:keys [roles]}]]
                (let [selected (get configuration component)
                      _ (when (and (some? selected)
                                   (or (not (map? selected))
                                       (not-every? (set roles) (keys selected))))
                          (refuse!))
                      changed (into (sorted-map)
                                    (keep (fn [role]
                                            (let [id (get selected role)]
                                              (when (some? id)
                                                (when-not (uuid? id) (refuse!))
                                                (when-not (= id (legacy/default-role-id component role))
                                                  [role id])))))
                                    roles)]
                  (when (seq changed) [component changed]))))
        legacy/components))


(defn- packaged-definitions
  [path]
  (let [resource (str "packages/" (str/replace path "." "/") "/fns.edn")]
    (:fns (edn/read-string (slurp (io/resource resource))))))


(defn- local-name
  [component role suffix]
  (keyword (str "_migration-" (name component) "-" (name role) "-" suffix)))


(defn- wrapper-definitions
  [root component role]
  (let [{path :namespace wrappers :wrapper} (get legacy/components component)
        definitions (packaged-definitions path)
        local-names (set (map :name definitions))
        template (some #(when (= (get wrappers role) (:name %)) %) definitions)
        full-view (when (and (= component :account-menu) (= role :view))
                    (some #(when (= :root-view (:name %)) %) definitions))
        qualify (fn [reference]
                  (if (and (keyword? reference) (nil? (namespace reference))
                           (contains? local-names reference))
                    (keyword path (name reference))
                    reference))]
    (when-not (and template (or (not (and (= component :account-menu) (= role :view))) full-view))
      (refuse!))
    (let [bridge (assoc (rebase/rewrite-references template qualify)
                        :name (local-name component role (if full-view "base" "wrapper"))
                        :namespace root
                        :parent (keyword root (name (local-name component role "source"))))]
      (if full-view
        ;; The old account-menu callback supplies state/theme. The shipped
        ;; root-view adds the server/common-tree envelope expected by the new
        ;; root-component contract; retain that composition by reference.
        [bridge
         (-> full-view
             (rebase/rewrite-references qualify)
             (assoc :name (local-name component role "wrapper") :namespace root)
             (assoc-in [:args :map] (keyword root (name (:name bridge)))))]
        [bridge]))))


(defn- component-definitions
  [root component changed]
  (let [{path :namespace base :root-component} (get legacy/components component)
        roles (vec (keys changed))]
    (mapv (fn [index role]
            {:name (local-name component role "component")
             :namespace root
             :parent (keyword path (str "_root-" (name role) "-assoc"))
             :return-type (keyword path "root-component")
             :args {:map (if (zero? index)
                           (keyword path (name base))
                           (keyword root (name (local-name component
                                                           (nth roles (dec index))
                                                           "component"))))
                    :key {:value role}
                    :value {:ref (keyword root (name (local-name component role "wrapper")))}}})
          (range (count roles)) roles)))


(defn- root-definitions
  [root changed]
  (let [components (vec (keys changed))
        steps (mapv (fn [index component]
                      (let [last-role (last (keys (get changed component)))]
                        {:name (keyword (str "_migration-root-" (name component)))
                         :namespace root
                         :parent :core.collections/assoc
                         :return-type :app.ui-config/_editor-bootstrap-result
                         :args {:map (if (zero? index)
                                       :app.ui-config/component-editor-root
                                       (keyword root (str "_migration-root-"
                                                          (name (nth components (dec index))))))
                                :key {:value component}
                                :value (keyword root (name (local-name component last-role "component")))}}))
                    (range (count components)) components)
        result (if (seq components)
                 (keyword root (name (:name (last steps))))
                 :app.ui-config/component-editor-root)]
    (conj steps
          {:name :editor-root :namespace root :parent :call
           :return-type :app.ui-config/_editor-bootstrap-result
           :args {:func {:ref result} :arg {:as :inputs :type :keyword-map}}})))


(defn definitions
  "Build a fresh personal HOF root from a checked legacy configuration.
   The returned aliases map to original role UUIDs only inside the parser."
  [root configuration]
  (when-not (and (string? root) (not (str/blank? root))) (refuse!))
  (let [changed (overrides configuration)
        wrappers (mapcat (fn [[component roles]]
                           (mapcat #(wrapper-definitions root component %) (keys roles))) changed)
        components (mapcat (fn [[component roles]]
                             (component-definitions root component roles)) changed)
        references (into {}
                         (mapcat (fn [[component roles]]
                                   (map (fn [[role id]]
                                          [(keyword root (name (local-name component role "source"))) id]) roles)))
                         changed)]
    {:definitions (vec (concat wrappers components (root-definitions root changed)))
     :references references :overrides changed}))
