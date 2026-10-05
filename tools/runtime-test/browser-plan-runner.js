// Differential-test bridge: the graph and inputs come from the JVM oracle.
const fs = require('node:fs');
const graph = require('../../resources/packages/app/ui-preview/browser-runtime.js');
const {plan, cases} = JSON.parse(fs.readFileSync(0, 'utf8'));
const runtime = graph.createRuntime(plan);
const results = cases.map(({entry, inputs}) => {
  try {
    const decoded = Object.fromEntries(Object.entries(inputs).map(([name, value]) => [name, graph.decode(value)]));
    return {value: graph.encode(runtime.run(entry, decoded))};
  } catch (error) { return {error: error.message, fnId: error.fnId || null}; }
});
process.stdout.write(JSON.stringify(results));
