(ns graphden.test-infra.browser-picker-cases
  "Actual picker row graphs, including finite nested HOFs and retained badges."
  (:require
    [clojure.string :as str]))


(def row
  {:key "candidate-one" :option-id "gd-fixture-option-0" :qualified-name "core.add"
   :label "add" :compatible true :active true :fit "captures" :fit-label "Needs inputs"
   :fit-title "Needs another argument" :kind "base-fn" :return-label "number"
   :effects [{:code "db" :label "DB"} {:code "network" :label "NETWORK"}]})


(def rendered-row
  [:div {:key "candidate-one" :id "gd-fixture-option-0"
         :class "fn-picker-row fn-picker-row-compat fn-picker-row-active"
         :role "option" :aria-selected "true" :data-picker-key "candidate-one"
         :data-fn-name "core.add" :title ""}
   [:span {:class "fn-picker-row-ok" :aria-hidden "true"} "✓"]
   [:span {:class "fn-picker-row-main"} "add"]
   [:span {:class "fn-picker-row-fit fn-picker-fit-captures" :title "Needs another argument"} "Needs inputs"]
   [:span {:class "fn-picker-row-kind fn-picker-row-kind-base-fn" :title "Function kind"} "base-fn"]
   [:span {:class "fn-picker-row-effects"}
    [:span {:key "db" :class "effects-chip effects-chip-db"} "DB"]
    [:span {:key "network" :class "effects-chip effects-chip-network"} "NETWORK"]]
   [:span {:class "fn-picker-row-rt"} "→ " "number"]])


(def dense-effects
  (mapv (fn [code] {:code code :label (str/upper-case code)})
        ["db" "env" "io" "network" "time" "misc" "random" "process" "raw-sql"]))


(def dense-row (assoc row :effects dense-effects))


(def rendered-dense-row
  (assoc rendered-row 6
         (into [:span {:class "fn-picker-row-effects"}]
               (map (fn [{:keys [code label]}]
                      [:span {:key code :class (str "effects-chip effects-chip-" code)} label])
                    dense-effects))))


(def styles
  [{:selector "& .fn-picker-row-active"
    :declarations {"background-color" "color-mix(in srgb, var(--fg) 12%, transparent)"}}])


(def full-model
  {:sections [{:key "exact" :kind "exact" :show-header true :count 1 :open true :foldable false :rows [row]}
              {:key "group-namespace-root" :header-key "group-namespace-root-header"
               :option-id "gd-fixture-option-1" :kind "group" :label "root" :count 3 :other-count 2
               :show-header true :foldable true :open false :active false :truncated true :rows []}]
   :empty-kind "none" :show-other-toggle true :show-other false :hidden-other 4})


(def rendered-full-view
  {:tree [:div {:class "fn-picker-results"} nil
          [[:div {:key "exact" :class "fn-picker-exact"}
            [:div {:class "fn-picker-ns-header"} nil
             [:span {:class "fn-picker-ns-name"} "Exact match"]
             [:span {:class "fn-picker-ns-count"} " · " 1 nil]]
            [rendered-row] nil]
           [:div {:key "group-namespace-root" :class "fn-picker-group fn-picker-group-folded"}
            [:div {:key "group-namespace-root-header" :id "gd-fixture-option-1"
                   :class "fn-picker-ns-header fn-picker-ns-toggle" :role "option"
                   :aria-selected "false" :aria-expanded "false" :data-picker-key "group-namespace-root-header"}
             [:span {:class "fn-picker-disclosure-arrow"} "▶"]
             [:span {:class "fn-picker-ns-name"} "root"]
             [:span {:class "fn-picker-ns-count"} " · " 3 [" · " 2 " other"]]]
            nil nil]]
          [:button {:type "button" :class "fn-picker-other-toggle"
                    :aria-pressed "false" :data-picker-toggle "other"}
           ["Show " 4 " fns of other types"]]]
   :styles styles})


(def cases
  [{:entry :picker-row :inputs {:row row} :expected rendered-row}
   {:entry :picker-styles :inputs {} :expected styles}
   {:entry :picker-view :inputs {:model full-model} :expected rendered-full-view}
   {:entry :picker-view
    :inputs {:model {:sections [] :empty-kind "search" :show-other-toggle false :show-other false :hidden-other 0}}
    :expected {:tree [:div {:class "fn-picker-results"}
                      [:div {:class "fn-picker-empty"} "No fn is named like that"] [] nil]
               :styles styles}}])
