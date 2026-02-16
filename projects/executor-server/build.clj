(ns build
  (:require
    [clojure.tools.build.api :as b]))


(def lib 'graphden/executor-server)
(def version "0.1.0")
(def class-dir "target/classes")
(def uber-file "target/executor-server.jar")


;; Paths relative to project root (projects/executor-server)
(def basis (b/create-basis {:project "deps.edn"}))


(defn clean
  [_]
  (b/delete {:path "target"}))


(defn uber
  [_]
  (clean nil)
  (b/copy-dir {:src-dirs [;; Base runtime
                          "../../bases/executor-runtime/src"
                          "../../bases/executor-runtime/resources"
                          ;; Main source directory (all components migrated here)
                          "../../src"]
               :target-dir class-dir})
  (b/compile-clj {:basis basis
                  :ns-compile '[graphden.executor-runtime.core]
                  :class-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis basis
           :main 'graphden.executor-runtime.core}))
