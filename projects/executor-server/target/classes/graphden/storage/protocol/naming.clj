(ns graphden.storage.protocol.naming
  "Naming convention utilities for storage backends.

   Provides consistent conversion between Clojure naming conventions
   (kebab-case keywords) and storage backend conventions (snake_case strings)."
  (:require
    [clojure.string :as str]))


(defn kw->snake-case
  "Converts a keyword to snake_case string.
   Example: :foo-bar -> \"foo_bar\""
  [k]
  (str/replace (name k) "-" "_"))


(defn snake->kw
  "Converts a snake_case string to kebab-case keyword.
   Example: \"foo_bar\" -> :foo-bar"
  [s]
  (keyword (str/replace s "_" "-")))


(defn check-snake-case-collisions!
  "Checks that converting keywords to snake_case doesn't create collisions.
   E.g., :foo-bar and :foo_bar would both become 'foo_bar'.
   Throws if collisions detected. Runs in O(n) time where n = number of keywords.

   Arguments:
   - context: map with context info for error reporting
   - keywords: collection of keywords to check"
  [context keywords]
  (let [;; Group keywords by their snake_case form in single pass - O(n)
        snake->originals (reduce (fn [acc kw]
                                   (update acc (kw->snake-case kw) (fnil conj []) kw))
                                 {}
                                 keywords)
        ;; Find groups with more than one original - O(n)
        collisions (into []
                         (comp (filter #(> (count (val %)) 1))
                               (map (fn [[snake originals]]
                                      {:snake-case snake :originals originals})))
                         snake->originals)]
    (when (seq collisions)
      (throw (ex-info "Snake_case naming collision detected"
                      (merge context {:type :validation-error/naming-collision
                                      :collisions collisions}))))))
