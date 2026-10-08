(ns graphden.packages.app.views.impls
  "Implementations for the app/views module — the Explorer's structured
   filter evaluation (`crud.entities.views/view-members`) behind the
   ad-hoc `POST /api/views/members` endpoint and the `:explorer-view`
   base-fn a view saved IN the graph extends."
  (:require
    [graphden.crud.entities :as entities]
    [graphden.crud.views :as views]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]))


(defbase view-members
  [name uses effects kinds problems namespaces exclude unused views]
  ;; An ad-hoc chip set from the editor — every axis a plain value.
  (cr/record-effect! :db)
  (entities/view-members ctx {:name name :uses uses :effects effects :kinds kinds :problems problems
                              :namespaces namespaces :exclude exclude :unused unused
                              :views views}))


(defbase explorer-view
  [name uses uses-all effects kinds problems namespaces exclude unused also also-all]
  ;; A view SAVED IN THE GRAPH: `uses` arrives as the bound fn's id (a
  ;; `:fn-ref` slot — identity, never evaluated), `also` as another view's
  ;; id (`:fn-ref` too) whose members this one intersects with; the
  ;; evaluator resolves it through its decoded slots, recursively
  ;; through ITS `also`, cycle-guarded.
  (cr/record-effect! :db)
  (entities/view-members ctx {:name name :uses (cond-> (vec uses-all) uses (conj uses)) :effects effects
                              :kinds kinds :problems problems :namespaces namespaces :exclude exclude
                              :unused unused :views (cond-> (vec also-all) also (conj also))}))


(defbase explorer-views
  []
  ;; Every fn-def extending `:explorer-view`, filters decoded.
  (cr/record-effect! :db)
  (entities/list-explorer-views ctx))


(defbase parse-view-command
  [body]
  (views/parse-command body))


(defbase save-explorer-view
  [command _request]
  (cr/record-effect! :db)
  (views/save! ctx command))


(def impls
  {:view-members   view-members
   :explorer-view  explorer-view
   :explorer-views explorer-views
   :parse-view-command {:impl parse-view-command :taint-propagate? true}
   :save-explorer-view {:impl save-explorer-view :taint-propagate? true}})
