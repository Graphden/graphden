(ns graphden.open-core-seam-test
  "Guard for the open-core split: the vars the private `graphden-tenancy`
   addon reaches in THIS repo (`tools/open-core-seam.edn`) must all still
   resolve.

   The failure mode this exists for is one-directional and quiet. Most of
   these vars have no caller in graphden — they exist FOR the addon — so a
   refactor can rename or relocate one and every test here stays green.
   The break then surfaces in a repo this CI cannot see, at pin-bump time,
   with a compile error and no obvious culprit.

   That happened: splitting `crud/entities.clj` moved `view-impl-filter`
   into `crud.entities.list` and re-exported its two neighbours but not
   it. Nothing in graphden reads that atom — the addon `reset!`s it at
   boot — so the whole suite passed and the tenancy suite did not.

   Scope, honestly: this proves the NAME still resolves, not that its
   contract is unchanged. An arity change or a semantic flip still slips
   through. It closes the cheap half of the gap, which is the half that
   keeps happening.

   The list is generated from the addon's own requires. Regenerate it when
   the addon reaches for something new — an unlisted var is not an error,
   it is simply not protected yet."
  (:require
    [clojure.edn :as edn]
    [clojure.test :refer [deftest is]]))


(def ^:private seam-path "tools/open-core-seam.edn")


(deftest every-addon-facing-var-still-resolves-test
  (let [seam (edn/read-string (slurp seam-path))
        missing (for [[ns-sym syms] seam
                      s syms
                      :let [qualified (symbol (name ns-sym) (name s))]
                      :when (nil? (try (requiring-resolve qualified)
                                       (catch Exception _ nil)))]
                  qualified)]
    (is (seq seam) "the seam registry is not empty")
    (is (empty? missing)
        (str "var(s) the tenancy addon depends on no longer resolve. Either "
             "re-export them from where they used to live, or update the addon "
             "AND " seam-path " in the same landing: " (pr-str (vec missing))))))
