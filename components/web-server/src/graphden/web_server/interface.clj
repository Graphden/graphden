(ns graphden.web-server.interface
  "Web server component - aggregates http-kit and reitit base functions.

   This component provides all base functions from http-kit-fns and reitit-fns.

   The actual web server configuration (port, routes, handlers) should be
   defined as regular fn entities in storage, enabling graph-based composition."
  (:require
    [graphden.web-server.core :as core]))


(def all-defs
  "All web server base function definitions.
   Includes http-kit and reitit functions."
  core/all-defs)
