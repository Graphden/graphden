# Browser Test Tool

Automated browser testing for Graphden editor using Playwright. Captures screenshots and console output.

## Setup

```bash
cd tools/browser-test
npm install
```

## Usage

```bash
# Basic: view a function's graph
node check-editor.js web-server

# Expand root node to level 1
node check-editor.js web-server root:1

# Expand root to level 2
node check-editor.js web-server root:2

# Expand multiple nodes
node check-editor.js web-server root:1 router-fn:1

# View without selecting a function
node check-editor.js
```

## Expand Spec Format

`node-name:level` where:
- `node-name` - the name of the node (use `root` for the root/selected function)
- `level` - how many ancestor levels to expand (1, 2, 3, ...)

## Output

- Screenshot saved to: `/tmp/editor-screenshot.png`
- Console output printed to terminal
- Build timestamp shown for deployment verification
- Errors highlighted in output

## Requirements

- Node.js
- Playwright with Chromium
- Graphden server running on `http://localhost:9002`
