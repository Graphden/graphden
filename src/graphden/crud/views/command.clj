(ns graphden.crud.views.command
  "The bounded Save view wire format; no storage or authorization decisions."
  (:require
    [clojure.string :as str]
    [graphden.crud.entities.views :as views]
    [graphden.types.core :as types]))


(defn reject!
  [reason]
  (throw (ex-info reason {:type :validation-error/view-command :reason reason})))


(defn- uuid-value
  [value field]
  (or (when (uuid? value) value)
      (when (string? value) (parse-uuid value))
      (reject! (str field " must be a UUID"))))


(defn- text-value
  [value field]
  (when-not (and (string? value) (<= (count value) 512))
    (reject! (str field " must be text of at most 512 characters")))
  value)


(defn- axis-values
  [axis values]
  (when-not (and (sequential? values) (<= (count values) 64))
    (reject! (str axis " must contain at most 64 values")))
  (mapv #(if (contains? #{:uses :views} axis)
           (uuid-value % axis)
           (text-value % axis)) values))


(defn parse-command
  "Normalize a full filter replacement. Absence of id creates a new view;
   updating an existing view never changes its parent identity."
  [body]
  (when-not (map? body) (reject! "Save view requires a JSON object"))
  (when (and (contains? body :id) (contains? body :create-id))
    (reject! "Use id to update or create-id to create, never both"))
  (let [view-name (:name body)
        filters (:filters body)
        axes #{:uses :views :effects :kinds :problems :namespaces :exclude}]
    (when-not (and (string? view-name) (not (str/blank? view-name)) (<= (count view-name) 160))
      (reject! "Give the view a non-empty name of at most 160 characters"))
    (when-not (and (map? filters)
                   (every? (conj axes :name :unused) (keys filters)))
      (reject! "Unknown or missing filter axes"))
    (doseq [axis axes :when (contains? filters axis)]
      (axis-values axis (get filters axis)))
    (when (some? (:name filters)) (text-value (:name filters) :name))
    (when (and (contains? filters :unused) (not (boolean? (:unused filters))))
      (reject! "unused must be a boolean"))
    (let [filters (views/normalise-filters filters)]
      (doseq [[axis allowed] [[:kinds #{:fn :types :secrets :services :apps :tests}]
                              [:problems #{:failed :type-errors :lint}]
                              [:effects types/known-effect-categories]]]
        (when-not (every? allowed (get filters axis))
          (reject! (str "Unknown " (name axis) " value"))))
      (cond-> {:name (str/trim view-name) :filters filters}
        (:id body) (assoc :id (uuid-value (:id body) :id))
        (contains? body :create-id) (assoc :create-id (uuid-value (:create-id body) :create-id))
        (contains? body :namespace-id)
        (assoc :namespace-id (when (:namespace-id body)
                               (uuid-value (:namespace-id body) :namespace-id)))))))
