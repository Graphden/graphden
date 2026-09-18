(ns graphden.packages.app.views.impls
  "Implementations for the app/views module — the Explorer's structured
   filter evaluation (`crud.entities.list/view-members`) behind the
   ad-hoc `POST /api/views/members` endpoint and the `:explorer-view`
   base-fn a view saved IN the graph extends."
  (:require
    [graphden.crud.entities :as entities]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]))


(defbase view-members
  [name uses effects kinds namespaces exclude unused]
  ;; An ad-hoc chip set from the editor — every axis a plain value.
  (cr/record-effect! :db)
  (entities/view-members ctx {:name name :uses uses :effects effects :kinds kinds
                              :namespaces namespaces :exclude exclude :unused unused}))


(defbase explorer-view
  [name uses effects kinds namespaces exclude unused also]
  ;; A view SAVED IN THE GRAPH: `uses` arrives as the bound fn's id (a
  ;; `:fn-ref` slot — identity, never evaluated), `also` as callables
  ;; over other views whose members this one intersects with.
  (cr/record-effect! :db)
  (let [own (entities/view-members ctx {:name name :uses (when uses [uses]) :effects effects
                                        :kinds kinds :namespaces namespaces :exclude exclude
                                        :unused unused})
        keep (reduce (fn [ids view-fn]
                       (let [theirs (into #{} (map :id) (:fns (view-fn nil)))]
                         (into #{} (filter theirs) ids)))
                     (into #{} (map :id) (:fns own))
                     (or also []))]
    (if (seq also)
      (assoc own :fns (filterv (comp keep :id) (:fns own)))
      own)))


(defbase explorer-views
  []
  ;; Every fn-def extending `:explorer-view`, filters decoded.
  (cr/record-effect! :db)
  (entities/list-explorer-views ctx))


(def impls
  {:view-members   view-members
   :explorer-view  explorer-view
   :explorer-views explorer-views})
