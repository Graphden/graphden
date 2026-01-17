(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'graphden/executor-server)
(def version "0.1.0")
(def class-dir "target/classes")
(def uber-file "target/executor-server.jar")

;; Paths relative to project root (projects/executor-server)
(def basis (b/create-basis {:project "deps.edn"}))

(defn clean [_]
  (b/delete {:path "target"}))

(defn uber [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["../../bases/executor-runtime/src"
                          "../../bases/executor-runtime/resources"
                          "../../components/base-functions/src"
                          "../../components/executor/src"
                          "../../components/fn-registry/src"
                          "../../components/http-kit-fns/src"
                          "../../components/reitit-fns/src"
                          "../../components/web-server/src"
                          "../../components/graph-storage-memory/src"
                          "../../components/graph-data-schema/src"
                          "../../components/malli-data-schema/src"
                          "../../components/memory-storage/src"
                          "../../components/data-schema-protocol/src"
                          "../../components/storage-protocol/src"
                          "../../components/field-types/src"]
               :target-dir class-dir})
  (b/compile-clj {:basis basis
                  :ns-compile '[graphden.executor-runtime.core]
                  :class-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis basis
           :main 'graphden.executor-runtime.core}))
