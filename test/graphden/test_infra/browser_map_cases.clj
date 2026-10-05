(ns graphden.test-infra.browser-map-cases
  "Ordinary stored compositions for browser/JVM HOF parity.")


(def definitions
  [{:name :read-item :parent :const :lambda-params [:item]
    :args {:value {:as :item}}}
   {:name :mapped :parent :map :args {:func :read-item :coll {:as :items}}}
   {:name :get-mapped :parent :get :args {:coll :mapped :key 0 :default "sequence"}}
   {:name :count-mapped :parent :count :args {:coll :mapped}}
   {:name :equal-mapped :parent :equal? :args {:a :mapped :b {:as :expected}}}
   {:name :read-theme :parent :const :args {:value {:as :theme}}}
   {:name :captured-pair :parent :list :lambda-params [:item]
    :args {:items [:read-item :read-theme]}}
   {:name :captured-map :parent :map :args {:func :captured-pair :coll {:as :rows}}}
   ;; Reading theme at the entry keeps this fixture on the existing public
   ;; argument surface; no new browser-only acceptance of captured arguments.
   {:name :captured-view :parent :list :args {:items [:read-theme :captured-map]}}
   {:name :bound-capture :parent :captured-view :args {:theme {:value false}}}
   {:name :constant-row :parent :const :lambda-params [] :args {:value 7}}
   {:name :constant-map :parent :map :args {:func :constant-row :coll {:as :items}}}
   {:name :domain-row :parent :const :args {:value {:as :domain}}}
   {:name :public-row :parent :domain-row :lambda-params [:public-item]
    :args {:domain {:as :public-item}}}
   {:name :public-map :parent :map :args {:func :public-row :coll {:as :items}}}
   {:name :label :parent :get :args {:coll {:as :item} :key {:value :label} :default ""}}
   {:name :row :parent :hiccup :lambda-params [:item]
    :args {:tag "button" :attrs {:value {:type "button"}} :children [:label]}}
   {:name :rows :parent :map :args {:func :row :coll {:as :items}}}
   {:name :view :parent :hiccup
    :args {:tag "div" :attrs {:value {:role "listbox"}} :children :rows}}
   ;; Same static callback target in two nested HOFs must receive independent
   ;; per-item frames; neither invocation may reuse the first item's result.
   {:name :inner-map :parent :map :lambda-params [:item]
    :args {:func :read-item :coll {:as :item}}}
   {:name :nested-map :parent :map :args {:func :inner-map :coll {:as :items}}}
   {:name :throw-row :parent :mod :lambda-params [:item]
    :args {:dividend 1 :divisor {:as :item}}}
   {:name :throw-map :parent :map :args {:func :throw-row :coll {:as :items}}}
   ;; get does not realize a seq: this fails only if map realizes callbacks
   ;; eagerly, before returning its otherwise unindexed sequence value.
   {:name :get-throw-map :parent :get
    :args {:coll :throw-map :key 0 :default "would-hide-lazy-error"}}
   {:name :untaken-map :parent :if
    :args {:test false :then :throw-map :else "safe"}}])


(def cases
  [{:entry :mapped :inputs {:items []} :expected [] :sequence true}
   {:entry :mapped :inputs {:items nil} :expected [] :sequence true}
   {:entry :mapped :inputs {:items [nil]} :expected [nil] :sequence true}
   {:entry :mapped :inputs {:items [3 nil false 8]} :expected [3 nil false 8] :sequence true}
   {:entry :get-mapped :inputs {:items [3 4]} :expected "sequence"}
   {:entry :count-mapped :inputs {:items [3 nil false]} :expected 3}
   {:entry :equal-mapped :inputs {:items [3 nil false] :expected [3 nil false]} :expected true}
   {:entry :equal-mapped :inputs {:items [3 4] :expected [3 5]} :expected false}
   {:entry :captured-view :inputs {:rows [1 2] :theme nil} :expected [nil [[1 nil] [2 nil]]]}
   {:entry :captured-view :inputs {:rows [1 2] :theme false} :expected [false [[1 false] [2 false]]]}
   {:entry :captured-view :inputs {:rows [1 2] :theme "accent"}
    :expected ["accent" [[1 "accent"] [2 "accent"]]]}
   {:entry :bound-capture :inputs {:rows [1 2]} :expected [false [[1 false] [2 false]]]}
   {:entry :constant-map :inputs {:items [1 nil false]} :expected [7 7 7]}
   {:entry :public-map :inputs {:items ["a" "b"]} :expected ["a" "b"]}
   {:entry :view :inputs {:items []} :expected [:div {:role "listbox"}] :sequence false}
   {:entry :view :inputs {:items [{:label "One"} {:label "Two"}]}
    :expected [:div {:role "listbox"} [:button {:type "button"} "One"] [:button {:type "button"} "Two"]]
    :sequence false}
   {:entry :nested-map :inputs {:items [[1 2] [3 nil] []]} :expected [[1 2] [3 nil] []]}
   {:entry :throw-map :inputs {:items [1 0]} :error true}
   {:entry :get-throw-map :inputs {:items [1 0]} :error true}
   {:entry :untaken-map :inputs {:items [1 0]} :expected "safe"}])
