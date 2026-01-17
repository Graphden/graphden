(ns graphden.web-server.interface
  "Web server component interface.

   This component defines fn entities (NOT base-fns) for a web server.
   It composes base-fns from other components:
   - http-kit-fns: http-server
   - reitit-fns: reitit-ring-handler
   - base-functions: constantly

   ## Key Principle

   Base-fns are wrappers around pure functions (Clojure core or libraries).
   This component has NO base-fns - only fn-defs that compose existing ones.

   ## Usage

   ```clojure
   (require '[graphden.web-server.interface :as web-server])
   (require '[graphden.fn-defs.interface :as fn-defs])

   ;; Create fn entities (no base-fns to register!)
   (fn-defs/sync-fns-to-storage! storage (web-server/fn-defs 8080))

   ;; Execute startup function
   (exec/execute-by-name ctx (name web-server/startup-fn-name) nil)
   ```"
  (:require
    [graphden.web-server.core :as core]))


(def fn-defs
  "Fn definitions for creating web server.
   Vector of fn-def maps for use with fn-defs/sync-fns-to-storage!"
  core/fn-defs)


(def startup-fn-name
  "Name of the function to execute at startup (keyword)."
  core/startup-fn-name)
