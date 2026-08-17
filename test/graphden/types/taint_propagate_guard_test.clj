(ns graphden.types.taint-propagate-guard-test
  "Anti-drift guard for the `:secret` information-flow marker (SECRETS.md § T3).

   A base-fn that passes or transforms caller CONTENT must declare
   `:taint-propagate?` in its impls-map entry, so a `[:secret …]` input flows
   into the result. There is no structural \"content-passing\" predicate the
   checker can key off, so forgetting the flag on a new content-passing base-fn
   silently DECLASSIFIES — with no test to catch it (the exact gap the audit
   flagged).

   This guard pins the reviewed set of taint-propagating base-fns (and the
   full base-fn name set). Adding, removing, or re-flagging a base-fn trips
   it, forcing a conscious decision about the new fn's taint behaviour before
   the change can land. It loads packages as pure data (no DB), so it runs in
   the unit suite."
  (:require
    [clojure.set :as set]
    [clojure.test :refer [deftest is]]
    [graphden.packages.loader :as loader]))


(def ^:private package-set
  "The shipped first-party packages. Keep in sync with the prod package list."
  ["core" "storage" "web" "app-base" "app" "registry" "mcp"])


(def ^:private golden-base-fns
  "Every base-fn name across `package-set`, pinned as a SET so a trip names
   exactly which fn appeared or disappeared (this used to be an integer
   count, which forced arithmetic plus a review-comment ledger here to
   explain each ±1). For each ADDED name ask \"does it pass/transform caller
   content?\" — if yes it needs `:taint-propagate?` and a `golden-tainted`
   entry; a REMOVED name just leaves both sets."
  #{:_apply-create-list-type-body :_apply-create-record-type-body
    :_apply-create-record-type-rollback :_apply-create-secret-body
    :_apply-inline-bind-body :_apply-secret-rollback
    :_apply-update-record-type-body :_apply-update-record-type-rollback
    :_execute-apply :_layout-build-apply :_layout-place-apply
    :_layout-strip-facts-apply :_load-graph-cached :_parse-layout-body
    :_reconcile-services-apply :_rotate-secret-not-owned?
    :_seq-append-load-binding :_seq-move-load-item :_seq-remove-load-item
    :_seq-update-load-item :_slot-effective-type-raw
    :_types-usages-apply :abs :add :all-rich-types :and :api-rich-types
    :assert-some :assoc :assoc-in :atom :auth-active?
    :authenticate-request :blank? :branch-diagnostics-flat
    :brotli-bytes :build-form :byte-count
    :byte-len :cached-api-routes-js :call :call-noargs
    :cancel-execution! :case :cell :chain-has-process-effect?
    :classify-literal :closed-enum-of :coalesce :comp
    :compatible-type-names :concat :cond :conj :cons :const
    :constant-time-equal? :constantly :contains? :count
    :counters-snapshot :create-branch! :create-entity :cron-fire-after
    :cron-parse :current-branch-id :current-branch-router
    :current-org-id :current-slot-value :current-time-ms :declarable-effect-categories
    :decode-row :delete-branch! :delete-entity :deref
    :describe-type-mismatch :detect-conflicts :diff-branches
    :diff-value-against-type :digest-hex :dispatch-to-branch :dissoc
    :distinct :div :do :drop :effective-branch-local? :empty? :env :eq
    :encode-unreadable-kws :equal? :error-boundary-wrap
    :error-http-status :every? :ex-data
    :ex-info :extract-entity-params
    :filter :filter-xf :find-first :first :fix :flatten
    :fn-names-with-tag :fn-stats-raw :fn-type-bound-effects
    :fork-package-fns
    :free-arg-slot-map :free-memory :future :get :get-entity
    :get-execution :get-in :graph-fn-defs :graph-rows :group-by :gt :gte :gzip-bytes
    :h-raw :header-get :heap-committed :heap-max :heap-used :hiccup
    :http-request :http-server :http-stop :if :into
    :invalidate-after-write :invalidate-graph-cache :invoke :is-a?
    :json-to-type :jvm-uptime-ms :keys
    :keyword-to-str :list :list-all-graph-entities :log-warn
    :loop-until-interrupted :lt :lte :map :map-xf
    :materialize-package-fns :max-memory :merge :merge-branch!
    :merge-post-commit! :merge-skipped-branch-local
    :middleware :missing-package-dependencies :mod :mul :name
    :namespace-external-deps :neg :neq
    :nil? :non-blank? :not :notify-after-write :or :os-arch
    :os-load-average :os-name :os-processors :package-upsert-pin
    :package-version-materialized? :pairs->map :parse-constraint
    :parse-edn :parse-int :parse-json :parse-uuid :pg-execute
    :pg-notify :pg-query :pg-tx :pick-encoding :position-in :postwalk
    :pr-str :publish-package-apply :query-entities :query-param
    :query-ref-many-owners :quot :range :re-find? :read-resource-or-nil
    :re-replace :realize-request-body :recent-failures :reduce
    ;; :request-capabilities reads the request-scope SEAM (server-derived
    ;; capability names), never caller content — no taint to propagate.
    :request-capabilities
    :render-hiccup :repeat :reset :resolve-branch-ref :resolve-fn
    :resolve-fn-version-id :resolve-form :resolve-package-version
    :resolve-type-fn-id :response-immutable? :rest :reverse
    :rewrite-refs-to-version
    :rich-type-of-name :ring-create-default-handler :ring-handler
    :ring-route-paths :ring-router :routes->js-bundle
    :rule-owner-of-name :running-entry :secret-leaf :secret-path-args
    :select-keys
    :service-blocking-free-args :set-branch-policy! :sleep :sleep-until-ms
    :slot-type-provenance :slurp :some :some? :sort :sort-by :sql-exec
    ;; :sse-stream returns the adopted-channel response map; the
    ;; render callable's output goes to the WIRE, never into the
    ;; return value — no taint to propagate (same as :http-server).
    :sse-stream
    :sql-query :storage-query-identities :str :str-contains? :str-join
    :str-len :str-lower :str-replace :str-split :str-starts-with?
    :str-to-keyword :str-to-uuid :str-trim :str-upper
    :stringify-response-headers :strip-hidden-impl :strip-secret-paths
    :sub :subs :subtype?
    :swap :sync-fn-defs-branch! :system-property :take :tenancy-active?
    :thread-count
    :throw :throwable-class-name :throwable-message :to-json-string
    :to-set :to-str :total-memory :transduce :try :try-apply-create
    ;; :try-apply-seq-move returns only row ids + positions (never the
    ;; item's content) — no taint to propagate, like seq-update.
    :try-apply-seq-append :try-apply-seq-move :try-apply-seq-update
    :try-apply-tighten
    :try-apply-update :type-check-binding-rej
    :type-name-kinds :update-entity :update-in :update-keys
    :update-vals :url-decode :usage-all-org-stats
    :usage-org-daily :usage-org-fn-stats
    :usage-org-summary :utf8-bytes :vals :value-kinds :vault-delete
    :vault-get :vault-metadata-get :vault-metadata-put :vault-put :vec
    :version-qualified-ns :write-rej :zero? :zipmap})


