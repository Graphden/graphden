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
    :_tests-run-apply :_tests-status-apply
    :_types-usages-apply :abs :add :all-rich-types :and :api-rich-types
    :assert :assert-eq :assert-some :assoc :assoc-in :atom :auth-active?
    :authenticate-request :blank? :branch-diagnostics-flat
    :brotli-bytes :build-form
    ;; Cached read of the fixed build artifact — no caller content.
    :build-hashes-raw :byte-count
    :byte-len :cached-api-routes-js :call :call-noargs
    :cancel-execution! :case :cell :chain-has-process-effect?
    :classify-literal :closed-enum-of :coalesce :comp
    :compatible-type-names :concat :cond :conj :cons :const
    :constant-time-equal? :constantly :contains? :count
    ;; The tutorial funnel — both bump a counter keyed by a VALIDATED
    ;; lesson id / step index and answer the counter's own name. No caller
    ;; content passes through, so no `:taint-propagate?`.
    :count-tour-event! :count-tour-step!
    :counters-snapshot :create-branch! :create-entity :cron-fire-after
    :cron-parse :current-branch-id :current-branch-router
    ;; /api/debug/catch — trap admin (runtime state, no caller content).
    :debug-catch-arm! :debug-catch-disarm! :debug-catch-status
    ;; Boot-snapshot read of a declared PUBLIC deployment setting — no
    ;; caller content passes through (like `:env`).
    :deploy-config
    :current-org-id :current-slot-value :current-time-ms :declarable-effect-categories
    :decode-row :delete-branch! :delete-entity :deref
    :describe-type-mismatch :detect-conflicts :diff-branches
    ;; diff-branches-view returns previews of user-authored binding
    ;; values — same read-projection class as :diff-branches.
    :diff-affected :diff-branches-view
    :diff-value-against-type :digest-hex :dispatch-to-branch :dissoc
    :distinct :div :do :drop :effective-branch-local? :empty? :env :eq
    :encode-unreadable-kws :equal? :error-boundary-wrap
    :error-http-status :every? :ex-data :execute-trace-rows
    :ex-info :extract-entity-params
    ;; :failure-ack / :failure-ack-all mutate acknowledged-at on audit
    ;; rows and return bool / count — no caller content in the return.
    :failure-ack :failure-ack-all
    :filter :filter-xf :find-first :fn-signature :fn-type? :first :fix :flatten
    :fn-names-with-tag :fn-return-type :form-decode :fn-stats-raw :fn-type-bound-effects
    :fork-package-fns
    :free-arg-slot-map :free-memory :future :get :get-entity
    :get-execution :get-in :graph-fn-defs :graph-rows :group-by :gt :gte :gzip-bytes
    :h-raw :header-get :heap-committed :heap-max :heap-used :hiccup
    :http-request :http-server :http-stop :hub-fetch-bundle :hub-push-bundle! :if :into
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
    :import-bundle! :mirror-remote-package! :parse-edn :parse-graph-edn :parse-int :parse-json :parse-uuid :pg-execute
    :pg-notify :pg-query :pg-tx :pick-encoding
    ;; :pkg-delete-guard-reason reads server rows (fn name via the
    ;; owned registry) — the reason string carries no caller content.
    :pkg-delete-guard-reason
    :position-in :postwalk
    :pr-str :publish-package-apply :query-entities :query-param
    :query-ref-many-owners :quot :range :re-find? :read-resource-bytes :read-resource-or-nil
    :re-replace :realize-request-body :recent-failures :reduce
    ;; :request-capabilities reads the request-scope SEAM (server-derived
    ;; capability names), never caller content — no taint to propagate.
    :request-capabilities
    :render-hiccup :render-value-repr :repeat :reset :resolve-branch-ref :resolve-fn
    :resolve-fn-version-id :resolve-form :resolve-package-version
    ;; :resolve-remote-version returns a server-picked version string off
    ;; the remote list — no caller content flows into the return.
    :resolve-remote-version
    :resolve-type-fn-id :response-immutable? :rest :reverse
    :rewrite-refs-to-version
    :rich-type-of-name :ring-create-default-handler :ring-handler
    :ring-route-paths :ring-router :routes->js-bundle
    :rule-owner-of-name :running-entry :secret-leaf :secret-path-args
    :select-keys
    :service-blocking-free-args :set-branch-policy!
    :set-branch-require-merge! :set-review-state!
    ;; Cached, allow-listed read of a shipped frontend asset — no caller content.
    :shipped-asset
    :set-branch-review-policy! :approve-proposal! :dismiss-my-approval!
    :proposal-approval-status :add-branch-comment! :list-branch-comments
    :delete-branch-comment! :sleep :sleep-until-ms
    :slot-type-provenance :slurp :some :some? :sort :sort-by :sql-exec
    ;; :sse-stream returns the adopted-channel response map; the
    ;; render callable's output goes to the WIRE, never into the
    ;; return value — no taint to propagate (same as :http-server).
    :sse-stream
    :sql-query :storage-query-identities :str :str-contains? :str-join
    :str-clip :str-len :str-lower :str-replace :str-split :str-starts-with?
    :str-to-keyword :str-to-uuid :str-trim :str-upper
    :stringify-response-headers :strip-hidden-impl :strip-secret-paths
    :sub :subs :subtype? :svg-polyline-points :tabulate-records
    :swap :sync-fn-defs-branch! :system-property :take :tenancy-active?
    :platform-owned-def-names
    :thread-count
    :throw :throwable-class-name :throwable-message :to-json-pretty :to-json-string
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
    :version-qualified-ns :withdraw-package-apply :write-rej :zero? :zipmap})


(def ^:private golden-tainted
  "The base-fns that propagate marker taint (`:secret` &c.) — the reviewed
   SECRETS.md § T3 set. Before changing this, for each ADDED name ask \"does it
   pass/transform caller content? then it needs `:taint-propagate?`\"; for each
   REMOVED name confirm it genuinely no longer handles content."
  #{:abs :add :and :assert :assert-eq :assert-some :assoc :assoc-in :atom :blank? :byte-len :call
    :call-noargs :case :cell :coalesce :comp :concat :cond :conj :cons :const
    :constant-time-equal? :constantly :contains? :count :deref :dissoc :distinct :div :do :drop
    :empty? :eq :equal? :every? :ex-data :ex-info :filter :filter-xf :find-first
    :first :flatten :fn-signature :fn-type? :form-decode :get :get-in :gt
    :gte :group-by :hiccup :hub-fetch-bundle :hub-push-bundle! :if :into :invoke :is-a? :keys
    :keyword-to-str :list :lt :lte
    :map :map-xf :merge :mod :mul :name :neg :neq :nil? :non-blank? :not :or :pairs->map
    :import-bundle! :list-branch-comments :parse-edn :parse-graph-edn
    :parse-int :parse-json :parse-uuid :platform-owned-def-names
    :position-in :postwalk :pr-str
    :quot :range
    :re-find? :re-replace :reduce :render-hiccup :render-value-repr :repeat :reset :rest :reverse :select-keys :digest-hex
    :slurp :some :some? :sort :sort-by :str :str-clip :str-contains? :str-join :str-len :str-lower
    :swap
    :str-replace :str-split :str-starts-with? :str-to-keyword :str-trim
    :str-upper :sub :subs :tabulate-records :take :throw :throwable-class-name :throwable-message
    :to-json-pretty :to-json-string :to-str :transduce :try :update-in :update-keys :update-vals
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
