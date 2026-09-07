(ns graphden.storage.postgres.graph-epoch-bump-guard-test
  "Guard: the graph epoch is bumped in ONE place — `with-bump*` in
   `versioning.storage.core`, which pairs every bump with its note on
   the refused path and whose request log the handlers drain. An
   explicit `epoch/bump!` anywhere else is a bump nobody notes: it ages
   past the heal grace and costs a spurious heal 45 s later — four such
   calls in the branch dials (policy / require-merge / review-state /
   review-policy) were the e2e suite's residual heals until 2026-09-07."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest is]]))


(def ^:private allowed
  #{"src/graphden/versioning/storage/core.clj"
    "src/graphden/storage/postgres/graph_epoch.clj"})


(defn- clj-files
  [root]
  (->> (file-seq (io/file root))
       (filter #(and (java.io.File/.isFile %)
                     (str/ends-with? (java.io.File/.getName %) ".clj")))))


(defn- bump-sites
  [^java.io.File f]
  (let [path (java.io.File/.getPath f)]
    (->> (str/split-lines (slurp f))
         (keep-indexed (fn [i line]
                         (when (and (re-find #"\(\s*(?:epoch|graph-epoch|ge)/bump!\b" line)
                                    (not (str/starts-with? (str/trim line) ";")))
                           (str path ":" (inc i)))))
         (remove (fn [_] (contains? allowed path))))))


(deftest graph-epoch-is-bumped-only-through-with-bump-star
  (let [sites (mapcat bump-sites (concat (clj-files "src/graphden")
                                         (clj-files "resources/packages")))]
    (is (empty? sites)
        (str "explicit epoch bumps outside with-bump* (each needs a note, or none): "
             (str/join ", " sites))))
  (is (str/includes? (slurp "src/graphden/versioning/storage/core.clj") "(defn- with-bump*")
      "the one sanctioned bump site still exists"))
