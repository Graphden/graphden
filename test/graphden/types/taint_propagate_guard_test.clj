(ns graphden.types.taint-propagate-guard-test
  "Anti-drift guard for the `:secret` information-flow marker (SECRETS.md § T3).

   A base-fn that passes or transforms caller CONTENT must declare
   `:taint-propagate?` in its impls-map entry, so a `[:secret …]` input flows
   into the result. There is no structural \"content-passing\" predicate the
   checker can key off, so forgetting the flag on a new content-passing base-fn
   silently DECLASSIFIES — with no test to catch it (the exact gap the audit
   flagged).

   This guard pins the reviewed set of taint-propagating base-fns (and the
   total base-fn count). Adding, removing, or re-flagging a base-fn trips it,
   forcing a conscious decision about the new fn's taint behaviour before the
   change can land. It loads packages as pure data (no DB), so it runs in the
   unit suite."
  (:require
    [clojure.set :as set]
    [clojure.test :refer [deftest is]]
    [graphden.packages.loader :as loader]))


(def ^:private package-set
  "The shipped first-party packages. Keep in sync with the prod package list."
  ["core" "storage" "web" "app-base" "app" "registry" "mcp"])


(def ^:private golden-total
  "Total base-fn count across `package-set`. A change means a base-fn was added
   or removed — review its taint behaviour, then update this number."
  ;; +1 (283): `:auth-active?` (web.ring-adapter) — reads whether an auth
  ;; provider is wired on the ctx and returns a bool. It handles NO caller
  ;; content (no `[:secret …]` input flows through it), so it does NOT declare
  ;; `:taint-propagate?` and stays OUT of `golden-tainted`.
  283)


(def ^:private golden-tainted
  "The base-fns that propagate marker taint (`:secret` &c.) — the reviewed
   SECRETS.md § T3 set. Before changing this, for each ADDED name ask \"does it
   pass/transform caller content? then it needs `:taint-propagate?`\"; for each
   REMOVED name confirm it genuinely no longer handles content."
  #{:abs :add :and :assert-some :assoc :assoc-in :blank? :byte-len :call
    :call-noargs :case :coalesce :concat :cond :conj :cons :const
    :constant-time-equal? :contains? :count :dissoc :distinct :div :drop
    :empty? :eq :equal? :ex-data :ex-info :first :flatten :get :get-in :gt
    :gte :hiccup :if :into :invoke :is-a? :keys :keyword-to-str :list :lt :lte
    :merge :mod :mul :name :neg :neq :nil? :non-blank? :not :or :pairs->map
    :parse-int :parse-json :parse-uuid :position-in :postwalk :pr-str :range
    :re-find? :render-hiccup :repeat :rest :reverse :select-keys :sha256-hex
    :slurp :some? :sort :str :str-contains? :str-join :str-len :str-lower
    :str-replace :str-split :str-starts-with? :str-to-keyword :str-trim
    :str-upper :sub :subs :take :throw :throwable-class-name :throwable-message
    :to-json-string :to-str :try :update-in :update-keys :update-vals
    :url-decode :vals :vec :zero? :zipmap})


(deftest taint-propagate-set-has-not-drifted
  (let [defs (:base-fn-defs (loader/load-packages package-set))
        tainted (set (keep (fn [[nm d]] (when (:taint-propagate? d) nm)) defs))]
    (is (= golden-total (count defs))
        (str "base-fn count changed (" (count defs) " vs " golden-total
             "). A base-fn was added/removed — review its taint behaviour "
             "(does it pass/transform caller content?) and update golden-total."))
    (is (= golden-tainted tainted)
        (str "taint-propagate set drifted — this can silently (de)classify "
             "secrets. Newly-tainted (verify intended): "
             (sort (set/difference tainted golden-tainted))
             ". No-longer-tainted (a content-passing fn that LOST the flag is a "
             "leak): " (sort (set/difference golden-tainted tainted))
             ". See docs/SECRETS.md § T3, then update the golden set."))))
