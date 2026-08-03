(ns graphden.tenancy.faas-addon-test
  "The REAL faas.edn addon fragment (resources/graphden/tenancy/
   faas.edn) — Aero merge + seam wiring. Audit-2 finding (2026-07-23):
   this fragment was referenced by NO test; the FaaS stack's config
   merge (`:tenancy/app-router`, org-schema splice, the
   `:exec/context` app-router seam) ran only in a real deployment
   with GRAPHDEN_ADDON_CONFIGS set. faas-app-test exercises the
   RUNTIME app-router by constructing it directly — this covers the
   CONFIG path that hands it to integrant. Pure config reads, no
   `ig/init`, no DB."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.system.config :as config]
    [integrant.core :as ig]))


(deftest faas-fragment-merges-over-addon
  (let [cfg (config/read-config :test ["graphden/tenancy/addon.edn"
                                       "graphden/tenancy/faas.edn"])]
    (testing "app-router key present and wired to the custom-domain host-resolver"
      (is (contains? cfg :tenancy/app-router))
      (is (= (ig/ref :tenancy/storage-host-resolver)
             (:host-resolver (:tenancy/app-router cfg)))))
    (testing "apps-domain resolves (env default) and timeout carries"
      (is (string? (:apps-domain (:tenancy/app-router cfg))))
      (is (pos-int? (:timeout-ms (:tenancy/app-router cfg)))))
    (testing ":exec/context gains the app-router seam"
      (is (= (ig/ref :tenancy/app-router)
             (:app-router (:exec/context cfg)))))
    (testing "org + app-route + domain schemas splice into :db/schema extensions"
      (is (some #{(ig/ref :tenancy/org-schema)}
                (:extensions (:db/schema cfg))))
      (is (some #{(ig/ref :tenancy/app-route-schema)}
                (:extensions (:db/schema cfg))))
      (is (some #{(ig/ref :tenancy/domain-schema)}
                (:extensions (:db/schema cfg)))))
    (testing "addon.edn's own wiring survives the second merge"
      (is (= (ig/ref :org/scoped-storage)
             (:base-storage (:db/versioned cfg)))
          "storage decorator stack unchanged by the faas overlay"))))
