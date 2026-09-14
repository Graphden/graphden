(ns graphden.packages.starter-catalogue
  "The starter catalogue — the themes, keyboard layouts and example
   packages a fresh graphden's marketplace lists before anyone has
   published anything (docs/MARKETPLACE.md § 11).

   The catalogue is data: `resources/packages/registry/marketplace/
   starter-catalogue.edn`, one entry per listing in the publish route's
   own fields. `seed!` publishes each entry through the SAME row the
   route writes (`registry-shared/version-row` + `insert-or-exists!`,
   under the package-name lock), in the platform tier — so every seeded
   version is `public? true` / `status approved` and appears on the
   cards, the storefront and Settings at once, and a user's later
   publish under one of these names is refused as `name-taken` like
   any other public name.

   Idempotent by `(name, version)`: a version already in the registry is
   skipped (a restart, a second pod, a withdrawn version), and a name
   another org already lists publicly is left alone (`:held`) — the
   platform never squats a user's public name. Changing a listing means
   bumping its `:version` in the file; rows are immutable."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.tools.logging :as log]
    [graphden.packages.registry-shared :as shared]
    [graphden.storage.protocol.core :as sp]))


(def resource-path
  "Classpath path of the catalogue — inside the registry package, next to
   the marketplace modules that render it."
  "packages/registry/marketplace/starter-catalogue.edn")


(defn read-catalogue
  "The shipped catalogue — a vector of listing maps (see the file's
   header for the three entry shapes)."
  []
  (-> resource-path io/resource slurp edn/read-string))


(defn- entry->publish
  "One catalogue entry as the publish path's arguments — the bundle (a
   fns entry's `:ns-root` / `:fns` / `:dependencies`, each fn-def's
   `:namespace` defaulting to the root; a theme / keymap carries the
   empty bundle the marketplace publish route uses) and the listing."
  [{:keys [kind ns-root fns dependencies package-dependencies] :as entry}]
  (let [fns? (= "fns" kind)]
    {:bundle (if fns?
               {:namespace ns-root
                :fns (mapv #(update % :namespace (fn [ns] (or ns ns-root))) fns)
                :dependencies (vec dependencies)
                :package-dependencies (vec package-dependencies)
                :secrets []}
               {:namespace "" :fns [] :dependencies [] :package-dependencies [] :secrets []})
     :listing (select-keys entry [:kind :description :category :tags :payload])}))


(defn- seed-one!
  "Publish one entry unless its `(name, version)` exists or another org
   holds the name publicly. Returns `:published` / `:exists` / `:held`."
  [storage {pkg-name :name pkg-version :version :as entry}]
  (shared/with-package-name-lock
    storage pkg-name
    (fn []
      (cond
        (seq (sp/query-entities (shared/platform-base storage) :package-version
                                {:name pkg-name :version pkg-version}))
        :exists

        (shared/foreign-public-holder storage pkg-name)
        :held

        :else
        (let [{:keys [bundle listing]} (entry->publish entry)]
          ;; nil = lost a race to the UNIQUE key — the row is there either way
          (if (shared/insert-or-exists!
                storage
                (shared/version-row pkg-name pkg-version bundle true listing))
            :published
            :exists))))))


(defn seed!
  "Publish every catalogue entry that is not yet in the registry. The
   1-arity reads the shipped file; the 2-arity takes the catalogue (tests).
   Returns `{:published [name@version …] :exists [...] :held [...]}` and
   logs one summary line."
  ([storage]
   (seed! storage (read-catalogue)))
  ([storage catalogue]
   (let [outcome (reduce (fn [acc {:keys [name version] :as entry}]
                           (update acc (seed-one! storage entry) conj (str name "@" version)))
                         {:published [] :exists [] :held []}
                         catalogue)]
     (log/info "[starter-catalogue]"
               (count (:published outcome)) "published,"
               (count (:exists outcome)) "already present,"
               (count (:held outcome)) "held by another org"
               (when (seq (:held outcome)) (str "— " (pr-str (:held outcome)))))
     outcome)))
