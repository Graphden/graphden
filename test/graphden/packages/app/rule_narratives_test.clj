(ns graphden.packages.app.rule-narratives-test
  "Contract tests for the return-type-rule popover's narrative table
   (`:_rtr-narratives` in `resources/packages/app/editor/fns.edn`) —
   the graph-resident successor of the JS `ruleNarrators` map that
   used to live in `editor-provenance-popover.js`.

   The roster below is HAND-MAINTAINED (inherited from the retired
   browser test): it guards prose drift and keeps roster == map-keys
   in both directions, but it can NOT detect a brand-new rule
   registered elsewhere — mechanical derivation isn't possible
   because `wrap-with-taint` gives every base-fn an opaque rule fn,
   so \"has a narrative-worthy rule\" is not observable from the
   registry. When adding a `:return-type-rule` with user-visible
   semantics, add its narrative to `:_rtr-narratives` AND its key
   here. Pure EDN comparison, no HTTP roundtrip."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.set :as set]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]))


(defn- read-fns-edn
  [resource-path]
  (-> (io/resource resource-path)
      slurp
      edn/read-string))


(defn- fn-defs
  [edn-content]
  (if (vector? edn-content)
    edn-content
    (:fns edn-content)))


(defn- fn-def-by-name
  [defs fn-name]
  (some #(when (= fn-name (:name %)) %) defs))


(def ^:private narrative-roster
  "Every return-type rule that carries a narrative. Hand-maintained —
   see the ns docstring for why it can't be registry-derived."
  #{:assoc :dissoc :get :get-in :assoc-in :update-in
    :conj :first :rest :cons :list :merge :into
    :range :repeat :keys :vals
    :take :drop :reverse :sort :distinct :concat
    :case :cond :coalesce :if :invoke :const :identity
    :add :sub :mul :mod :neg :abs})


(defn- narratives
  []
  (-> (read-fns-edn "packages/app/editor/fns.edn")
      fn-defs
      (fn-def-by-name :_rtr-narratives)
      (get-in [:args :value])))


(deftest narrative-map-covers-known-rule-roster
  (let [m (narratives)]
    (is (map? m) ":_rtr-narratives carries a literal map")
    (testing "every known rule has a non-blank narrative"
      (doseq [rule narrative-roster]
        (is (and (string? (get m rule))
                 (not (str/blank? (get m rule))))
            (str "rule " rule " has a narrative"))))
    (testing "no orphan narratives outside the roster (keep the roster in sync)"
      (is (= #{} (set/difference (set (keys m)) narrative-roster))))))


(deftest narrative-prose-spot-checks
  (let [m (narratives)]
    ;; Same properties the retired JS test asserted — the sentences
    ;; must keep naming the load-bearing semantics.
    (is (str/includes? (:assoc m) "field")
        ":assoc mentions field-add semantics")
    (is (str/includes? (:assoc m) "jsonb")
        ":assoc warns about the computed-key :jsonb degradation")
    (is (str/includes? (:get m) "record shape")
        ":get mentions the record-shape lookup")
    (is (str/includes? (:coalesce m) "null")
        ":coalesce mentions null-stripping")
    (is (str/includes? (:if m) "union")
        ":if mentions the branch union")
    (doseq [rule [:take :drop]]
      (is (str/includes? (get m rule) ":count")
          (str rule " mentions the :count parameter")))
    (doseq [rule [:reverse :sort :distinct]]
      (is (str/includes? (get m rule) "element type")
          (str rule " notes element-type preservation")))
    (is (str/includes? (:concat m) "LUB")
        ":concat mentions LUB across inputs")
    (doseq [rule [:add :sub :mul]]
      (is (str/includes? (get m rule) ":numeric")
          (str rule " mentions :numeric widening"))
      (is (str/includes? (get m rule) "Refinement constraints")
          (str rule " mentions refinement non-propagation")))
    (is (str/includes? (:mod m) ":numeric")
        ":mod mentions :numeric widening")
    (doseq [rule [:neg :abs]]
      (is (str/includes? (get m rule) "arithmetic")
          (str rule " notes constraint non-propagation through arithmetic")))))


(deftest return-type-rule-partial-route-is-public
  ;; The popover projects read-only type structure — it must stay on
  ;; the public `:get-route` parent (same posture as
  ;; `/partials/provenance`) and stay registered in the route group.
  (let [routes (fn-defs (read-fns-edn "packages/app/routes/fns.edn"))
        route  (fn-def-by-name routes :partial-return-type-rule)
        groups (fn-defs (read-fns-edn "packages/app/route-groups/fns.edn"))
        items  (some #(when (some #{:partial-return-type-rule}
                                  (get-in % [:args :items]))
                        (get-in % [:args :items]))
                     groups)]
    (is (some? route) "route fn-def exists")
    (is (= :get-route (:parent route)) "route is PUBLIC (:get-route)")
    (is (= "/partials/return-type-rule" (get-in route [:args :path])))
    (is (some? items) "route is registered in the route group")))
