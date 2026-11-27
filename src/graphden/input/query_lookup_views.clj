(ns graphden.input.query-lookup-views
  "Frozen native call ABIs for the verified physical query-parameter reader."
  (:require
    [graphden.input.migration :as migration]
    [graphden.input.signatures :as signatures]
    [graphden.packages.records.ids :as ids]))


(def ^:private view-ns "graphden.query-replay")


(defn- kernel
  [module native args raw-args return raw-return execution]
  (let [original (ids/fn-id module native)
        witness (migration/derived-id :raw-native-view original raw-args raw-return)
        name (keyword (str "_native-" witness))]
    {:original original :id (ids/fn-id view-ns name) :ref (keyword view-ns (clojure.core/name name))
     :signature {:fn-id original :namespace module :name native :args args :return return
                 :effects #{} :execution execution :taint-propagate? true}
     :form {:namespace view-ns :name name :args (update-vals raw-args #(hash-map :type %)) :return-type raw-return}
     :raw-args raw-args}))


(def ^:private kernels
  [(kernel "core.collections" :get '{:coll [:union :null [:list :any] [:map a :any]] :key :any :default :any}
           {:coll :any :key :any :default :any} :any :any #{:jvm-server :browser-main})
   (kernel "core.collections" :get-in '{:map [:union :null [:map a :any]] :path [:list [:union :int :keyword :text]] :default :any}
           {:map :any :path [:list :any] :default :any} :any :any #{:jvm-server})
   (kernel "core.strings" :str-to-keyword {:string [:union :null :text]}
           {:string :any} [:union :keyword :null] :nullable-keyword #{:jvm-server})
   (kernel "web.crud" :_query-param-raw {:query-string [:union :null :text] :param-name :text}
           {:query-string :any :param-name :any} [:union :null :text] :nullable-text #{:jvm-server})])


(defn views
  "Only checked-in signatures can create a view. Resolve the donor by its
   original visible UUID, compare the complete native ABI and preserve all
   inherited execution, effect and taint policy. Query call sites still
   require their separate source witness. No executable is read from EDN."
  [roots visible]
  (when (every? (fn [{:keys [original id signature]}]
                  (let [entry (get roots original)]
                    (and (contains? visible original) (not (contains? visible id)) (not (contains? roots id))
                         (= signature (select-keys entry (keys signature)))
                         (signatures/local-type-rules? entry))))
                kernels)
    (mapv (fn [{:keys [original id ref form raw-args]}]
            (let [signature (-> (get roots original)
                                (assoc :fn-id id :name (:name form) :namespace view-ns :args raw-args)
                                (dissoc :return-type-rule :slot-types-rule :nav-types-rule :local-type-rule-evidence))]
              {:original original :id id :ref ref :form form :signature signature}))
          kernels)))
