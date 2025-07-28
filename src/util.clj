(ns util)

(defn thrw
  [ex-str meta-data]
  (throw (ex-info ex-str
                  {:type ::exception
                   :meta meta-data})))
