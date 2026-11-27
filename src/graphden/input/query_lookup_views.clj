(ns graphden.input.query-lookup-views
  "Exact native declarations used by the reviewed Ring query expansion."
  (:require
    [graphden.input.signatures :as signatures]
    [graphden.packages.records.ids :as ids]))


(def ^:private signatures
  (let [portable #{:jvm-server :browser-main}
        server #{:jvm-server}]
    [["core.collections" :get '{:coll [:union :null [:list :any] [:map a :any]] :key :any :default :any} :any portable]
     ["core.collections" :get-in '{:map [:union :null [:map a :any]] :path [:list [:union :int :keyword :text]] :default :any} :any server]
     ["core.strings" :str-to-keyword {:string [:union :null :text]} [:union :keyword :null] server]
     ["web.crud" :_query-param-raw {:query-string [:union :null :text] :param-name :text} [:union :null :text] server]
     ["core.logic" :coalesce '{:value [:union :null a] :default [:union :null a]} 'a portable]
     ["core.logic" :const '{:value a} 'a portable]]))


(defn donors
  "Only visible original native functions and their complete unmodified
   signatures can be used. No native view or implementation is generated."
  [roots visible]
  (let [ids (mapv (fn [[module name args return execution]]
                    (let [id (ids/fn-id module name)
                          expected {:fn-id id :namespace module :name name :args args :return return
                                    :execution execution :effects #{} :taint-propagate? true}
                          actual (get roots id)]
                      (when (and (contains? visible id) (= expected (select-keys actual (keys expected)))
                                 (signatures/local-type-rules? actual))
                        id)))
                  signatures)]
    (when (every? uuid? ids) ids)))
