(ns graphden.editor.component-export-fixture
  "Exercise imported component selections through ordinary personal roots."
  (:require
    [graphden.editor.components :as components]
    [graphden.editor.config-preparation :as preparation]
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
  (let [result (with-home selection-id #(preparation/prepare! ctx {:create true}))]
    (epoch/seed-watermark! (:storage ctx))
    result))


(defn export!
  [ctx selection-id component]
  (with-home selection-id #(components/export-resolved ctx {:component component})))
