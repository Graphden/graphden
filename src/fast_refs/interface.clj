(ns fast-refs.interface)

(defprotocol Protocol
  (add-args-back-ref [this node-name arg-name])
  (set-base-node-name [this base-node-name])
  (set-full-args [this args parent-full-args])
  (add-child-back-ref [this child-name])
  (delete-child-back-ref [this child-name])
  (rename-arg-back-ref-node [this old-name new-name]))