(def ^:private golden-tainted
  "The base-fns that propagate marker taint (`:secret` &c.) — the reviewed
   SECRETS.md § T3 set. Before changing this, for each ADDED name ask \"does it
   pass/transform caller content? then it needs `:taint-propagate?`\"; for each
   REMOVED name confirm it genuinely no longer handles content."
  #{:abs :add :and :assert-some :assoc :assoc-in :atom :blank? :byte-len :call
    :call-noargs :case :cell :coalesce :concat :cond :conj :cons :const
    :constant-time-equal? :contains? :count :deref :dissoc :distinct :div :do :drop
    :empty? :eq :equal? :ex-data :ex-info :first :flatten :get :get-in :gt
    :gte :hiccup :if :into :invoke :is-a? :keys :keyword-to-str :list :lt :lte
    :merge :mod :mul :name :neg :neq :nil? :non-blank? :not :or :pairs->map
    :parse-int :parse-json :parse-uuid :position-in :postwalk :pr-str
    :quot :range
    :re-find? :re-replace :render-hiccup :repeat :reset :rest :reverse :select-keys :digest-hex
    :slurp :some? :sort :str :str-contains? :str-join :str-len :str-lower
    :swap
    :str-replace :str-split :str-starts-with? :str-to-keyword :str-trim
    :str-upper :sub :subs :take :throw :throwable-class-name :throwable-message
    :to-json-string :to-str :try :update-in :update-keys :update-vals
    :url-decode :vals :vec :zero? :zipmap})


(deftest taint-propagate-set-has-not-drifted
  (let [defs (:base-fn-defs (loader/load-packages package-set))
        names (set (keys defs))
        tainted (set (keep (fn [[nm d]] (when (:taint-propagate? d) nm)) defs))]
    (is (= golden-base-fns names)
        (str "base-fn set changed. Added (review taint behaviour — does it "
             "pass/transform caller content? if yes it needs "
             ":taint-propagate? + a golden-tainted entry): "
             (sort (set/difference names golden-base-fns))
             ". Removed: " (sort (set/difference golden-base-fns names))
             ". Then update golden-base-fns."))
    (is (= golden-tainted tainted)
        (str "taint-propagate set drifted — this can silently (de)classify "
             "secrets. Newly-tainted (verify intended): "
             (sort (set/difference tainted golden-tainted))
             ". No-longer-tainted (a content-passing fn that LOST the flag is a "
             "leak): " (sort (set/difference golden-tainted tainted))
             ". See docs/SECRETS.md § T3, then update the golden set."))))
