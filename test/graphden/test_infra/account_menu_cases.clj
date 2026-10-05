(ns graphden.test-infra.account-menu-cases
  "Shared value-level cases for the browser evaluator and actual HTTP execution."
  (:require
    [cheshire.core :as json]
    [graphden.crud.request :as request]))


(def view
  {:frame [:div {:class "auth-menu" :role "menu" :aria-label "Account and editor"}]
   :common-rows [[:button {:type "button" :class "auth-menu-item" :role "menuitem"
                           :tabindex -1 :data-action "settings" :data-item "Settings"} "Settings"]
                 [:button {:type "button" :class "auth-menu-item" :role "menuitem"
                           :tabindex -1 :data-action "operate" :data-item "Organization"} "Organization"]]
   :active 2 :phase "open" :handled false
   :theme-tokens {"--gd-flow" "#123456" "--bg" "#abcdef"}
   :menu-tokens {"--gd-account-menu-hover" "#abcdef"}
   :motion {:duration-ms 160 :offset-y 0 :opacity 1}})


(defn cases
  []
  (let [closed {:phase "closed" :active 0 :handled false}
        open {:phase "open" :active 2 :handled false}
        items ["Settings" "Organization" "Interactive tutorial" "Sign out" "Website"]
        update-case (fn [state kind key index entries expected]
                      {:entry :account-menu-update
                       :inputs {:state state :event {:kind kind :key key :index index}
                                :context {:items entries}}
                       :expected expected})
        ;; This is the SAME recursive JSON decoding used by POST /api/execute.
        ;; A CSS string-keyed input map would turn into keywords here. The
        ;; theme input is deliberately a record; output CSS keys stay strings.
        view-input (:args (request/read-json-body
                            {:body (json/generate-string
                                     {:args {:state open
                                             :theme {:accent "#123456" :canvas-background "#abcdef"}}})}))]
    [{:entry :account-menu-initial :inputs {} :expected closed}
     (update-case closed "open" "" -1 items {:phase "opening" :active 0 :handled true})
     (update-case open "keydown" "ArrowDown" 4 items (assoc open :active 0 :handled true))
     (update-case open "keydown" "ArrowUp" 0 items (assoc open :active 4 :handled true))
     (update-case open "keydown" "Home" -1 items (assoc open :active 0 :handled true))
     (update-case open "keydown" "End" -1 items (assoc open :active 4 :handled true))
     (update-case open "keydown" "ArrowDown" -1 [] (assoc open :active 0 :handled true))
     (update-case open "keydown" "End" -1 [] (assoc open :active 0 :handled true))
     (update-case open "keydown" "Enter" -1 items open)
     (update-case (assoc open :handled true) "keydown" "x" -1 items open)
     (update-case open "keydown" "Escape" -1 items (assoc open :phase "closing" :handled true))
     (update-case open "keydown" "Tab" -1 items (assoc open :phase "closing" :handled true))
     (update-case open "activate" "" 3 items (assoc open :phase "closing" :handled true))
     (update-case open "focus" "" 4 items (assoc open :active 4 :handled true))
     (update-case (assoc open :phase "closing") "open" "" -1 items
                  {:phase "opening" :active 0 :handled true})
     (update-case (assoc open :phase "closing") "hover" "" 4 items (assoc open :phase "closing"))
     (update-case (assoc open :phase "closing") "animation-finished" "" -1 items (assoc open :phase "closed"))
     (update-case closed "animation-finished" "" -1 items closed)
     {:entry :account-menu-view :inputs view-input :expected view}
     {:entry :account-menu-view :inputs (assoc view-input :state closed)
      :expected (assoc view :active 0 :phase "closed"
                       :motion {:duration-ms 160 :offset-y -4 :opacity 0})}]))
