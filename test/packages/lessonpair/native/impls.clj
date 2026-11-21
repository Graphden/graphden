(ns graphden.packages.lessonpair.native.impls
  (:require
    [graphden.executor.defbase :refer [defbase]]))


(defbase contact-route
  [sender recipient]
  (str (:name sender) " in " (get-in sender [:address :city]) " → "
       (:name recipient) " in " (get-in recipient [:address :city])))


(def impls {:contact-route-native contact-route})
