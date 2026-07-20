(ns graphden.packages.app.mcp-doc-sync-test
  "The MCP ai-context resource reads
   `resources/packages/app/mcp/ai-context.md` off the classpath; that file
   must stay byte-identical to `docs/AI_CONTEXT.md`, the human-facing copy.
   This guard fails the build the moment they drift."
  (:require
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is]]))


(deftest ai-context-doc-in-sync
  (let [doc (slurp (io/file "docs/AI_CONTEXT.md"))
        served (slurp (io/file "resources/packages/app/mcp/ai-context.md"))]
    (is (= doc served)
        "resources/packages/app/mcp/ai-context.md must equal docs/AI_CONTEXT.md — copy the doc over after editing it")))
