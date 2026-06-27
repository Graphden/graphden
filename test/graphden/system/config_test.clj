(ns graphden.system.config-test
  "The addon-manifest merge (PLATFORM_PLAN §3.0 prereq #2) — an addon
   config fragment splices into the system config without a core edit."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.system.config :as config]
    [graphden.system.core]
    [integrant.core :as ig]))


(deftest no-addons-is-core-config
  (testing "empty manifest → the core config, unchanged"
    (let [cfg (config/read-config :test [])]
      (is (contains? cfg :exec/context))
      (is (contains? cfg :auth/provider) "core single-token provider present")
      (is (= (ig/ref :auth/provider) (:auth-provider (:exec/context cfg)))
          "core routes the auth seam through :auth/provider")
      (is (not (contains? cfg :auth/test-provider)) "no addon keys"))))


(deftest addon-fragment-merges
  (testing "an addon fragment overrides an indirection key, adds its own, and loads its ns"
    (let [cfg (config/read-config :test ["graphden/system/test_addon.edn"])]
      (testing "the addon's new component is added"
        (is (= {:label "from-addon"} (:auth/test-provider cfg))))
      (testing "the auth seam is redirected to the addon's provider"
        (is (= (ig/ref :auth/test-provider) (:auth-provider (:exec/context cfg)))))
      (testing "deep-merge preserves the rest of :exec/context"
        (is (contains? (:exec/context cfg) :storage)))
      (testing ":graphden/require is stripped from the Integrant config"
        (is (not (contains? cfg :graphden/require))))
      (testing "the require directive loaded the addon namespace (init-key registered)"
        (is (true? @(resolve 'graphden.system.test-addon-fixture/loaded?)))
        (is (= {:provider :test :label "x"}
               (ig/init-key :auth/test-provider {:label "x"}))
            "the addon's :auth/test-provider init-key is now callable")))))


(deftest storage-seam-wiring
  (testing "core routes :db/versioned through the :app/storage seam (default passthrough)"
    (let [cfg (config/read-config :test [])]
      (is (= (ig/ref :db/postgres) (:base (:app/storage cfg)))
          ":app/storage defaults to passing :db/postgres through unchanged")
      (is (= (ig/ref :app/storage) (:base-storage (:db/versioned cfg)))
          (str ":db/versioned wraps the seam, so an addon can slip an org-scoped "
               "decorator BENEATH versioning (vs/unwrap then preserves the tenant filter)")))))


(deftest app-packages-extra-seam-concatenates
  ;; The addon fns-channel seam (§2.1 / §3.0): :extra-package-names appends
  ;; to the core list — the tenancy addon adds its org-admin fns-package via
  ;; the manifest without restating :package-names.
  (testing "extra packages load alongside the core list"
    (let [with-extra (ig/init-key :app/packages
                                  {:package-names ["core"] :extra-package-names ["web"]})
          core-only (ig/init-key :app/packages {:package-names ["core"]})]
      (is (< (count (:packages core-only)) (count (:packages with-extra)))
          "the extra package adds to what core alone loads")))
  (testing "no extra → just the core list (unchanged)"
    (is (pos? (count (:packages (ig/init-key :app/packages {:package-names ["core"]})))))))


(deftest missing-addon-fragment-fails-fast
  (testing "a named-but-absent addon resource throws (no silent skip)"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Addon config not found"
          (config/read-config :test ["graphden/system/does-not-exist.edn"])))))
