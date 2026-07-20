(ns graphden-crac-poc
  (:require
    [graphden.packages.loader :as loader])
  (:import
    (java.nio.file
      Files
      OpenOption
      Paths)))


(defn -main
  [& _]
  (let [t0 (System/currentTimeMillis)
        pkgs (loader/load-packages ["core" "storage" "web" "app"])
        dt (- (System/currentTimeMillis) t0)
        n (count (:base-fn-defs pkgs))]
    (Files/write (Paths/get "/tmp/gpoc.ready" (make-array String 0))
                 (String/.getBytes (str "loaded " n " base-fn-defs in " dt " ms"))
                 (make-array OpenOption 0))
    (println "READY: loaded" n "base-fn-defs in" dt "ms")
    (loop [] (Thread/sleep 300) (recur))))
