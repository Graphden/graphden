// A bounded evaluator for server-resolved ordinary graph calls. Inheritance,
// bindings and rename resolution remain the existing compiler's responsibility.
((host) => {
  

  const keywordPool = new Map();
  class Keyword {
    constructor(namespace, name) { this.namespace = namespace; this.name = name; Object.freeze(this); }
  }
  function keyword(name, namespace = null) {
    const key = JSON.stringify([namespace, name]);
    if (!keywordPool.has(key)) keywordPool.set(key, new Keyword(namespace, name));
    return keywordPool.get(key);
  }
  function integer(value) {
    if (!Number.isSafeInteger(value)) throw new Error('Browser graph requires a safe integer');
    return value;
  }
  function budget(remaining, depth) {
    if (depth > 64 || --remaining.nodes < 0) throw new Error('Browser graph value limit exceeded');
  }
  function checkedString(value) {
    if (typeof value !== 'string' || value.length > 1048576) throw new Error('Invalid graph string');
    return value;
  }
  function mapKey(value) {
    if (!(value instanceof Keyword) && typeof value !== 'string') throw new Error('Unsupported browser map key');
    return value;
  }
  function decodeValue(value, remaining, depth) {
    budget(remaining, depth);
    if (!Array.isArray(value)) throw new Error('Invalid graph value');
    const child = (item) => decodeValue(item, remaining, depth + 1);
    switch (value[0]) {
      case 'nil': if (value.length === 1) return null; break;
      case 'bool': if (value.length === 2 && typeof value[1] === 'boolean') return value[1]; break;
      case 'int': if (value.length === 2) return integer(value[1]); break;
      case 'string': if (value.length === 2) return checkedString(value[1]); break;
      case 'keyword':
        if (value.length === 3) return keyword(checkedString(value[2]), value[1] === null ? null : checkedString(value[1]));
        break;
      case 'vector': if (value.length === 2 && Array.isArray(value[1])) return value[1].map(child); break;
      case 'map':
        if (value.length === 2 && Array.isArray(value[1])) return new Map(value[1].map((pair) => {
          if (!Array.isArray(pair) || pair.length !== 2) throw new Error('Invalid graph map entry');
          return [mapKey(child(pair[0])), child(pair[1])];
        }));
        break;
      default: break;
    }
    throw new Error('Unsupported graph value');
  }
  function decode(value) { return decodeValue(value, {nodes: 50000}, 0); }
  function encodeValue(value, remaining, depth) {
    budget(remaining, depth);
    const child = (item) => encodeValue(item, remaining, depth + 1);
    if (value === null) return ['nil'];
    if (typeof value === 'boolean') return ['bool', value];
    if (typeof value === 'number') return ['int', integer(value)];
    if (typeof value === 'string') return ['string', checkedString(value)];
    if (value instanceof Keyword) return ['keyword', value.namespace, value.name];
    if (Array.isArray(value) || value instanceof LazySequence) {
      const items = []; for (const item of value) items.push(child(item)); return ['vector', items];
    }
    if (value instanceof Map) return ['map', [...value].map(([k, v]) => [child(mapKey(k)), child(v)])];
    throw new Error('Unsupported graph value');
  }
  function encode(value) { return encodeValue(value, {nodes: 50000}, 0); }
  function equal(a, b) {
    if (a === b) return true;
    if ((Array.isArray(a) || a instanceof LazySequence) && (Array.isArray(b) || b instanceof LazySequence)) {
      const left = a[Symbol.iterator](); const right = b[Symbol.iterator]();
      for (;;) { const x = left.next(); const y = right.next(); if (x.done || y.done) return x.done === y.done; if (!equal(x.value, y.value)) return false; }
    }
    if (a instanceof Map && b instanceof Map) return a.size === b.size && [...a].every(([k, v]) => b.has(k) && equal(v, b.get(k)));
    return false;
  }
  function truth(value) { return value !== null && value !== false; }

  class Thunk {
    constructor(compute) { this.compute = compute; this.status = 'pending'; }
    force() {
      if (this.status === 'evaluating') throw new Error('Recursive browser graph value');
      if (this.status === 'failed') throw this.error;
      if (this.status === 'done') return this.value;
      this.status = 'evaluating';
      try { this.value = this.compute(); this.status = 'done'; return this.value; }
      catch (error) { this.error = error; this.status = 'failed'; throw error; }
    }
  }
  const force = (value) => value instanceof Thunk ? value.force() : value;
  class LazySequence {
    constructor(items) { this.items = items; }
    *[Symbol.iterator]() { for (const item of this.items) yield force(item); }
  }
  function sequence(value) {
    if (value === null) return [];
    if (Array.isArray(value) || value instanceof LazySequence) return value;
    throw new Error('Browser graph requires a sequence');
  }
  function lookup(collection, key, fallback) {
    if (typeof collection === 'string') throw new Error('Browser string indexing is unsupported');
    if (collection instanceof Map) return collection.has(key) ? collection.get(key) : fallback;
    if (Array.isArray(collection) && Number.isInteger(key) && key >= 0 && key < collection.length) return collection[key];
    return fallback;
  }
  const operations = {
    const: (arg) => arg('value'),
    list: (arg) => arg('items'),
    get: (arg) => lookup(arg('coll'), arg('key'), arg('default')),
    assoc: (arg) => {
      const source = arg('map');
      const key = arg('key'); const value = arg('value');
      if (Array.isArray(source)) {
        if (!Number.isSafeInteger(key) || key < 0 || key > source.length) throw new Error('Invalid vector index');
        const result = source.slice(); result[key] = value; return result;
      }
      if (source !== null && source !== false && !(source instanceof Map)) throw new Error('Browser assoc requires a map or vector');
      if (!(key instanceof Keyword) && typeof key !== 'string') throw new Error('Unsupported browser map key');
      const result = new Map(source || []); result.set(key, value); return result;
    },
    zipmap: (arg) => {
      const keys = sequence(arg('keys'))[Symbol.iterator]();
      const vals = sequence(arg('vals'))[Symbol.iterator]();
      const result = new Map();
      for (;;) { const k = keys.next(); if (k.done) break; const v = vals.next(); if (v.done) break; if (!(k.value instanceof Keyword) && typeof k.value !== 'string') throw new Error('Unsupported browser map key'); result.set(k.value, v.value); }
      return result;
    },
    if: (arg) => arg(truth(arg('test')) ? 'then' : 'else'),
    'equal?': (arg) => equal(arg('a'), arg('b')),
    add: (arg) => { let total = 0; for (const value of sequence(arg('nums'))) total = integer(total + integer(value)); return total; },
    mod: (arg) => {
      const a = integer(arg('dividend')); const b = integer(arg('divisor'));
      if (!b) throw new Error('Division by zero');
      const remainder = a % b; return remainder === 0 ? 0 : integer(remainder + (Math.sign(remainder) === Math.sign(b) ? 0 : b));
    },
    count: (arg) => {
      const value = arg('coll');
      if (value === null) return 0;
      if (value instanceof Map) return value.size;
      if (Array.isArray(value) || typeof value === 'string') return value.length;
      if (value instanceof LazySequence) { let count = 0; for (const _item of value) count++; return count; }
      throw new Error('Browser count requires a collection');
    },
    hiccup: (arg) => {
      const tag = arg('tag'); const attrs = arg('attrs');
      return [tag instanceof Keyword ? tag : keyword(tag), ...(truth(attrs) ? [attrs] : []), ...sequence(arg('children'))];
    },
  };

  function createRuntime(plan, {operationLimit = 10000} = {}) {
    if (plan.format !== 1 || plan.primitiveAbi !== 1) throw new Error('Unsupported browser graph format');
    const functions = new Map(plan.functions.map((fn) => [fn.id, fn]));
    const primitives = new Map(plan.primitives.map(({id, op}) => {
      if (!Object.hasOwn(operations, op)) throw new Error('Unsupported browser primitive: ' + id);
      return [id, operations[op]];
    }));
    function run(entry, supplied = {}) {
      const id = plan.entries[entry] || entry;
      const input = plan.inputs[id];
      if (!input || !functions.has(id)) throw new Error('Unknown browser graph entry');
      const accepted = new Set(input.accepted);
      for (const name of Object.keys(supplied)) if (!accepted.has(name)) throw new Error('Unknown graph argument: ' + name);
      for (const name of input.required) if (!Object.hasOwn(supplied, name)) throw new Error('Missing graph argument: ' + name);
      const root = {slots: new Map(), names: new Map()};
      for (const [name, value] of Object.entries(supplied)) { encode(value); root.names.set(name, value); }
      for (const {name, slots} of input.destinations) if (Object.hasOwn(supplied, name)) for (const slot of slots) root.slots.set(slot, supplied[name]);
      let remaining = operationLimit;
      const cache = new WeakMap();
      function tick() { if (--remaining < 0) throw new Error('Browser graph operation limit exceeded'); }
      function expr(value, frame) {
        tick();
        switch (value.kind) {
          case 'literal': return decode(value.value);
          case 'read': return force(frame.slots.has(value.slot) ? frame.slots.get(value.slot) : frame.names.has(value.name) ? frame.names.get(value.name) : null);
          case 'seq': return new LazySequence(value.items.map((item) => new Thunk(() => expr(item, frame))));
          case 'call': {
            let calleeFrame = frame;
            if (value.renames.length) {
              calleeFrame = {slots: frame.slots, names: new Map(frame.names)};
              for (const {callee, caller} of value.renames) calleeFrame.names.set(callee, frame.names.has(caller) ? frame.names.get(caller) : null);
            }
            return call(value.fn, calleeFrame);
          }
          default: throw new Error('Unsupported browser expression');
        }
      }
      function call(fnId, incoming) {
        tick();
        let byFunction = cache.get(incoming);
        if (!byFunction) { byFunction = new Map(); cache.set(incoming, byFunction); }
        if (byFunction.has(fnId)) return byFunction.get(fnId).force();
        const result = new Thunk(() => {
          const fn = functions.get(fnId);
          if (!fn) throw new Error('Missing browser graph function: ' + fnId);
          const frame = {slots: incoming.slots, names: new Map(incoming.names)};
          for (const {fromName, toName} of fn.aliases) if (frame.names.has(fromName) && !frame.names.has(toName)) frame.names.set(toName, frame.names.get(fromName));
          const aliasedNames = new Map(frame.names);
          for (const binding of fn.env) frame.names.set(binding.name, new Thunk(() => {
            const scope = {slots: frame.slots, names: new Map(frame.names)};
            if (aliasedNames.has(binding.name)) scope.names.set(binding.name, aliasedNames.get(binding.name));
            else scope.names.delete(binding.name);
            return expr(binding.expr, scope);
          }));
          const args = new Map(fn.args.map((arg) => [arg.name, new Thunk(() => expr(arg.expr, frame))]));
          const operation = primitives.get(fn.primitive);
          if (!operation) throw new Error('Missing browser primitive: ' + fn.primitive);
          try { return operation((name) => args.has(name) ? args.get(name).force() : null); }
          catch (error) { if (!error.fnId) error.fnId = fnId; throw error; }
        });
        byFunction.set(fnId, result);
        return result.force();
      }
      return call(id, root);
    }
    return {run};
  }
  const api = {createRuntime, encode, decode, keyword, Keyword, equal};
  host.GraphdenBrowser = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof window === 'undefined' ? globalThis : window);
