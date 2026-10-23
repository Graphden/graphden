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
  [path kind sha roster prior]
  (let [ordered (cond-> (order roster prior) (= kind "stages") stage-dependencies)
        state (atom {:schema 1 :kind kind :sha sha :roster ordered
                     :status (zipmap ordered (repeat :pending))})]
    (when-not (valid-receipt? @state kind)
      (throw (ex-info "Invalid current progress receipt" {:kind kind})))
    (report/write-atomic! path @state)
    {:path path :state state :prior prior :roster ordered}))


(defn mark!
  [{:keys [path state]} id status]
  (locking state
    (when-not (and (contains? (:status @state) id) (statuses status))
      (throw (ex-info "Unknown progress check/status" {:id id :status status})))
    (swap! state assoc-in [:status id] status)
    (report/write-atomic! path @state)))


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
    (begin! path kind sha roster prior)))
