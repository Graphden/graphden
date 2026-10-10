(ns graphden.test-infra.browser-recents-cases
  "Navigation trail cases shared by JVM and bounded browser evaluation.")

(def a {:id "a" :name "add" :qname "core.add"})
(def b {:id "b" :name "map" :qname "core.map"})
(def c {:id "c" :name "get" :qname "core.get"})
(def state {:pins [b] :trail [a b c a]})

(def cases
  [{:entry :recents-initial :inputs {:context state} :expected state}
   {:entry :recents-initial :inputs {:context {}} :expected {:pins [] :trail []}}
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
   {:entry :recents-visible-trail :inputs {:state state :context {:selected "a"}} :expected [c]}
   {:entry :recents-visible-trail :inputs {:state {:pins [] :trail (vec (repeat 8 c))} :context {:selected nil}}
    :expected (vec (repeat 5 c))}
   {:entry :recents-hidden :inputs {:state state :context {:searching true}} :expected true}
   {:entry :recents-hidden :inputs {:state {:pins [] :trail []} :context {}} :expected true}
   {:entry :recents-hidden :inputs {:state state :context {}} :expected false}])
