(ns graphden.schema.input.schema
  "Additive structured input storage. Record descriptors reuse fn/slot/fn-slot;
   these bindings are offline-only until the coordinated runtime cutover."
  (:require
    [graphden.schema.protocol.protocol :as ds]))


(def input-type-field
  {:uuid #uuid "a462892f-0521-586e-9ded-aca87410c89e" :type :ref :ref-entity :fn :nullable? true})


(def input-type-version-uuid
  #uuid "3bc666a9-d737-50cd-9ac0-b0d3bbd7b757")


(def binding-fields
  "Path addresses use UUID vectors, encoded by the existing JSONB codec.
   A nil expression-kind carries only metadata and does not occupy a path."
  {:fn-id {:uuid #uuid "e51ea719-0194-5b05-b292-c469a12cc396" :type :ref :ref-entity :fn :indexed? true}
   :scope-path {:uuid #uuid "11996868-8860-5ce1-9a73-e437e47c993c" :type :jsonb}
   :target-path {:uuid #uuid "0fb3bab4-0d9f-5217-997c-e5f525858fc1" :type :jsonb}
   :expression-kind {:uuid #uuid "8350fa2f-8606-5c6c-a3cd-ebd9e4097929" :type :text :nullable? true}
   :value {:uuid #uuid "99c1a52a-29d2-50cc-bd89-9ceeb544848a" :type :jsonb :nullable? true}
   :value-present {:uuid #uuid "f00d0355-477c-5491-b4fa-12660b1e8d81" :type :bool :nullable? true}
   :ref-fn-id {:uuid #uuid "27215177-1243-53ed-9af4-8ce5ad480339" :type :ref :ref-entity :fn :nullable? true}
   :resolver-fn-id {:uuid #uuid "327d380d-36f4-5140-b11b-948089f33e11" :type :ref :ref-entity :fn :nullable? true}
   :projection-scope-path {:uuid #uuid "f4b0ac1d-a9b2-577c-b169-124d6d2253de" :type :jsonb :nullable? true}
   :projection-path {:uuid #uuid "168a90ee-e293-5416-acc3-bd643284c227" :type :jsonb :nullable? true}
   :type-override-fn-id {:uuid #uuid "501481d4-24fb-55cb-9b5e-590607349086" :type :ref :ref-entity :fn :nullable? true}
   :required {:uuid #uuid "aeb68721-bc30-5ae1-808d-7e2638438c03" :type :bool :nullable? true}
   :terminal {:uuid #uuid "0be490a8-5769-5e51-bdc9-c058337281f3" :type :bool :nullable? true}
   :description {:uuid #uuid "dcc96533-82d7-5cdf-a597-d2af8845e7c9" :type :text :nullable? true}
   :org-id {:uuid #uuid "1de647ba-76b5-5cb7-a02c-290d243db6d5" :type :text :nullable? true}})


(def mirror-config
  {:identity-fields #{:org-id}
   :uuids {:fn-id #uuid "72c10d71-7172-5cb2-a4e7-85de89fdd814"
           :scope-path #uuid "75e4fabc-4fb0-5739-94b2-acd80675cbee"
           :target-path #uuid "83e6aef9-93b9-56a6-b1a6-e08683834a6b"
           :expression-kind #uuid "1ad765ea-413b-5db6-b491-6b59af52705e"
           :value #uuid "64cb8ceb-8ff9-5221-830f-1e17ff0cb98d"
           :value-present #uuid "03a8578e-1ac9-54a9-a9e6-d49839405245"
           :ref-fn-id #uuid "0d947a0e-3c41-569d-a06e-71cdc021b877"
           :resolver-fn-id #uuid "3aad7aa2-60ae-5595-9708-2d4b86160684"
           :projection-scope-path #uuid "c7d11dfb-8b3f-53ad-9f57-6731c2b96b4d"
           :projection-path #uuid "1afa99f7-2b5b-5604-af1c-5fe44ba197f5"
           :type-override-fn-id #uuid "1d5b7da9-45e7-5b66-b071-27ae68352f17"
           :required #uuid "54f0a9b3-c936-59e5-9dc5-853068eee4e4"
           :terminal #uuid "359f896d-8d35-5a36-aa82-2540c881cd69"
           :description #uuid "91572671-8c96-59b0-9352-b154a6182277"}
   :tweaks {:fn-id {:indexed? true} :ref-fn-id {:indexed? true}}})


(def version-framework
  {:input-binding-id {:uuid #uuid "adb68e0e-0934-54eb-aa09-592018cf4071" :type :ref :ref-entity :input-binding}
   :branch-id {:uuid #uuid "7fbfe9d6-4838-5621-aed2-b1d179b4f7e6" :type :ref :ref-entity :branch}
   :created-at {:uuid #uuid "9fa76d2f-91da-5caa-ac22-26ac5d15d4a7" :type :timestamptz}
   :deleted-at {:uuid #uuid "0b765d0b-2e6f-51a9-83fd-0f6f3c2c94c9" :type :timestamptz :nullable? true}
   :change-id {:uuid #uuid "cde6fbba-91d7-52e8-ba5a-377e9166589c" :type :uuid :nullable? true}
   :change-revision {:uuid #uuid "c6902bf1-5af8-5d25-bd3a-45b1b9c03a64" :type :int :nullable? true}
   :author-id {:uuid #uuid "332fb2dc-4f8c-5779-8aa1-61b23213b3a9" :type :text :nullable? true}
   :author-label {:uuid #uuid "c31abed5-8093-593c-a135-d9194b743e56" :type :text :nullable? true}})


(def version-entity-uuid
  #uuid "01e26126-d88f-59d3-8cdf-cb9ab7d4673d")


(defn extend-builder
  [builder]
  (ds/add-entity builder :input-binding #uuid "9cedb098-e008-5ae8-85d9-0afc4a3df4e9" binding-fields))
