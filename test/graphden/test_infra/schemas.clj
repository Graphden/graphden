(ns graphden.test-infra.schemas
  "The ONE place test fixtures build their storage schema.

   Seven test namespaces used to carry byte-identical private
   `full-schema` builders (plus two variants), each hand-chaining the
   same production storage-init combination — so adding a schema layer
   meant finding every copy. A LEAF ns (no test-infra / test-setup
   requires) so both `executor.test-setup` and
   `test-infra.shared-bootstrap` can delegate here without the import
   cycle that originally forced the duplication."
  (:require
    [graphden.schema.executions.schema :as es]
    [graphden.schema.graph.schema :as gds]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.packages.schema :as pkgs]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.schema.queue.schema :as queue-schema]
    [graphden.schema.services.schema :as svcs]
    [graphden.schema.stats.schema :as stats]
    [graphden.schema.traits.schema :as vts]
    [graphden.schema.versioned.schema :as vds]))


(defn full-schema
  "Malli + graph + traits + versioned + executions + services — the
   production storage-init chain. Opts extend it:

     :packages? — add the packages schema (registry pins); what the
                  golden bootstrap and `executor.test-setup` use.
     :stats?    — add the `:usage-stat` rollup table (terminal-
                  transition bumps degrade to warn-logs without it)."
  ([] (full-schema {}))
  ([{:keys [packages? stats?]}]
   (-> (mds/create-builder)
       (gds/extend-builder)
       (vts/extend-builder)
       (vds/extend-builder)
       (es/extend-builder)
       (svcs/extend-builder)
       (queue-schema/extend-builder)
       (cond-> packages? (pkgs/extend-builder))
       (cond-> stats? (stats/extend-builder))
       (ds/build))))
