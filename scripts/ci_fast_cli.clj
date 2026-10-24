(ns ci-fast-cli
  (:require
    [ci-fast :as fast]))


(if (= ["--status"] (vec *command-line-args*))
  (fast/show-status!)
  (fast/run-checks! (first *command-line-args*)))
