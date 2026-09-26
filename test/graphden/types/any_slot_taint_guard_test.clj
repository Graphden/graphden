(ns graphden.types.any-slot-taint-guard-test
  "Structural guard for the `:any`-slot escape hatch (SECRETS.md § `:any`-slot
   escape hatch / Known limits 3).

   `[:secret T] ⊆ :any` is TRUE, so a secret flowing into a wide value slot
   loses its marker at the type level. The T3 mitigation is per-base-fn:
   every fn whose result derives from what came in through such a slot
   carries `:taint-propagate?`, which lifts the result back into
   `[:secret …]`. `taint-propagate-guard-test` pins the reviewed SET of
   flagged fns; it cannot say whether a base-fn that is NOT flagged should
   be — SECRETS.md used to claim they all were, and `:fix` / `:utf8-bytes`
   / `:pg-tx` / `:diff-value-against-type` were not.

   This guard closes that: every base-fn with a wide value slot — `:any`,
   `:jsonb`, or a list / map / union / record / alias that contains one, or
   a callable slot whose RETURN is one (`:pg-tx`'s body) — must either
   carry `:taint-propagate?`, declare a marked (`[:secret …]`) return, or
   sit in `allow-list` below WITH the reason its return carries none of
   the slot's content. A new wide-slotted base-fn trips it until the author
   decides. Packages load as pure data (no DB), so it runs in the unit
   suite."
  (:require
    [clojure.set :as set]
    [clojure.string :as str]
    [clojure.test :refer [deftest is]]
    [graphden.crud.fn-execution.persist :as persist]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.core :as registry]
    [graphden.packages.loader :as loader]
    [graphden.packages.sync :as sync]
    [graphden.types.check :as check]))


(def ^:private package-set
  "The shipped first-party packages. Keep in sync with the prod package list."
  ["core" "storage" "web" "app-base" "app" "registry" "mcp"])


;; ---------------------------------------------------------------------
;; The allow-list — base-fns with a wide value slot and NO propagation.
;; One entry per fn, each with the reason the RETURN carries none of
;; the slot's content. Shared reasons are named so a reader can tell
;; the classes apart; a new entry needs a reason a reviewer can check
;; against the impl.
;; ---------------------------------------------------------------------

(def ^:private allow-list
  {:_reconcile-services-apply "Ignores request content; returns generated started/stopped instance IDs."
   :debug-catch-disarm! "Ignores request content; returns the disarmed status."
   :debug-catch-status "Ignores request content; returns the current org-scoped trap state."
   :fork-package-fns "Writes package copies to storage; returns the number copied."
   :future "Starts the body asynchronously; returns a cancellation callback, not the body value."
   :http-server "Starts the handler as a network service; returns a stop handle."
   :invalidate-after-write "Invalidates executor caches; returns nil."
   :log "Logs the input as an explicit IO sink; returns nil."
   :log-warn "Logs the input as an explicit IO sink; returns nil."
   :loop-until-interrupted "Runs the effectful body repeatedly; discards its values and returns nil."
   :materialize-package-fns "Materializes package definitions in storage; returns a count."
   :merge-post-commit! "Invalidates caches and restarts affected services; returns nil."
   :notify-after-write "Invalidates the written entity through the notifier; returns nil."
   :pg-notify "Sends the event through the explicit DB effect; returns nil."
   :queue-publish "Stores the payload as an explicit DB effect; returns a newly generated UUID."
   :rewrite-refs-to-version "Updates stored references; returns the number rewritten."})


;; ---------------------------------------------------------------------
;; The scan
;; ---------------------------------------------------------------------

(defn- alias-map
  "`{alias-name body}` for every type-row fn-def across the loaded
   packages — the same desugaring `sync/register-type-aliases!` feeds
   the checker, read as data."
  [fn-defs]
  (into {}
        (keep (fn [fd]
                (when-let [body (and (:name fd) (sync/type-row-alias-body fd))]
                  [(:name fd) body])))
        fn-defs))


(defn- wide?
  "Does `t` let an ARBITRARY value in — `:any` / `:jsonb` at the top, or
   inside a list / map / tuple / union / record / marker, through an
   alias, or as the RETURN of a callable slot (`[:fn args ret]` — what
   the base-fn receives back from the callable it is handed)? A
   refinement is judged by its base; a callable's ARGS are what the
   base-fn passes down, not content it receives, so they are skipped."
  [aliases t seen]
  (cond
    (keyword? t)
    (or (contains? #{:any :jsonb} t)
        (and (contains? aliases t)
             (not (contains? seen t))
             (wide? aliases (get aliases t) (conj seen t))))

    (map? t)
    (boolean (some #(wide? aliases % seen) (vals t)))

    (vector? t)
    (case (first t)
      :fn (wide? aliases (nth t 2 nil) seen)
      :refine (wide? aliases (second t) seen)
      (boolean (some #(wide? aliases % seen) (rest t))))

    :else false))


(defn- marked-return?
  "The declared return carries a `[:secret …]` marker somewhere — the
   redactor hides it regardless of what flowed in."
  [t]
  (boolean (some #(and (vector? %) (= :secret (first %)))
                 (tree-seq coll? seq t))))


(defn- wide-slots
  [aliases base-def]
  (into []
        (keep (fn [[slot-name spec]]
                (when (wide? aliases (:type spec) #{}) slot-name)))
        (:args base-def)))


(deftest every-wide-slotted-base-fn-propagates-or-is-allow-listed
  (let [{:keys [base-fn-defs fn-defs]} (loader/load-packages package-set)
        aliases (alias-map fn-defs)
        wide (into {}
                   (keep (fn [[nm d]]
                           (let [slots (wide-slots aliases d)]
                             (when (and (seq slots)
                                        (not (:taint-propagate? d))
                                        (not (marked-return? (:return-type d))))
                               [nm {:slots slots :return (:return-type d)}]))))
                   base-fn-defs)
        unclassified (set/difference (set (keys wide)) (set (keys allow-list)))
        stale (set/difference (set (keys allow-list)) (set (keys wide)))]
    (is (empty? unclassified)
        (str "Base-fns with a wide value slot (`:any` / `:jsonb`, or a type "
             "containing one) that neither propagate taint nor sit in the "
             "allow-list. For each: does the RETURN derive from what comes in "
             "through the slot? Then add `:taint-propagate? true` to its impls-map "
             "entry (and to taint-propagate-guard-test's golden set); else add it "
             "to `allow-list` with the reason. Unclassified:\n"
             (str/join "\n" (map (fn [nm] (str "  " nm " " (pr-str (get wide nm))))
                                 (sort unclassified)))))
    (is (empty? stale)
        (str "allow-list entries that no longer name a wide-slotted, unflagged "
             "base-fn (renamed, narrowed, flagged, or removed) — drop them: "
             (sort stale)))
    (is (every? (fn [[_ reason]] (and (string? reason) (not (str/blank? reason))))
                allow-list)
        "every allow-list entry carries a non-blank reason")))


(deftest recursion-preserves-the-secret-marker-and-hides-the-result
  (exec/with-isolated-rich-types
    (fn []
      (let [{:keys [base-fn-defs]} (loader/load-packages ["core"])
            id (random-uuid)]
        (registry/record-rich-types! :fix (get base-fn-defs :fix))
        (let [ret (check/rule-return :fix {:input {:type [:secret :text]}} :any)]
          (is (= [:secret :any] ret))
          (registry/record-rich-types-raw! id :secret-recursion-result {:return ret})
          (is (= {:status :succeeded :result nil :tainted? true}
                 (persist/redact-outcome id {:status :succeeded :result "private-value"}))))))))
