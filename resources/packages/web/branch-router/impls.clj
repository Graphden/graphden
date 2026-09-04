(ns graphden.packages.web.branch-router.impls
  "Base-fn primitives for branch routing. `:branch-routing-wrap` itself
   is a graph fn-def composing these primitives — see fns.edn."
  (:require
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.system.branch-router :as br]))


(defbase current-branch-router
  "Read the currently-installed branch router (nil when no router has
   been installed yet — test paths, or the brief window before
   `:exec/branch-router` fires). Single global-atom deref."
  []
  (br/current-router))


(defbase dispatch-to-branch
  "Delegate a Ring request to the appropriate per-branch handler
   resolved by the supplied router. Single library call."
  [router request]
  ;; Tagged `:network` in fns.edn — the dispatched handler runs HTTP
  ;; processing. Record explicitly so the audit-trail at this layer
  ;; doesn't depend on what the downstream handler happens to record
  ;; (effect-sets are idempotent over union, so double-recording is
  ;; harmless if the handler also tags :network).
  (cr/record-effect! :network)
  ;; A dispatch is a NEW logical execution of the branch's handler, so it
  ;; memoises in its own per-request call cache. The outer scope's cache
  ;; keys project only a ref's DECLARED free args, and the request itself
  ;; reaches most handler fns by name-fallback rather than as a declared
  ;; free — a nested dispatch (the MCP `branch` argument re-dispatching a
  ;; call to another branch) sharing the outer map answered the INNER
  ;; request from the outer request's memo and recursed until the stack
  ;; overflowed.
  (cr/with-fresh-call-cache #(br/dispatch router request)))


(def impls
  {:current-branch-router current-branch-router
   :dispatch-to-branch dispatch-to-branch})
