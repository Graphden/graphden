(ns graphden.web.server.interface
  "Web server component interface.

   Re-exports fn-defs from library/fn_defs/web/server.
   Kept for backwards compatibility with existing config references."
  (:require
    [graphden.library.fn-defs.web.server :as server]))


(def fn-defs
  "Fn definitions for creating web server.
   Vector of fn-def maps for use with fn-composition/sync-fns-to-storage!"
  server/fn-defs)


(def startup-fn-name
  "Name of the function to execute at startup (keyword)."
  server/startup-fn-name)
