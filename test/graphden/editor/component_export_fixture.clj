(ns graphden.editor.component-export-fixture
  "Exercise imported component selections through ordinary personal roots."
  (:require
    [graphden.editor.components :as components]
    [graphden.editor.config-preparation :as preparation]
    [graphden.graph.metadata :as metadata]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router.epoch :as epoch]
    [graphden.tenancy.context :as tenancy]))


(defn with-home
  [selection-id f]
  (with-redefs [tenancy/current-personal-namespace
                (constantly {:namespace-path (str "component-owner-" selection-id)
                             :source :fixture :isolated? false})]
    (f)))


(defn prepare!
  "Materialize the ordinary editor root from an already written selection."
  [ctx selection-id]
  (epoch/seed-watermark! (:storage ctx))
  (let [result (with-home selection-id
                 (fn []
                   ;; A configured home belongs to the account lifecycle;
                   ;; preparation may create only the addon-less operator home.
                   (let [storage (:storage ctx)
                         path (:namespace-path (tenancy/current-personal-namespace))]
                     (when-not (metadata/namespace-row storage path (tenancy/current-org))
                       (sp/create-entity storage :ns {:name path}))
                     (epoch/seed-watermark! storage)
                     (preparation/prepare! ctx {:create true}))))]
    (epoch/seed-watermark! (:storage ctx))
    result))


(defn export!
  [ctx selection-id component]
  (with-home selection-id #(components/export-resolved ctx {:component component})))
