(ns graphden.web-server.core
  "Web server component - aggregates http-kit and reitit base functions.

   This component provides all base functions from http-kit-fns and reitit-fns.

   The actual web server configuration (port, routes, etc.) should be
   defined as regular fn entities in storage, not as base functions.
   This enables composition through the graph rather than code."
  (:require
    [graphden.http-kit-fns.interface :as http-kit-fns]
    [graphden.reitit-fns.interface :as reitit-fns]))


(def all-defs
  "All web server base function definitions.
   Includes http-kit and reitit functions only."
  (merge http-kit-fns/all-defs
         reitit-fns/all-defs))
