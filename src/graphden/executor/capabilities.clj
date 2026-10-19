(ns graphden.executor.capabilities
  "Trusted native/backend capability facts keyed by canonical fn identity.
   Browser ABI entries describe implemented operations only. User names,
   effects and authored execution claims cannot add backend support."
  (:require
    [graphden.packages.records.ids :as ids]))


(def browser-primitive-abi 1)


(def ^:private browser-primitives
  (into {}
        (for [[ns-path names] [["core.logic" [:const :if :equal? :nil? :coalesce]]
                               ["core.collections" [:list :get :assoc :zipmap :count :take :concat :vals]]
                               ["core.arithmetic" [:add :mod :lte]]
                               ["core.hof" [:map :filter :group-by :partition-by :sort-by]]
                               ["core.strings" [:str :str-starts-with? :str-lower :str-trim :str-contains? :str-clip]]
                               ["web.html" [:hiccup :as-ui-view :encode-view]]]
              n names]
          [(ids/fn-id ns-path n)
           {:browser-op (name n)
            :invoked-slots (if (= ns-path "core.hof")
                             #{(case n :map :func :filter :pred :key-fn)}
                             #{})}])))


(defn browser-op
  "Browser ABI operation for a canonical identity, or nil if unsupported."
  [fn-id]
  (get-in browser-primitives [fn-id :browser-op]))


(defn browser-primitive-ids
  "Exactly the canonical identities implemented by the browser ABI."
  []
  (set (keys browser-primitives)))


(defn invoked-slots
  "Proven immediate callback invocations in a supported primitive stage.
   nil means its stage contract is not known by this registry."
  [fn-id]
  (get-in browser-primitives [fn-id :invoked-slots]))


(def ^:private browser-callable-slot-ids
  (into #{}
        (mapcat (fn [[fn-id {callbacks :invoked-slots}]]
                  (map #(ids/slot-id fn-id %) callbacks)))
        browser-primitives))


(defn browser-callable-slot?
  "The bounded browser ABI implements closure invocation at this slot."
  [slot-id]
  (contains? browser-callable-slot-ids slot-id))


(defn native-execution
  "A real Clojure implementation proves JVM support. Browser support needs
   the canonical identity's implemented ABI entry, never an authored field.
   Missing implementations retain unknown execution support."
  [fn-id impl]
  (if (fn? impl)
    (cond-> #{:jvm-server} (browser-op fn-id) (conj :browser-main))
    :unknown))
