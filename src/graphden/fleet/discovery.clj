(ns graphden.fleet.discovery
  "Live executor-set discovery for the placement controller (docs/FLEET_RFC.md
   §6.2 / §7). Two sources, in order:

   - `GRAPHDEN_FLEET_EXECUTORS` — an explicit comma-separated list. Static, and
     what tests / non-k8s deployments use.
   - `GRAPHDEN_FLEET_DNS` — the SRV record of the fleet's headless Service. In
     k8s a StatefulSet's headless Service publishes one SRV target per ready pod
     (`graphden-0.graphden-headless.<ns>.svc.cluster.local`), so resolving it
     yields the CURRENT membership — it grows and shrinks as HPA scales the
     StatefulSet, which is exactly what §6.2 dynamic membership needs. Each SRV
     target IS an executor-id (the same pod-FQDN a pod sets as its own
     `GRAPHDEN_EXECUTOR_ID`), so the forward-hop / cell-command clients dial it
     directly.

   Resolution failures degrade to an empty set (the controller then plans
   nothing that tick) rather than crashing the loop."
  (:require
    [clojure.string :as str])
  (:import
    (java.util
      Hashtable)
    (javax.naming.directory
      Attribute
      Attributes
      InitialDirContext)))


(defn parse-executor-list
  "Comma-separated executor ids → a trimmed, blank-free vec, or nil when the
   string is nil/blank/all-blank (so the caller falls through to DNS)."
  [s]
  (when (seq s)
    (let [xs (into [] (comp (map str/trim) (remove str/blank?)) (str/split s #","))]
      (when (seq xs) xs))))


(defn parse-srv-target
  "The target host of one SRV record string (`\"<pri> <weight> <port> <host>.\"`)
   with the trailing dot stripped, or nil if unparseable."
  [record]
  (when (string? record)
    (let [target (last (str/split (str/trim record) #"\s+"))]
      (when (seq target)
        (not-empty (str/replace target #"\.$" ""))))))


(defn resolve-srv-targets
  "Resolve `srv-name`'s SRV record → the sorted, de-duplicated vec of target
   hostnames (executor-ids). Empty on any DNS/JNDI error — a transient
   resolution failure must not take down the control loop."
  [srv-name]
  (try
    (let [env (doto (Hashtable.)
                (Hashtable/.put "java.naming.factory.initial" "com.sun.jndi.dns.DnsContextFactory")
                (Hashtable/.put "java.naming.provider.url" "dns:"))
          ctx (InitialDirContext. env)
          attr (some-> (InitialDirContext/.getAttributes ctx ^String srv-name (into-array String ["SRV"]))
                       (Attributes/.get "SRV"))]
      (if attr
        (->> (enumeration-seq (Attribute/.getAll attr))
             (keep #(parse-srv-target (str %)))
             distinct
             sort
             vec)
        []))
    (catch Exception _ [])))


(defn fleet-executors
  "The live executor set: the explicit `GRAPHDEN_FLEET_EXECUTORS` list if set,
   else the SRV members of `GRAPHDEN_FLEET_DNS`, else empty (⇒ the controller
   plans nothing — there is nowhere to place)."
  []
  (or (parse-executor-list (System/getenv "GRAPHDEN_FLEET_EXECUTORS"))
      (some-> (System/getenv "GRAPHDEN_FLEET_DNS") resolve-srv-targets)
      []))
