(ns ci-fast-cli
  (:require
    [ci-fast :as fast]))


(fast/run-checks! (first *command-line-args*))
