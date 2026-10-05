// Differential-test bridge: the graph and inputs come from the JVM oracle.
const fs = require('node:fs');
const graph = require('../../resources/packages/app/ui-preview/browser-runtime.js');
const {plan, cases} = JSON.parse(fs.readFileSync(0, 'utf8'));
const runtime = graph.createRuntime(plan);
const results = cases.map(({entry, inputs, operationLimit, measureOperations}) => {
  try {
    const decoded = Object.fromEntries(Object.entries(inputs).map(([name, value]) => [name, graph.decode(value)]));
    const evaluator = operationLimit === undefined ? runtime : graph.createRuntime(plan, {operationLimit});
    const result = evaluator.run(entry, decoded);
    const output = {value: graph.encode(result), sequence: graph.isSequence(result)};
    if (measureOperations) {
      // Test-only black-box measurement, including deferred result realization.
      // Keep production's runtime API free of diagnostic state.
      let lower = 0;
      let upper = operationLimit ?? 10000;
      while (lower < upper) {
        const budget = Math.floor((lower + upper) / 2);
        try {
          graph.encode(graph.createRuntime(plan, {operationLimit: budget}).run(entry, decoded));
          upper = budget;
        } catch (error) {
          if (error.message !== 'Browser graph operation limit exceeded') throw error;
          lower = budget + 1;
        }
      }
      output.operations = upper;
    }
    return output;
  } catch (error) { return {error: error.message, fnId: error.fnId || null}; }
});
process.stdout.write(JSON.stringify(results));
