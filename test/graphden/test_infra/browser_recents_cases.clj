(ns graphden.test-infra.browser-recents-cases
  "Navigation trail cases shared by JVM and bounded browser evaluation.")


(def a {:id "a" :name "add" :qname "core.add"})
(def b {:id "b" :name "map" :qname "core.map"})
(def c {:id "c" :name "get" :qname "core.get"})
(def state {:pins [b] :trail [a b c a]})


(def state-cases
  [{:entry :recents-initial :inputs {:inputs state} :expected state}
   {:entry :recents-initial :inputs {:inputs {}} :expected {:pins [] :trail []}}
   {:entry :recents-update :inputs {:state state :event {:kind "push" :entry a}}
    :expected {:pins [b] :trail [a b c]}}
   {:entry :recents-update :inputs {:state state :event {:kind "toggle-pin" :entry b}}
    :expected {:pins [] :trail [a b c a]}}
   {:entry :recents-update :inputs {:state state :event {:kind "toggle-pin" :entry a}}
    :expected {:pins [a b] :trail [a b c a]}}
   {:entry :recents-update :inputs {:state state :event {:kind "push" :entry (assoc a :name "_anon-123")}}
    :expected state}
   {:entry :recents-update :inputs {:state state :event {:kind "push" :entry (assoc a :name "")}}
    :expected state}
   {:entry :recents-update
    :inputs {:state {:pins [] :trail (mapv #(assoc c :id (str %)) (range 7))}
             :event {:kind "push" :entry a}}
    :expected {:pins [] :trail (into [a] (map #(assoc c :id (str %)) (range 5)))}}
   {:entry :recents-update :inputs {:state state :event {:kind "unknown"}} :expected state}
   {:entry :recents-model :inputs {:state state :inputs {:selected "a"}} :expected {:trail [c] :hidden false}}
   {:entry :recents-model :inputs {:state {:pins [] :trail (vec (repeat 8 c))} :inputs {:selected nil}}
    :expected {:trail (vec (repeat 5 c)) :hidden false}}
   {:entry :recents-hidden :inputs {:state state :inputs {:searching true}} :expected true}
   {:entry :recents-hidden :inputs {:state {:pins [] :trail []} :inputs {}} :expected true}
   {:entry :recents-hidden :inputs {:state state :inputs {}} :expected false}])


(defn transition-case
  [{:keys [entry inputs expected] :as test-case}]
  (if (= entry :recents-update)
    (let [{:keys [kind]} (:event inputs)
          requests (case kind
                     "toggle-pin" [{:kind "persist-pins" :entries (:pins expected)}]
                     "push" (if (= expected (:state inputs)) [] [{:kind "persist-trail" :entries (:trail expected)}])
                     [])]
      (-> test-case
          (update :inputs #(assoc % :inputs {}))
          (assoc :expected {:state expected :requests requests})))
    test-case))


(def cases
  (conj (mapv transition-case state-cases)
        {:entry :recents-update
         :inputs {:state state :event {:kind "navigate" :entry b} :inputs {}}
         :expected {:state state :requests [{:kind "navigate-fn" :fn-id "b" :qname "core.map"}]}}
        {:entry :recents-update
         :inputs {:state state :event {:kind "sync" :entry a} :inputs {:pins [] :trail [c]}}
         :expected {:state {:pins [] :trail [c]} :requests []}}))
