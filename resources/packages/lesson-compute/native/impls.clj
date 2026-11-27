(ns graphden.packages.lesson-compute.native.impls
  (:require
    [graphden.executor.defbase :refer [defbase]]))


(defbase build-greeting-record [profile]
  {:name (str "Hello " (:name profile))})


(defbase decorate-greeting [string mark]
  (str string mark))


(defbase deliver-greeting [profile style]
  (str ((:callback style) (:name profile)) " · " (:suffix style)))


(def impls
  {:build-greeting-record build-greeting-record
   :decorate-greeting decorate-greeting
   :deliver-greeting deliver-greeting})
