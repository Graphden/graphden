(ns graphden.crud.services
  "Implementation primitives consumed by service-related graph fn-defs
   in `resources/packages/app/execution`. Currently just the popover
   renderer that replaces `editor-service-popover.js`'s in-JS DOM
   construction; service orchestration (start/stop/reconcile) stays in
   `graphden.services.reconciler`."
  (:require
    [clojure.string :as str]))


(def ^:private restart-policies ["always" "on-failure" "never"])


(defn- service-row-branch-id
  [svc]
  (or (:branch-id svc) (get svc "branch-id")))


(defn- service-row-fn-id
  [svc]
  (or (:fn-id svc) (get svc "fn-id")))


(defn- service-row-running
  [svc]
  (or (:running svc) (get svc "running")))


(defn- running-field
  [running k]
  (when running (or (get running k) (get running (name k)))))


(defn- pick-existing
  "Mirrors `loadServiceForFn` in the JS module: prefer the row whose
   `:branch-id` matches `current-branch-id`; fall back to the legacy
   null-branch row; otherwise the first match. nil when no row
   targets `fn-id`."
  [services fn-id current-branch-id]
  (let [matches (filter #(= fn-id (service-row-fn-id %)) services)]
    (or (some (fn [s] (when (= current-branch-id (service-row-branch-id s)) s)) matches)
        (some (fn [s] (when (nil? (service-row-branch-id s)) s)) matches)
        (first matches))))


(defn- sibling-services
  "Other rows for the SAME fn-id, excluding `existing-id`. Used for the
   ⚠ cross-branch warning. Returns rows in input order — caller already
   sorted the services list."
  [services fn-id existing-id]
  (filter (fn [s]
            (and (= fn-id (service-row-fn-id s))
                 (not= (:id s) existing-id)))
          services))


(defn- sibling-state-label
  [svc]
  (let [r (service-row-running svc)]
    (cond
      (running-field r :stopper-set?)   "running"
      (running-field r :start-failed-at) "failed"
      (or (:enabled? svc) (get svc "enabled?")) "pending"
      :else "disabled")))


(defn- short-time
  [s]
  (when (and s (>= (count s) 19)) (subs s 11 19)))


(defn- status-line
  [running]
  (cond
    (nil? running) "Not yet started (reconcile to apply)."
    (running-field running :start-failed-at)
    (str "Start failed — exhausted "
         (or (running-field running :start-attempts) 1)
         " retries at "
         (short-time (running-field running :start-failed-at))
         " UTC.")
    (running-field running :stopper-set?)
    (str "Running"
         (when-let [t (short-time (running-field running :started-at))]
           (str " since " t " UTC"))
         ".")
    :else "Tracked but no active stopper."))


(defn- sibling-warning
  [siblings branches-by-id]
  (when (seq siblings)
    (let [labels (->> siblings
                      (map (fn [s]
                             (let [b-id (service-row-branch-id s)
                                   b-name (or (some-> b-id branches-by-id :name)
                                              (if b-id "<unknown branch>" "(any)"))]
                               (str b-name " (" (sibling-state-label s) ")"))))
                      (str/join ", "))]
      [:div {:class "service-popover-sibling-warn"
             :title (str "Same fn-id, different branch. Reconciler keeps each branch's "
                         "instance separate — verify this is intentional before adding "
                         "another.")}
       (str (if (= 1 (count siblings)) "⚠ Also a service on: " "⚠ Also services on: ")
            labels)])))


(defn- enabled-field
  [existing]
  [:label {:class "service-popover-option"}
   [:input (cond-> {:type "checkbox" :class "service-popover-enabled"}
             (or (nil? existing) (:enabled? existing)) (assoc :checked "checked"))]
   " Enabled"])


(defn- branch-field
  [existing branches default-branch-id]
  (let [pre-selected (or (some-> existing :branch-id str)
                         (some-> default-branch-id str)
                         "")]
    [:div {:class "service-popover-branch"}
     [:div {:class "service-popover-branch-label"} "Branch:"]
     [:select {:class "service-popover-branch-select"}
      [:option (cond-> {:value ""}
                 (= "" pre-selected) (assoc :selected "selected"))
       "(any — legacy)"]
      (for [b branches]
        (let [bid-str (str (:id b))]
          [:option (cond-> {:value bid-str}
                     (= bid-str pre-selected) (assoc :selected "selected"))
           (:name b)]))]]))


(defn- policy-field
  [existing]
  (let [current (some-> (:restart-policy existing) name (str/replace #"^:" ""))
        chosen  (or current "always")]
    [:div {:class "service-popover-policy"}
     [:div {:class "service-popover-policy-label"} "Restart policy:"]
     (for [p restart-policies]
       [:label {:class "service-popover-policy-option"}
        [:input (cond-> {:type "radio"
                         :name "service-restart-policy"
                         :value p}
                  (= p chosen) (assoc :checked "checked"))]
        (str " " p)])]))


(defn- action-bar
  [existing]
  [:div {:class "service-popover-actions"}
   [:button {:type "button"
             :class "service-popover-save-btn"
             :data-existing-service-id (some-> existing :id str)}
    (if existing "Save & reconcile" "Create & reconcile")]
   (when existing
     [:button {:type "button"
               :class "service-popover-delete-btn"
               :data-existing-service-id (str (:id existing))
               :title "Removes the :service row; reconcile stops the running fn."}
      "Delete service"])])


(defn render-service-popover-hiccup
  "Body hiccup for the service settings popover. JS-side
   `editor-service-popover.js showServicePopover` used to build the
   entire popover from `loadServiceForFn` + `fetchBranchesForPicker`
   + per-section DOM construction; this renders the same shape from
   one server-side join.

   `fn-id` / `fn-name` identify the target. `enriched-services` is the
   `:_list-services-enriched` rows (each carries `:running` snapshot).
   `branches` is the decoded `:branch` rows. `current-branch-id` drives
   both the existing-row selection (prefer current-branch over null)
   and the default branch in the picker for brand-new rows.

   Button click handlers stay in `editor-service-popover.js`, bound
   post-swap via `.service-popover-{save,delete,close}-btn` selectors;
   `data-existing-service-id` carries the row id (or empty for new)
   so the JS save handler can pick PUT vs POST."
  [fn-id fn-name enriched-services branches current-branch-id]
  (let [existing      (pick-existing enriched-services fn-id current-branch-id)
        siblings      (sibling-services enriched-services fn-id (:id existing))
        branches-by-id (into {} (map (juxt :id identity) branches))
        title         (str (if existing "Service: :" "Make service: :")
                           (or fn-name "(anonymous)"))]
    [:div
     [:div {:class "service-popover-header"}
      [:span {:class "service-popover-title"} title]
      [:button {:type "button"
                :class "service-popover-close"
                :aria-label "Close service popover"}
       "×"]]
     (when existing
       [:div {:class "service-popover-status"} (status-line (service-row-running existing))])
     (sibling-warning siblings branches-by-id)
     (enabled-field existing)
     (branch-field existing branches current-branch-id)
     (policy-field existing)
     (action-bar existing)]))
