(ns graphden.storage.protocol.validation
  "CRUD and credential validation utilities.

   This namespace re-exports validation functions from focused modules:
   - crud-validation: entity and query validation
   - credential-validation: security-focused credential checks
   - field-types: canonical field type definitions

   For new code, consider importing the specific modules directly."
  (:require
    [graphden.storage.protocol.credential-validation :as cred]
    [graphden.storage.protocol.crud-validation :as crud]
    [graphden.storage.protocol.field-types :as types]))


;; === CRUD validation (re-exports) ===

(def validate-required-fields! crud/validate-required-fields!)
(def validate-no-duplicate-ids! crud/validate-no-duplicate-ids!)
(def validate-data-is-map! crud/validate-data-is-map!)
(def validate-where-clause! crud/validate-where-clause!)
(def validate-where-clause-fields! crud/validate-where-clause-fields!)
(def validate-where-clause-types! crud/validate-where-clause-types!)
(def validate-entity-name! crud/validate-entity-name!)


;; === Credential validation (re-exports) ===

(def max-username-length cred/max-username-length)
(def max-password-length cred/max-password-length)
(def max-jdbc-url-length cred/max-jdbc-url-length)
(def validate-credential-length! cred/validate-credential-length!)
(def validate-no-control-chars! cred/validate-no-control-chars!)
(def validate-credentials! cred/validate-credentials!)
(def validate-jdbc-url! cred/validate-jdbc-url!)


;; === Field types (re-exports) ===

(def canonical-field-types types/canonical-field-types)
(def canonical-type? types/canonical-type?)
(def type-category types/type-category)
(def reference-type? types/reference-type?)
(def complex-type? types/complex-type?)
