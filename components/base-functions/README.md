# base-functions

Core library of base functions for the graphden executor. Provides fundamental operations that can be composed in the function graph.

## Categories

### Arithmetic

| Function | Args | Description |
|----------|------|-------------|
| `:add` | `{:a :b}` | Addition |
| `:sub` | `{:a :b}` | Subtraction |
| `:mul` | `{:a :b}` | Multiplication |
| `:div` | `{:a :b}` | Division (throws on zero) |
| `:mod` | `{:a :b}` | Modulo |
| `:neg` | `{:n}` | Negation |
| `:abs` | `{:n}` | Absolute value |

### Comparison

| Function | Args | Description |
|----------|------|-------------|
| `:eq` | `{:a :b}` | Equality |
| `:neq` | `{:a :b}` | Inequality |
| `:lt` | `{:a :b}` | Less than |
| `:lte` | `{:a :b}` | Less than or equal |
| `:gt` | `{:a :b}` | Greater than |
| `:gte` | `{:a :b}` | Greater than or equal |

### Logic

| Function | Args | Description |
|----------|------|-------------|
| `:and` | `{:a :b}` | Logical AND |
| `:or` | `{:a :b}` | Logical OR |
| `:not` | `{:x}` | Logical NOT |

### Conditionals

| Function | Args | Description |
|----------|------|-------------|
| `:if` | `{:condition :then :else}` | Conditional (lazy branches) |
| `:cond` | `{:pairs :default}` | Multi-way conditional (pairs use `:pred` and `:result`) |

### Strings

| Function | Args | Description |
|----------|------|-------------|
| `:str` | `{:args}` | Concatenate strings |
| `:subs` | `{:s :start :end}` | Substring |
| `:str-len` | `{:s}` | String length |
| `:str-upper` | `{:s}` | Uppercase |
| `:str-lower` | `{:s}` | Lowercase |
| `:str-trim` | `{:s}` | Trim whitespace |
| `:str-split` | `{:s :sep}` | Split string |
| `:str-join` | `{:coll :sep}` | Join strings |

### Collections

| Function | Args | Description |
|----------|------|-------------|
| `:first` | `{:coll}` | First element |
| `:rest` | `{:coll}` | All but first |
| `:cons` | `{:x :coll}` | Prepend element |
| `:conj` | `{:coll :x}` | Append element |
| `:get` | `{:coll :k :default}` | Get by key |
| `:assoc` | `{:m :k :v}` | Associate key-value |
| `:dissoc` | `{:m :k}` | Remove key |
| `:count` | `{:coll}` | Collection size |
| `:empty?` | `{:coll}` | Is empty? |
| `:contains?` | `{:coll :k}` | Contains key? |
| `:keys` | `{:m}` | Map keys |
| `:vals` | `{:m}` | Map values |
| `:merge` | `{:m1 :m2}` | Merge maps |
| `:into` | `{:to :from}` | Pour collection |
| `:range` | `{:start :end :step}` | Number range |
| `:repeat` | `{:n :x}` | Repeat value |
| `:take` | `{:n :coll}` | Take first n |
| `:drop` | `{:n :coll}` | Drop first n |
| `:reverse` | `{:coll}` | Reverse |
| `:sort` | `{:coll}` | Sort |
| `:concat` | `{:coll1 :coll2}` | Concatenate |
| `:flatten` | `{:coll}` | Flatten nested |
| `:distinct` | `{:coll}` | Remove duplicates |

### Higher-Order Functions

| Function | Args | Description |
|----------|------|-------------|
| `:map` | `{:f :coll}` | Apply f to each element |
| `:filter` | `{:pred :coll}` | Keep elements matching pred |
| `:reduce` | `{:f :init :coll}` | Reduce with accumulator |
| `:some` | `{:pred :coll}` | First truthy result |
| `:every?` | `{:pred :coll}` | All match pred? |
| `:find-first` | `{:pred :coll}` | First matching element |
| `:group-by` | `{:key-fn :coll}` | Group by key |
| `:sort-by` | `{:key-fn :coll}` | Sort by key |
| `:apply` | `{:f :args}` | Apply f to args |
| `:identity` | `{:x}` | Return x unchanged |
| `:constantly` | `{:x}` | Return x |

## Usage

```clojure
(require '[graphden.base-functions.interface :as bf])

;; Get all function definitions
(bf/get-all-defs)
```

## Defining Custom Base Functions

See `fn-registry` component for the `defbase` macro and infrastructure.

Quick example:

```clojure
(require '[graphden.fn-registry.interface :refer [defbase]])

;; Simple function - arguments auto-deref'd
(defbase double-it
  {:args {:n :int}
   :return-type :int}
  (* n 2))
```

See `graphden.fn-registry.macros` for full documentation.

## HOF Semantics

Higher-order functions receive callables (not fn-ids). They invoke them with named arguments.

For `:map`:
- Input: `{:f callable :coll [1 2 3]}`
- Calls: `(f {:item 1})`, `(f {:item 2})`, `(f {:item 3})`
- Output: `[result1 result2 result3]`

For `:reduce`:
- Input: `{:f callable :init 0 :coll [1 2 3]}`
- Calls: `(f {:acc 0 :item 1})` → `(f {:acc r1 :item 2})` → `(f {:acc r2 :item 3})`
- Output: final accumulator value

**Note**: The user function's argument names don't need to match `item`/`acc`.
The UI creates bindings when connecting nodes (e.g., `item → n` for a function with arg `n`).

## Error Handling

| Error | Condition |
|-------|-----------|
| `:execution-error/division-by-zero` | Division by zero in `:div` |

## Dependencies

- `executor` - For `register-base-fn!` and `force-value`

## See Also

- [executor README](../executor/README.md) - Execution context and thunks
