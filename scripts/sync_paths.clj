(ns sync-paths
  "Sync component/base paths to deps.edn and tests.edn"
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.string :as str]))


(defn find-paths
  "Find all src/test paths for components and bases"
  []
  (let [components (fs/list-dir "components")
        bases (fs/list-dir "bases")
        ->paths (fn [dirs]
                  (->> dirs
                       (map fs/file-name)
                       (sort)
                       (mapcat (fn [name]
                                 [(str "components/" name "/src")
                                  (str "components/" name "/test")]))))]
    {:component-paths (->> components
                           (map fs/file-name)
                           (sort)
                           (mapcat (fn [name]
                                     [(str "components/" name "/src")
                                      (str "components/" name "/test")])))
     :base-paths (->> bases
                      (map fs/file-name)
                      (sort)
                      (mapcat (fn [name]
                                [(str "bases/" name "/src")
                                 (str "bases/" name "/resources")])))}))


(defn read-edn [path]
  (edn/read-string (slurp path)))


(defn write-edn [path data]
  (spit path (with-out-str (clojure.pprint/pprint data))))


(defn update-deps-edn
  "Update deps.edn with component/base paths"
  [{:keys [component-paths base-paths]}]
  (let [deps (read-edn "deps.edn")
        all-paths (vec (concat ["development/src"]
                               component-paths
                               base-paths))
        test-paths (vec (concat component-paths base-paths))]
    (-> deps
        (assoc-in [:aliases :dev :extra-paths] all-paths)
        (assoc-in [:aliases :test :extra-paths] test-paths))))


(defn update-tests-edn
  "Update tests.edn with test paths"
  [{:keys [component-paths base-paths]}]
  (let [test-paths (->> (concat component-paths base-paths)
                        (filter #(str/ends-with? % "/test"))
                        (vec))]
    {:tests [{:id :unit
              :test-paths test-paths}]
     :plugins [:kaocha.plugin/profiling]}))


(defn sync! []
  (let [paths (find-paths)]
    (println "Found paths:" paths)
    (write-edn "deps.edn" (update-deps-edn paths))
    (println "✓ Updated deps.edn")
    (spit "tests.edn"
          (str "#kaocha/v1\n"
               (with-out-str (clojure.pprint/pprint (update-tests-edn paths)))))
    (println "✓ Updated tests.edn")))


(when (= *file* (System/getProperty "babashka.file"))
  (sync!))
