(ns ci-resume
  "Progress is a scheduling hint, never evidence that a current check passed."
  (:require
    [babashka.fs :as fs]
    [babashka.process :as p]
    [ci-report :as report]
    [clojure.string :as str]))


(def statuses #{:pending :running :passed :failed})


(defn valid-receipt?
  [receipt kind]
  (let [{:keys [schema roster status sha]} receipt]
    (and (= 1 schema) (= kind (:kind receipt))
         (string? sha) (re-matches #"[0-9a-f]{40}" sha)
         (vector? roster) (every? #(and (string? %) (not (str/blank? %))) roster)
         (= (count roster) (count (set roster)))
         (map? status) (= (set roster) (set (keys status)))
         (every? statuses (vals status)))))


(defn read-hint
  [path kind]
  (when (seq path)
    (try
      (let [receipt (report/read-report path)]
        (when (valid-receipt? receipt kind) receipt))
      (catch Exception _ nil))))


(defn exact-credit?
  "Only an interrupted invocation of the identical candidate and roster can
   supply pass credit. Inherited hints and completed red runs supply order only."
  [prior sha roster]
  (and prior (not (:inherited prior)) (= sha (:sha prior))
       (= (set roster) (set (:roster prior)))
       (= (count roster) (count (:roster prior)))))


(defn credited
  [prior sha roster]
  (if (exact-credit? prior sha roster)
    (set (for [id roster :when (= :passed (get-in prior [:status id]))] id))
    #{}))


(defn interrupted-gate?
  "The former train must have died without a verdict on this exact assembled
   commit and base. A completed red gate never lends pass credit."
  [gate expected sha base]
  (and (= 1 (:schema gate)) (false? (:complete gate))
       (= sha (:sha gate) (:sha expected))
       (= base (get-in expected [:scope :since]))
       (nil? (:exit gate))))


(defn carry-hints!
  "Carry detailed scheduling hints across a run that never reaches that kind.
   The caller has validated the source member/base lineage. Keep the original
   SHA and mark the copy inherited; it is neither current progress nor proof."
  [output-prefix source-prefix]
  (doseq [kind ["checks" "browser"]
          :let [path (str output-prefix "." kind ".edn")
                prior (read-hint (when (seq source-prefix) (str source-prefix "." kind ".edn")) kind)]]
    (if prior
      (report/write-atomic! path (assoc prior :inherited true))
      (fs/delete-if-exists path))))


(defn order
  "Failed/in-flight, unrun/new, passed. Always a permutation of the CURRENT
   roster, including added checks and excluding deleted/out-of-scope checks."
  [roster prior]
  (when-not (= (count roster) (count (set roster)))
    (throw (ex-info "Duplicate current check IDs" {:roster roster})))
  (if-not prior
    (vec roster)
    (let [rank {:failed 0 :running 0 :pending 1 :passed 2}
          position (zipmap (:roster prior) (range))
          current-position (zipmap roster (range))]
      (vec (sort-by (fn [id]
                      [(get rank (get-in prior [:status id] :pending))
                       (get position id (+ (count position) (get current-position id)))])
                    roster)))))


(defn schedule-batches
  "Reorder only within canonical waves/resource batches. Quick pre-checks always
   precede measuring tests, and post-test readers follow every selected test."
  [batches prior]
  (let [original (vec (mapcat batches [:affected :pre :priority :test :post-test]))
        ids (mapv :name (mapcat identity original))
        position (zipmap (order ids prior) (range))
        category #(case (get-in prior [:status (:name %)])
                    (:failed :running) 0 :passed 2 1)]
    (vec (mapcat (fn [wave]
                   (let [pieces (mapcat #(map val (sort-by key (group-by category %))) (get batches wave))]
                     (sort-by #(apply min (map (comp position :name) %)) pieces)))
                 [:affected :pre :priority :test :post-test]))))


(defn stage-dependencies
  "Keep CI quick checks first and insert build before its first image consumer."
  [ordered]
  (let [ordered (if (some #{"ci"} ordered)
                  (into ["ci"] (remove #{"ci"}) ordered)
                  ordered)]
    (if (some #{"build"} ordered)
      (vec (distinct (mapcat #(if (#{"e2e" "visual" "integration" "fleet"} %)
                                ["build" %] [%]) ordered)))
      ordered)))


(defn begin!
  ([path kind sha roster prior] (begin! path kind sha roster prior false))
  ([path kind sha roster prior credit?]
   (let [ordered (cond-> (order roster prior) (= kind "stages") stage-dependencies)
         passed (when credit?
                  (let [candidates (credited prior sha roster)]
                    (if (= kind "checks")
                      (set (filter #(let [p (get-in prior [:proof %])]
                                      (and (zero? (get p :exit -1))
                                           (number? (:duration-ms p))
                                           (not (#{"tests-perf" "perf"} %))))
                                   candidates))
                      candidates)))
         state (atom {:schema 1 :kind kind :sha sha :roster ordered
                      :status (zipmap ordered (map #(if (contains? passed %) :passed :pending) ordered))
                      :proof (select-keys (:proof prior) passed)})]
     (when-not (valid-receipt? @state kind)
       (throw (ex-info "Invalid current progress receipt" {:kind kind})))
     (report/write-atomic! path @state)
     {:path path :state state :prior prior :roster ordered :credited passed})))


(defn mark!
  [{:keys [path state]} id status]
  (locking state
    (when-not (and (contains? (:status @state) id) (statuses status))
      (throw (ex-info "Unknown progress check/status" {:id id :status status})))
    (swap! state assoc-in [:status id] status)
    (report/write-atomic! path @state)))


(defn mark-proof!
  [run id proof]
  (locking (:state run)
    (when-not (and (= :passed (get-in @(:state run) [:status id]))
                   (map? proof) (zero? (:exit proof))
                   (number? (:duration-ms proof)))
      (throw (ex-info "Cannot credit a check without a passing proof" {:id id})))
    (swap! (:state run) assoc-in [:proof id] proof)
    (report/write-atomic! (:path run) @(:state run))))


(defn record-order!
  "Record dependency/resource-adjusted dispatch order before starting work."
  [{:keys [path state]} roster]
  (locking state
    (when-not (= (frequencies (:roster @state)) (frequencies roster))
      (throw (ex-info "Dispatch order changed the mandatory roster" {})))
    (swap! state assoc :roster (vec roster))
    (report/write-atomic! path @state)))


(defn git-output
  [& args]
  (str/trim (:out (apply p/shell {:out :string} "git" args))))


(defn invocation!
  "wtq supplies a lineage-validated source prefix. Standalone runners reuse
   only receipts whose SHA is an ancestor of this checkout, in this worktree."
  [kind roster]
  (let [sha (git-output "rev-parse" "HEAD")
        output-prefix (System/getenv "GRAPHDEN_RESUME_OUT")
        source-prefix (System/getenv "GRAPHDEN_RESUME_FROM")
        path (if (seq output-prefix) (str output-prefix "." kind ".edn")
                 (git-output "rev-parse" "--git-path" (str "graphden-resume/" kind ".edn")))
        prior (read-hint (if (seq output-prefix)
                           (when (seq source-prefix) (str source-prefix "." kind ".edn"))
                           path) kind)
        prior (when (and prior
                         (or (seq output-prefix)
                             (zero? (:exit (p/shell {:out :string :err :string :continue true}
                                                    "git" "merge-base" "--is-ancestor" (:sha prior) sha)))))
                prior)]
    (begin! path kind sha roster prior
            (= "1" (System/getenv "GRAPHDEN_RESUME_CREDIT")))))
