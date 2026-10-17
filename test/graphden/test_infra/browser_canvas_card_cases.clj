(ns graphden.test-infra.browser-canvas-card-cases
  "Finite authorized presentation facts for the passive fn-card components.")


(def policy-defaults
  {:surface "groups" :level {:position 0 :depth 0 :mi false :clickable false :duplicate-next false}
   :prior {:group -1 :root true :mi -1 :previous-mi false} :use-site false
   :root false :cell false :clickable false :within false :partial-next false :expanded false})


(def policy-cases
  [(assoc policy-defaults :surface "groups")
   (assoc policy-defaults :level {:position 1 :depth 1 :mi true :clickable true :duplicate-next false}
          :prior {:group 0 :root true :mi -1 :previous-mi false})
   (assoc policy-defaults :level {:position 2 :depth 2 :mi false :clickable false :duplicate-next false}
          :prior {:group 1 :root false :mi 1 :previous-mi true})
   (assoc policy-defaults :level {:position 2 :depth 2 :mi false :clickable false :duplicate-next true}
          :prior {:group 1 :root false :mi -1 :previous-mi false})
   (assoc policy-defaults :use-site true :level {:position 0 :depth 0 :mi false :clickable true :duplicate-next false})
   (assoc policy-defaults :surface "paint" :root true)
   (assoc policy-defaults :surface "paint" :root true :cell true :clickable true :expanded true :partial-next true)
   (assoc policy-defaults :surface "paint" :expanded true :within true)
   (assoc policy-defaults :surface "paint" :within true)])


(def metadata-defaults
  {:surface "return" :declared-return "" :computed-return "" :constraint "" :constraint-title ""
   :lambda-mode "derived" :params [] :effect "" :computed false :declared false :has-contract false})


(def metadata-cases
  (mapv #(merge metadata-defaults %)
        [{:surface "return"}
         {:surface "return" :declared-return ":any" :computed-return ":text"}
         {:surface "return" :computed-return ":positive-int" :constraint "> 0" :constraint-title "positive integer"}
         {:surface "lambda"}
         {:surface "lambda" :lambda-mode "declared"}
         {:surface "lambda" :lambda-mode "declared" :params [{:label "request" :first true} {:label "response" :first false}]}
         {:surface "effect" :effect "env" :computed true}
         {:surface "effect" :effect "db" :computed true :declared true :has-contract true}
         {:surface "effect" :effect "network" :computed true :has-contract true}
         {:surface "effect" :effect "time" :declared true :has-contract true}]))
