---
name: graphden-code-quality
description: Quality of Clojure code in `src/` and `test/` — DRY, dead code, decomposition of large functions/files, N+1, security, nil-safety, test speed, sensible coverage. Apply on ANY touch of Clojure code (write new code cleanly right away so you don't have to redo it later) AND as an explicit check of existing code ("go through the project", "clean up `src/`", "go through it again — what else can be improved"). Triggers — phrases like "refactoring", "clean up", "DRY", "large file", "large function", "dead code", "duplicates", "optimize", "N+1", "speed up tests", "flake", "test flake", "improve quality", "security", "alpha release", "before release". SKIP for: pure frontend questions (.js/.css/.html — separate skills), questions about `fns.edn`/`impls.clj` (→ `graphden-packages-quality`), pure REPL-debug hypotheses (→ `graphden-repl`), business-feature implementation.
---

# graphden-code-quality — clean Clojure src/ and test/

The goal of this skill: **open the code in front of an outside developer and not
wince**. This is not "run linters until green" (`bb check` we run anyway),
but catching semantic problems the linter doesn't
see: duplicates, N+1, wall-of-text functions, flakes, dead code,
asymmetric guard preambles.

**Apply both when writing new code and as an explicit check of
existing code.** If new code passes this skill on the first pass —
you won't have to come back. If you're asked to "go through the project" — that's an
explicit rerun over the same list.

## 0. Sanity checks before starting — where we are now

Before editing existing code, take a snapshot:

```bash
bb check                   # clj-kondo + splint + cljstyle, should be 0 warnings
bb test                    # should be green; remember the slowest (kaocha profiling)
clojure -M:dev tools/reachability_audit.clj  # reachability tree of fn-defs
```

If `bb check` already complains about something — close it BEFORE refactoring. Other people's
errors will later mask yours.

## 1. Decomposition of large functions — threshold 100 lines

**A function ≥ 100 lines — mandatory check for a split.** Not always
necessary to cut (see §1.5 below), but you must JUSTIFY
leaving it.

### 1.1 How to find candidates

```bash
python3 << 'EOF'
import re, os
for root, _, files in os.walk('/root/projects/graphden/src'):
    for f in files:
        if not f.endswith('.clj'): continue
        path = os.path.join(root, f)
        with open(path) as fh: content = fh.read()
        matches = list(re.finditer(r'^\((defn-?|defmulti|defmethod|defmacro)\s+(\S+)', content, re.M))
        for i, m in enumerate(matches):
            start = m.start()
            end = matches[i+1].start() if i+1 < len(matches) else len(content)
            n = content[start:end].count('\n')
            if n >= 100:
                line = content[:start].count('\n') + 1
                print(f"  {n:4d} {m.group(2):35s} {path.split('graphden/')[1]}:{line}")
EOF
```

The regexp skips `defrecord` / `defprotocol` — that's correct, they
often contain many protocol-method declarations, which is NOT a refactor target.

### 1.2 How to cut — the name-the-phases rule

Suitable seams are the **nameable phases** of the function's work:

| Seam | Signs |
|---|---|
| `parse`/`classify` | Parsing raw input into a structured form |
| `validate` | Checks that return either a go-ahead or a rejection marker |
| `compute`/`derive` | Pure transformation without I/O |
| `apply`/`write` | Side effects (DB write, NOTIFY, log) |
| `finalize`/`respond` | Forming the return value for the caller |
| `throw-*!` | Canonical ex-info — extract if ≥2 callers |

**Example (from this session)** — `crud/entities/apply-create-core` was 106
lines = nested let + cond. Split into:

- `humanise-create-exception` (16 lines) — message format
- `try-create-or-error` (24 lines) — capability-gate + create-entity wrap
- `forward-rename-slot!` (10 lines) — Phase 6c side-effect
- `post-create-type-check-fn-id` (10 lines) — fn-id resolution
- `verify-post-create-or-rollback!` (20 lines) — type-check + rollback
- `apply-create-core` (16 lines) — orchestrator

Each helper has a name, a docstring (1-3 lines), reads independently.
`apply-create-core` itself now tells a story: "create → maybe
rename-slot → maybe rollback".

### 1.3 When a helper ends up with 6+ parameters

If an extracted helper has to be passed > 5 arguments, the seams
are in the wrong place. Options:

- Combine related parameters into a map (`{:storage :ctx :row}` → one
  `ctx`-map).
- Find a different seam — maybe the helper includes another phase
  that covers half the parameters.

### 1.4 When NOT to cut — a linear pipeline via `let`

If a function is a linear chain of transformations (`indexes →
sorted → reduced`), and each next value is genuinely needed by
the next, splitting into helpers makes it WORSE. Example:
`executor/compile/lookups/build-lookups` (104 lines) — this is a
linear-let constructing 8 index maps from raw rows. A split would create
8 helpers with 3-4 parameters each, readability would drop.

Solution: leave it as one let, but make sure each binding has a
MEANINGFUL name.

### 1.5 When a large function may be left as is

- Linear pipeline via `let` (§1.4)
- `defrecord` / `defprotocol` with protocol-method declarations
- Data-heavy definitions (schema declarations, big enum value maps) —
  `extend-builder`, `value-kinds`, etc.
- Algorithm with an invariant (`letfn` with mutual recursion, shared
  cycle-set) — an un-cuttable algorithm body

In the docstring or in a comment before the function, explicitly say why we DON'T
cut.

## 2. Decomposition of large files — threshold 1000 LOC

**A file > 1000 lines** — a reason to think. Not every such file
must be cut, but check:

1. Are there > 1 topic in the file? If so — cut by topic.
2. Is there a semantically-distinct group of functions that can be
   given a name? If so — move it into a separate namespace.

**Example of a good split** (historical): `crud/entities.clj` → `crud/
entities.clj` + `crud/request.clj` + `crud/validation.clj` + …

**Don't cut the file if:**

- All functions are one logical responsibility (`types/check.clj` —
  type-checker, 2858 LOC, but it's ONE algorithm).
- Decomposition would leave many cross-references — a bad knife.

## 3. DRY — finding duplicates

### 3.1 Identical `(throw (ex-info …))` blocks

```bash
grep -rEn 'ex-info\s+\(str\s+"' src --include='*.clj' | head -20
```

If the SAME ex-info `:type` is thrown from ≥2 places with the same
data-shape — lift it into a helper `(defn- throw-<X>! [arg] …)`.

Example (from this session): `executor/compile_runtime.clj` threw
`:execution-error/fn-not-found` from `execute` and `make-single-arg-
callable`. The helper `throw-fn-not-found!` removed the duplicate and simplified
the let-binding: `(or (get reg fn-id) (throw-fn-not-found! fn-id))`.

### 3.2 An inline let-bound helper repeated between two cond branches

If two `cond`/`if` branches do `(let [eff (compute-eff) outcome
(->> base (stamp-touched-secret …) (redact-outcome …))] (write …)
(unregister …) outcome)` — that's a `finalize-X` helper.

Example: `crud/fn-execution/apply-execute` had two nearly-identical
finalizers for `:succeeded` and `:failed`. Lifting into
`finalize-inline-outcome` simplified the cond.

### 3.3 The same sequence of let-bindings within one function

If within one function the SAME triple of `let`-bindings (or
more) is computed TWICE — it's a bug or a forgotten refactor.

Example: `types/check/check-fn-def!` computed `parent-list +
type-row-fields + parent-args` twice — once for the pre-pass
validators, the second time for the inference body. The second block was a full
copy of the first + one cosmetic `cond` → `if`. Lifting into a shared outer
`let` removed 12 lines.

### 3.4 A helper for a cond-tree with the same result shape

If in a large `(cond …)` tree each branch returns a map with the
same keys (`{:type T :value V}`), and the differences are only in the
values — extract into a classifier:

```clojure
(defn- binding-info-entry
  "..."
  [b-form]
  (cond
    (rename-binding? b-form)   {:type … :value nil}
    (value-binding?  b-form)   {:type … :value … :value-present true}
    …))
```

Example: `types/check/bindings-info-for-rule` had 80 lines of `cond`
inside an `into {} (map …)`. Extracted `binding-info-entry`.

## 4. Dead code — `tools/reachability_audit.clj`

Run:

```bash
clojure -M:dev tools/reachability_audit.clj
```

Prints "Unreachable COMPOSED fn-defs" — these are candidates for deletion.
Before deleting **check with grep** that the name isn't used
somewhere else (comment, docstring, a `requiring-resolve` string). If
there are only declarative references — delete and leave a comment
"deleted, replaced by X" in place.

**Type-rows and base-fns in "Unreachable" — NOT dead code.** This is
the language's dictionary: a subset is used by the application, the rest
awaits user fn-defs.

For `src/` code there is no equivalent — `clj-kondo --unused-private-vars`
catches unused private vars, but not cross-namespace. If you suspect
dead src-code — grep `(<symbol>` across the project, filter out
comments.

## 5. Complex / unclear logic — what to look for

### 5.1 Deep nested let (> 4 levels)

```bash
grep -rEnB1 'let\s+\[.*\n.*let\s+\[.*\n.*let\s+\[.*\n.*let' src --include='*.clj'
```

If found — consider extraction: each inner `let` usually
carries the name "I do X, then Y".

### 5.2 cond-tree with > 6 branches

Each branch should have a docstring comment, OR the whole tree
should be a simple dispatch by shape (see §3.4). If neither one nor
the other — it's a marker of "many unnamed cases".

### 5.3 Excessive `(or x y)` for null-handling

```bash
grep -rEn '\(or\s+\(\.\S+\s+\S+\)\s+""' src --include='*.clj'
```

Here you often need a `(str (.getMessage e))` wrapper — `str` coerces
nil to `""`, while a double `or` is unreadable.

### 5.4 Cyclic deps / `requiring-resolve` chaos

```bash
grep -rEn 'requiring-resolve' src --include='*.clj' | wc -l
```

`requiring-resolve` is a **valid escape hatch** for breaking
circular dependencies (executor ↔ registry), but if there are > 10
across the project — the dependency structure is contorted.

## 6. N+1 queries — DB-access patterns

```bash
echo '=== loop over sp/read-entity ==='
grep -rEC1 'doseq\s+\[.*\n.*sp/read-entity' src --include='*.clj'

echo '=== map over sp/query-entities ==='
grep -rEn '\(map\s+#\(.*sp/query-entities' src --include='*.clj'
```

If found — replace with a batch API:

- `sp/read-entities` (batch read by ids)
- `sp/query-entities` with an `:id` IN clause

**Not every doseq over `sp/`** is N+1. If the iteration is over 4-5 entity-types
(the list itself is fixed and small) — it's not N+1, it's iteration over a dictionary.

## 7. Security

### 7.1 Comparing secrets — constant-time

```bash
grep -rE ':equal\?' resources/packages/web/ring-adapter resources/packages/app
```

Bearer-token / HMAC-tag / vault-token comparison must go through
`:constant-time-equal?` (`MessageDigest/isEqual`), not `:equal?`
(`=`). `=` short-circuits on the first mismatched byte — a timing
channel, a one-byte-per-round leak during probing.

For new code with secrets: always use `:constant-time-equal?`.

### 7.2 SQL injection / shell injection

```bash
grep -rEn '\(jdbc/execute!\s+\w+\s+\(str\s+' src --include='*.clj'  # → should be empty
grep -rEn 'shell\s+\(str|sh\s+\(str' src --include='*.clj'           # → should be empty
```

In Graphden HoneySQL covers all known paths; a raw `(str ...)` in
JDBC is a reason to dig deep.

### 7.3 Read-string / eval on user-input

```bash
grep -rEn 'read-string|eval\s+\(' src --include='*.clj'
```

`packages/loader.clj`'s `read-string` reads a CLASSPATH-resource — OK
(supply-chain, not runtime). Others should not read.

### 7.4 SQL — HoneySQL by default, raw-string only by carve-out

**Rule**: every new JDBC query is built via `honey.sql/format`
over a data-map, not via `(str "SELECT … " var " …")`. This is already
the dominant style today in `storage/postgres/*.clj` (90%+ of sites);
any new raw-string in `src/` is a reason to justify a carve-out.

**Why:**

- **Safety**: HoneySQL automatically parameterizes values
  (`?`-placeholders), rules out identifier-injection through a
  user-supplied table-name. Raw `(str "\"" jt "\"")` requires
  manual escaping — easy to forget.
- **Composability**: a query is data; steps (where, order-by) can be
  assembled with `cond->` / `merge` without string-concat acrobatics.
- **Consistency**: the codebase is already HoneySQL-heavy; new raw-sites
  break navigation and review style.
- **Refactor-friendly**: a column rename = a keyword edit, not grep+sed
  over SQL fragments.

**When a raw-string IS JUSTIFIED** (carve-outs):

| Carve-out | Example | Reason |
|---|---|---|
| PG built-in RPC | `SELECT pg_notify(?, ?)`, `pg_try_advisory_lock(?)` | HoneySQL coverage of PG-RPC functions is weak; raw is idiomatic + 1 line |
| DDL edges | `CREATE TYPE … AS ENUM (…)`, dynamic `CREATE INDEX` names | HoneySQL DDL coverage is partial; ENUM with runtime-values is awkward |
| One-line SQL without runtime data | `"SELECT pg_advisory_unlock_all()"` | Nothing to parameterize; HoneySQL overhead = pure noise |

**Detection:**

```bash
# Raw SQL strings with runtime data:
grep -rEnB1 '\(str\s+"(SELECT|INSERT|UPDATE|DELETE|CREATE|ALTER|VALUES|WITH)\b' src --include='*.clj' | head

# JDBC execute with an explicit string query (no HoneySQL):
grep -rEn 'execute!.*\[\s*"(SELECT|INSERT|UPDATE|DELETE)\b' src --include='*.clj' | head
```

Each hit is either a carve-out from the table above (with an inline
comment on why), or a migration candidate.

**HoneySQL gotchas:**

- **PG reserved words** (`user`, `order`, `group`, `from`, …) — entity
  names may land in the table-arg. Without quoting, `UPDATE user …` will fail.
  Solution: `(sql/format … {:quoted true})` for query-builders that
  accept an entity-name from outside. Within-storage queries with fixed
  identifiers don't need quoting. See `build-batch-update-sql` in
  `storage/postgres/crud.clj`.
- **Batch INSERT via `jdbc/execute-batch!`** — JDBC API: one SQL
  template + N param-sets. HoneySQL gives `[sql & params]`; for
  execute-batch take `(first formatted)` — SQL with `?`-placeholders.
  Pattern: `insert-junction-sql` in `storage/postgres/junction.clj`.
- **Per-cell casts in `VALUES (...)`** — for PG-specific type coercion
  of each cell use `[:cast value :uuid]`. HoneySQL renders it as
  `CAST(? AS UUID)` — semantically identical to `?::uuid`.
- **Derived table with column-aliases**: `:from [[{:values …}
  [:v {:columns [:id :col1 :col2]}]]]` → `FROM (VALUES …) AS v(id,
  col1, col2)`. Pattern: `build-batch-update-sql`.

**Migration reference** (for analogous tasks):

- `storage/postgres/junction.clj` (6 raw-sites → HoneySQL,
  preserved `execute-batch!`)
- `storage/postgres/crud.clj build-batch-update-sql` (UPDATE FROM
  VALUES + per-cell casts + RETURNING)

## 8. Nil safety — Throwable/.getMessage and analogues

Java-API contract: `.getMessage` may return null. If the result
goes into a user-facing `:error` / `:reason` / message field — wrap it in
`str`:

```clojure
;; BAD — JSON will emit null, UI renders "rejected, no reason"
{:reason (Throwable/.getMessage e)}

;; GOOD — an empty string, UI at least sees something
{:reason (str (Throwable/.getMessage e))}
```

If it goes INTO `(str "prefix " (.getMessage e))` — the outer `str`
already coerces nil, **the inner str is redundant** (splint will complain).

The same check for `:cause`, `:caused-by` fields. On an `ex-data` payload
— not critical (`null` serializes and parses correctly).

## 9. Tests — flakes and anti-patterns

### 9.1 A fixed `Thread/sleep` before an assert — a flake under parallel load

```bash
grep -rEn 'Thread/sleep\s+[1-9][0-9]{2,}' test --include='*.clj' | head
```

`(Thread/sleep N)` before `(is (>= @iters K))` or a similar check
— under parallel-test CPU contention the thread may NOT manage to run the
iterations within N ms, and the test fails.

Replace with poll-with-deadline:

```clojure
(let [deadline (+ (System/currentTimeMillis) 2000)]
  (while (and (< @iters K)
              (< (System/currentTimeMillis) deadline))
    (Thread/sleep 20)))
```

### 9.2 `(is true)` / `(is (= 1 1))` — empty assertions

```bash
grep -rEnB1 '\(is\s+true\)' test --include='*.clj'
```

If a function "must not throw" — its call BY ITSELF checks that
(if it throws — kaocha will report it). `(is true)` adds nothing.

Replace with an observable check: `(is (nil? (get-X)))` after an operation
that should not have written anything.

### 9.3 Duplicate tests across files

```bash
python3 << 'EOF'
import os, re
from collections import defaultdict
names = defaultdict(list)
for root, _, files in os.walk('/root/projects/graphden/test'):
    for f in files:
        if not f.endswith('_test.clj'): continue
        path = os.path.join(root, f)
        with open(path) as fh:
            for m in re.finditer(r'^\(deftest\s+(\^?:?\w*\s*)?([a-zA-Z]\S+)', fh.read(), re.M):
                names[m.group(2)].append(path)
for k, v in names.items():
    if len(v) >= 2:
        print(f"  {k}: {len(v)}x — {[p.split('test/')[1] for p in v]}")
EOF
```

True duplicates are two functions testing the SAME thing (one is a strict
subset). Delete the subset, keep the superset.

NOT a duplicate — tests with the same name for different ASPECTS
(`read-config-test` in `interface-test.clj` about unit semantics,
`read-config-test` in `executor-runtime/core-test.clj` about
integration). They should not be merged — each covers its own
layer.

### 9.4 `with-redefs` on a non-`^:dynamic` var outside a `^:serial` ns

```bash
for f in $(grep -rl 'with-redefs' test --include='*.clj'); do
  head -3 "$f" | grep -q '\^:serial' || echo "  $f"
done
```

`with-redefs` modifies the root binding (NOT thread-local). In a parallel
kaocha NS, other threads see the redef. If the NS is not `^:serial`, it flakes.
Solution: either a `^:serial` meta on the ns, or move the test into a separate
`^:serial` NS.

### 9.5 Quality of assertions — weak `(is)` proves nothing

The skill §9.1-9.4 catches EMPTY assertions. §9.5+ catches **green tests
that check something other than what they claim**.

#### 9.5.1 Tautologies — `(is (= X X))`

```bash
# (is (= literal literal)) — both sides identical:
grep -rEn '\(is\s+\(=\s+(:?\w+)\s+\1\s*\)' test --include='*.clj'

# (is (= (f arg) (f arg))) — the same call on both sides:
grep -rEn '\(is\s+\(=\s+(\([^)]+\))\s+\1\s*\)' test --include='*.clj'
```

Always green, checks NOTHING. Delete or replace with a concrete
expected value.

#### 9.5.2 Laziness: `(is (some? …))` / `(is (not= nil …))` where the expected is known

```bash
# (is (some? (function-call ...))) — where the exact value can be checked:
grep -rEn '\(is\s+\(some\?\s+\(' test --include='*.clj' | head -10
```

`(is (some? (read-entity ...)))` is green for any non-nil — it does NOT
guarantee the data is correct. `(is (= expected-shape (read-entity
...)))` catches a regression on a shape change.

**When `(some? ...)` is justified**:

- The value being checked is an opaque handle (UUID, future, atom) without a stable
  representation
- A "didn't blow up" test in initialization (but then better `(is (nil?
  (init)))` — an observable check)

#### 9.5.3 `(is (thrown? Exception ...))` without a class/regex

```bash
grep -rEn '\(is\s+\(thrown\?\s+Exception\b' test --include='*.clj' | head -10
grep -rEn '\(is\s+\(thrown\?\s+Throwable\b' test --include='*.clj' | head -10
```

`Exception` catches EVERYTHING — including a `NullPointerException` from a typo in
the test setup. Replace:

- `(is (thrown-with-msg? ClassName #"specific msg" ...))` — an exact
  contract
- `(is (thrown? ClassName ...))` — a concrete class (`ExceptionInfo`,
  `ArithmeticException`, etc.)

#### 9.5.4 Logic inside `(is)` — `loop` / `if` / `cond`

```bash
grep -rEn '\(is\s+\((loop|if|cond|when|when-let|let)\b' test --include='*.clj' | head -10
```

```clojure
;; BAD — logic inside is. If the loop is buggy, "the test is green" can
;;       mean different things.
(is (loop [n 0]
      (if (>= n 100) true (recur (compute n)))))

;; GOOD — logic BEFORE is, is checks the result.
(let [result (loop [n 0]
               (if (>= n 100) :done (recur (compute n))))]
  (is (= :done result)))
```

`when` / `when-let` are especially treacherous: they return nil when the
condition is false, and `(is nil)` — that's a FAIL! So `(is (when X Y))` is
an extra layer.

#### 9.5.5 Multiple `is` in one `testing` without an explicit connection

```bash
# `testing` with >3 `is` in a row — a candidate for splitting:
python3 << 'EOF'
import re, os
for root, _, files in os.walk('/root/projects/graphden/test'):
    for f in files:
        if not f.endswith('_test.clj'): continue
        path = os.path.join(root, f)
        with open(path) as fh: content = fh.read()
        for m in re.finditer(r'\(testing\s+"([^"]+)"((?:\s*\([^()]*\([^()]*\)[^()]*\))+)', content):
            label = m.group(1)
            block = m.group(2)
            is_count = len(re.findall(r'\(is\s+', block))
            if is_count >= 4:
                line = content[:m.start()].count('\n')+1
                print(f"  {path.split('test/')[-1]}:{line}  testing \"{label[:40]}\"  ({is_count} is)")
EOF
```

If in `(testing "X" ...)` there are 4+ UNRELATED `is` checks (different
aspects of the system) — the failure doesn't show what broke, and a rerun-after-
fix is unclear. Split into separate `testing` blocks.

#### 9.5.6 Testing the impl, not the contract

Marker: the test references private symbols (`#'ns/private-fn`) or
checks internal data structures.

```bash
grep -rEn "#'\S+/_?[a-z]" test --include='*.clj' | head -10
```

```clojure
;; BAD — testing private impl
(is (= 42 (#'my.ns/internal-counter-state ctx)))

;; GOOD — testing an observable contract
(is (= 42 (public-api/get-counter ctx)))
```

Tests of private impl break on refactors that do NOT break
behavior — this is false-negative debt.

#### 9.5.7 Test names — describe what is being checked

```bash
# Test names of the form test-1 / my-test / works:
grep -rE '^\(deftest\s+(test-?[0-9]+|my-?test|works?|test|t)\b' test --include='*.clj'

# deftest without a -test suffix:
grep -rE '^\(deftest\s+[a-z]\w*[^-][^t]\b' test --include='*.clj' | grep -v '\-test\b' | head -5
```

A good name: `<concept>-<scenario>-test` or `<concept>-<expected-
behavior>-test`. Example: `register-base-fns-handles-empty-defs-map-
test` is better than `test-1`.

#### 9.5.8 Commented-out tests

```bash
grep -rEn '^\s*;;\s*\(deftest|^\s*\(comment\s+\(deftest' test --include='*.clj' | head -5
```

`(comment (deftest ...))` or `;;; (deftest ...)` — forgotten code. Either
delete it, or enable it (if the test should work). Never
leave it "for later" — it turns into permanent noise.

#### 9.5.9 Inter-test dependencies via global state

```bash
# defonce / def in a test ns — potential shared state:
grep -rEn '^\(defonce\b' test --include='*.clj' | head -5
grep -rEn '^\(def\s+\^:private\s+\S+\s+\(atom' test --include='*.clj' | head -5
```

Tests sharing state via `defonce` atoms or global Vars break
by execution order. Each test must start from a known state —
via a `:each` fixture or an explicit setup in `let`.

#### 9.5.10 Over-mocking in integration tests

```bash
# Integration tests with >3 with-redefs:
python3 << 'EOF'
import os, re
for root, _, files in os.walk('/root/projects/graphden/test/graphden/integration'):
    for f in files:
        if not f.endswith('.clj'): continue
        path = os.path.join(root, f)
        with open(path) as fh: content = fh.read()
        count = len(re.findall(r'\(with-redefs\b', content))
        if count >= 3:
            print(f"  {path.split('test/')[-1]}: {count} with-redefs")
EOF
```

An integration test with 3+ `with-redefs` is a unit test with stubs,
it has lost its purpose (to check the production-shape). Either remove the
mocking, or rename it to a unit test and move it to `test/graphden/
<module>/`.

## 10. Test speed — what is actually worth fixing

### 10.1 Heavy fixtures — the golden-bootstrap pattern

Integration tests that need the full package-set should
go through `setup/bootstrap-crud-graph-from-golden!` (TEMPLATE
clone, ~100ms / NS), not through `setup/bootstrap-crud-graph!`
(full sync, 10-14s / NS).

Check:

```bash
grep -lE 'bootstrap-crud-graph!' test/graphden -r | xargs grep -L 'from-golden'
```

If found — migrate to golden, except in cases where the test
needs the bootstrap process itself (for example, a test that checks sync).

### 10.2 A long Thread/sleep in integration tests with a polling-accessible contract

If a test checks a cron / future / async-flow with a fixed
sleep, and the system has polling semantics (deadline + poll) — those are
wasted seconds.

Not every sleep can be removed: "cron `* * * * * ?` fires once a
second" — an 1100ms sleep is justified, because that's the OBLIGATION
of the cron.

### 10.3 A configurable poll-timeout for polling-based primitives

Example: `pg-notify/create-listener` accepts an optional
`:poll-timeout-ms`. Production — 1000ms (low idle-CPU), tests —
250ms (fast wake-up). An analogous pattern in any "idle pod
polls" primitive.

## 11. Performance — don't optimize without measuring

Before optimizing:

```bash
# Find the slow operation:
clojure -M:dev:test -m kaocha.runner --focus ...   # the kaocha profiling plugin prints top-slow
```

New-performance anti-patterns:

- **An atom-cache in a hot path WITHOUT measuring** — it may reduce throughput due to
  contention.
- **Memoize over a function with unbounded args** — a memory leak.
- **Eager-loading a big result set** into `(into [])` when a `lazy-seq`
  is enough.

## 12. Test coverage — not for the sake of a percentage

**Our baseline is already alpha-grade** (see `bb coverage` — unit with
cloverage instrumentation). Add further tests only when:

1. **A regression sentinel** on a critical invariant (security,
   versioned-storage merge, executor compile). Example (from this
   session) — `logic_test.clj` for `:constant-time-equal?` so that a
   future "optimization" doesn't break constant-time.
2. **Coverage of a new scenario** — a feature added user-visible behavior.
3. **Reproduce-on-CI of an existing bug** — a perpetual regression
   guard.

**Do NOT add a test:**

- To bump coverage% on a defensive `log/warn` in a catch — the log
  path is expensive to test, the regression risk is low.
- A duplicate of an already-covered path — see §9.3.
- "Just in case" without a named regression mode.

## 13. Workflow — where to edit and how to check

### 13.1 Writing new code

1. Before writing — `bb check` green.
2. You write the module — following this skill right away (short functions, DRY,
   clear names, nil-safety).
3. Before committing — `bb check` + focused tests of the touched ns's.

### 13.2 Checking existing code

1. **Baseline** — `bb check`, `bb test`, reachability audit. Write down
   what's green now, what's red.
2. **Scan per §1-12** — each section gives a `grep`/`python` one-
   liner for finding candidates.
3. **Summary** before edits — how many candidates were found, what the
   priorities are (security > nil-safety > dead code > DRY > splits).
4. **Edit by priority** — for each significant change **commit
   per checkpoint** (a recommendation from CLAUDE.md), not in a wall-of-text batch.
5. **Verify after each commit** — `bb check` + focused tests of the
   touched ns's.
6. **Final sweep** — `bb test` or `bb ci` (depends on the
   amount of changes).

### 13.3 Commit rules — each commit is a value in itself

From the round-1 + round-2 sessions:

- **One conceptual change per commit** — a security-fix and a DRY
  refactor should not be in one commit.
- **Subject ≤ 70 chars** — "refactor(types/check): extract closure-
  strip + literal-bound throw". The prefix shows the type: `refactor`,
  `fix`, `security`, `perf`, `test`, `style`, `docs`.
- **Body explains WHY** — not WHAT (the diff shows what); WHY = "it was
  X-shape, the reason / effect Y".
- **Verified-by lines** — which tests confirmed no-regression
  (`23 tests / 78 assertions, 0 failures`).

## 14. Anti-patterns (don't do)

- **Big-bang refactor.** "Let's cut all 5 large files in one
  commit" — you're guaranteed to break something imperceptibly. Per-target,
  per-commit.
- **Refactor + behavior change in one commit.** Refactor = same
  observable behavior; mixing it with a fix changes the smell-test.
- **A silent rewrite of a docstring** when extracting a helper. Moving
  the logic is OK; moving + rephrasing the reason loses the history.
- **Deleting code without a grep on the name.** The name may appear in
  a comment — the comment will go stale.
- **Premature optimization** — adding a cache / batching without a
  baseline measurement. More code, no gain.
- **`bb coverage` after every edit.** ~15-19 minutes — that's a
  periodic audit, not a CI-gate. Day-to-day — `bb check` +
  focused tests.

## 15. Integration tests — `test/graphden/integration/`

The integration suite sits in `test/graphden/integration/` (11 NSes as
of 2026-07-05). Each — `^:integration` meta, goes through a
shared PG testcontainer + golden-bootstrap. This is the most expensive
testing (`bb test` integration takes ~70% of wall-time), so
quality here is critical.

### 15.1 Coverage matrix — which user-flows MUST be covered

List of critical user-flows and their coverage:

| User-flow | Integration test | If missing — risk |
|---|---|---|
| Server bootstrap (full sys/start-with-overrides!) | `smoke-pass-test` | a regression in integrant wiring reaches prod |
| `/api/execute` happy path + cancellation + timeout | `execute-http-test` | an execute-pipeline regression isn't caught by units |
| `/api/secrets/*` end-to-end (vault create/rotate/delete) | `secret-flow-test` | vault integration breaks silently |
| Cron `:schedule` → service registration → reconciler-driven fire | `cron-schedule-service-test` | cron breakage is discovered only in prod |
| `find-fn-usages` through the graph | `find-fn-usages-graph-test` | usage-graph regression hides |
| Storage protocol contract (any backend) | `storage-protocol-poc-test` | future backends aren't checked |
| **Branches** (create, switch, diff, merge) | `branches-lifecycle-test` | — |
| **Services** (full reconciler lifecycle for HTTP server) | `http-server-service-lifecycle-test` | — |
| **Auth middleware** (real bearer-token request → 200 / 401) | `auth-middleware-test` | — |
| Tenancy (FaaS addon-active harness) | `faas-app-test` | — |
| Admin grants (per-request-scope router) | `grants-admin-test` | — |

All critical flows are currently covered. For a NEW critical flow: set up an
integration test (a sentinel for regression) OR explicitly justify why
unit-level is sufficient.

### 15.2 Duplication audit — what integration MUST NOT do

An integration test does not duplicate a unit test. Scenario:

| Layer | What is checked | Where to put it |
|---|---|---|
| Unit | Pure logic (parse / validate / format / classify) | `test/graphden/<module>/<file>_test.clj` |
| Integration through the graph | DB write + read through VersionedStorage | `test/graphden/crud/<file>-graph-test.clj` (NOT `integration/`) |
| Integration through the full system | Full sys/start-with-overrides! + HTTP roundtrip + cleanup | `test/graphden/integration/` |

If an integration test can be repeated through `crud/...-graph-test`
without bootstrapping the full system — it's overkill. Move it.

### 15.3 Performance of the integration suite

```bash
# Total wall time per integration test:
clojure -M:dev:test -m kaocha.runner --config-file tests.edn :integration --reporter kaocha.report/documentation 2>&1 | tail -30
```

Target numbers:

- `smoke-pass-test`: 30-60 s (full bootstrap + 1 pass)
- `cron-schedule-service-test`: 60-120 s (includes a 1+ s cron-fire)
- `execute-http-test`: < 20 s
- `secret-flow-test`: < 30 s

If a test > target: either an extra bootstrap (use golden), or
extra `Thread/sleep`s, or genuinely heavy work — justify it.

```bash
# Which integration tests are NOT through golden?
grep -L 'bootstrap-crud-graph-from-golden' test/graphden/integration/*_test.clj 2>/dev/null
```

### 15.4 What should be in every integration test

- **One user-flow per NS** — do NOT put 5 unrelated ones in one
  `^:integration` (one failure fails everything)
- **Cleanup gate** — each test cleans up after itself (either an `:each` fixture
  with clean-db, or an explicit `(finally (sys/stop! system))`)
- **Sleep by contract, not by hope** — `Thread/sleep 1100` for cron
  is justified (per-second contract); 5 s "just in case" — no
- **One assert cycle** — `(testing "complete flow" ...)`, NOT
  10 separate testings with independent submissions

## 16. Browser tests — `tools/browser-test/*.test.js`

The browser suite — 56 Playwright e2e tests in `tools/browser-test/`

- the visual-snapshot suite in `tools/visual-tests/`. ~9000 LOC JS,
covering the editor UI flow.

### 16.1 Coverage matrix — UI features → test files

Editor `editor-*.js` modules and their e2e coverage:

| UI module | Coverage | Browser tests |
|---|---|---|
| Auth / login | ✅ | `edit-auth-login` |
| Sidebar + ns tree | ✅ | `edit-sidebar-*` |
| Branches (create/switch/diff/merge) | ✅ | `edit-branch-*` (3 tests) |
| Secrets panel + create / rotate / delete | ✅ | `edit-secrets-*` (3 tests) |
| Arg value / type edit | ✅ | `edit-arg-*` (4 tests) |
| Fn create / edit / delete | ✅ | `edit-fn-*` (6 tests) |
| Type-row CRUD | ✅ | `edit-type-*` (7 tests) |
| Effects tighten | ✅ | `edit-effects-*` (3 tests) |
| Re-parent / Phase-3 | ✅ | `edit-reparent`, `edit-phase3-reparent` |
| Sequence ops | ✅ | `edit-sequence`, `edit-phase5-sequence` |
| Execute popover + history | ✅ | `edit-execute` (2 tests) |
| Free-arg propagation | ✅ | `edit-free-*` (3 tests) |
| Service popover | ✅ | `edit-service` (2 tests) |
| Description / tooltip / mismatch | ✅ | `edit-description`, `edit-mismatch` |
| **Build hash verify** | **❌ gap** | no browser test on `window.BUILD_HASH` after deploy |
| **Layout edge labels click → expand** | **❌ gap** | a complex flow without e2e cover |
| **Visual regression** | ✅ | `tools/visual-tests/*` (separate suite) |

### 16.2 Duplication audit between browser tests

```bash
# How many times each prefix is tested:
ls /root/projects/graphden/tools/browser-test/*.test.js | xargs -n1 basename | \
  sed -E 's/(edit-[a-z]+|regression|type-system-ui).*/\1/' | sort | uniq -c | sort -rn
```

If a prefix has > 5 files and they all test a similar API — candidates
for merging. Example: 7 `edit-type-*` files — but each tests
ITS OWN thing (variant / record / list / record-remove etc.) — that's OK,
single-test-per-shape.

**Pattern smell**: two files with nearly-identical setup (>50% same code)

- a different assertion — a candidate for parameterization (one test-function, two
calls with different scenarios).

```bash
# Find tests that open the same initial state:
grep -lE 'navigateTo.*"web-server"' tools/browser-test/*.test.js | wc -l
grep -lE 'createComposedFn.*const' tools/browser-test/*.test.js | wc -l
```

### 16.3 Performance — total wall time + parallelization

```bash
# How many files = how many Playwright processes (if not parallelized):
ls tools/browser-test/*.test.js | wc -l
echo "Per-test setup cost: chromium launch + page navigation ~ 3-5 s"
echo "Sequential total: ~50 tests * 30 s avg = 25 min"
```

Modern best practices:

- **Shared `browser.newContext()` per file**, not per test (if the file
  has 1 test — ok; if several — shared)
- **Parallel run via `npx playwright test`** (if using the `@playwright/
  test` runner). Right now our files are standalone `node *.js` scripts,
  parallel does NOT work out of the box → a migration target
- **Visual regression suite — a separate CI phase** (not every PR)

### 16.4 Reliability — cleanup, wait strategies, selectors

#### 16.4.1 Cleanup race

```bash
# Each browser test should start with cleanup:
grep -LE 'cleanup\(|deleteFnByName|delete.*before' tools/browser-test/*.test.js | head
```

Browser tests run in parallel — each seeds a fn-def with a unique `RUN_ID`
(`process.pid + Date.now`). If cleanup doesn't work — garbage piles up
in the dev-DB → the next full reset via `bb deploy`.

**Pattern check**: `RUN_ID = '-' + process.pid + '-' + Date.now()` —
each probe-fn should have a suffix.

#### 16.4.2 Wait strategies — `waitForSelector` > `page.waitForTimeout`

```bash
# page.waitForTimeout — flaky under load:
grep -rEn 'waitForTimeout\s*\(\s*[1-9]' tools/browser-test/*.test.js | head
```

Fixed-time waits in browser tests = same as `Thread/sleep` in Clojure
tests (see §9.1). Replace with polling: `page.waitForSelector(...)`,
`page.waitForFunction(...)`, `await assert(...)` with retry.

#### 16.4.3 Brittle selectors — `:nth-child` / class-by-text

```bash
# nth-child / nth-of-type — fragile to UI rearrangement:
grep -rEn ':nth-child\(|:nth-of-type\(' tools/browser-test/*.test.js | head

# CSS class by content — can break on a theme refactor:
grep -rEn 'querySelector.*\.[\w-]+:has-text' tools/browser-test/*.test.js | head
```

Stable selectors (from best to worst):

1. `data-testid="foo"` — an explicit test handle
2. `getByRole('button', {name: 'Save'})` — semantic
3. `text=Save` — content-based (breaks on i18n)
4. `.css-class` — breaks on a style refactor
5. `:nth-child(3)` — breaks on a layout change

#### 16.4.4 Auth-token leakage in test output

```bash
# Tokens hardcoded vs env-var:
grep -rEn 'Bearer\s+[a-zA-Z0-9]' tools/browser-test/*.test.js | head
# Should be only process.env.AUTH_TOKEN
```

### 16.5 What should be in every browser test

- **Header docstring** — what it tests + run command + exit codes
- **Unique RUN_ID** — `'-' + process.pid + '-' + Date.now().toString(36)`
- **Cleanup gate** — try/finally + a `cleanup(page)` wrapper
- **Console error listener** — `page.on('console', ...)` catches UI
  exceptions during the test
- **Dialog handler** — `page.on('dialog', d => d.accept())` if
  cleanup may trigger a confirm-dialog
- **Final `process.exit(0|1)`** — the exit code determines PASS / FAIL
- **No `console.log` after assert success** — clean output

## 17. Connections with other skills

- **`graphden-packages-quality`** — the same principles for `fns.edn` +
  `impls.clj` (types, fn-def naming, minimal base-fn). If the work
  is in `resources/packages/` — switch over.
- **`graphden-fn-design`** — the detail on naming / namespaces / MI for
  fn-defs. Called by `graphden-packages-quality` for specifics.
- **`graphden-fn-refactor`** — decomposition of base-fn impls. Called by
  `graphden-packages-quality` for specifics.
- **`graphden-repl`** — debugging a hypothesis before `bb rebuild`.
  Used ALWAYS when you need to check "what this function will return
  right now".
- **CLAUDE.md** — the primary source of project principles. This skill is its
  operational arm.

## 18. What counts as "nothing left to nitpick"

Final self-check before closing:

**Code & lint**

- [ ] `bb check` green (0 warnings)
- [ ] focused tests of touched ns's green
- [ ] `bb test` or `bb ci` (depending on scope) green
- [ ] Reachability audit shows no new unreachable (if you
      changed `fns.edn`)
- [ ] No TODO/FIXME/XXX/HACK markers without an issue link

**Structure**

- [ ] Each function ≥ 100 LOC is JUSTIFIED (see §1.5) or split
- [ ] User-facing `:error` / `:reason` fields are nil-safe
- [ ] Each secret compare is constant-time
- [ ] Each new JDBC query goes through HoneySQL `sql/format`; a raw-string
      only by carve-out from §7.4 (PG-RPC / DDL edge / no runtime data)

**Unit tests**

- [ ] No duplicate deftests with the same observable assertions
- [ ] Each sleep in tests is either justified by a runtime contract, or
      replaced with poll-with-deadline
- [ ] No tautological `(is (= X X))` / `(is (some? …))` where there's a
      concrete expected (§9.5.1-9.5.2)
- [ ] No `(is (thrown? Exception …))` without a class/regex (§9.5.3)
- [ ] No logic (`loop` / `if` / `when`) inside `(is …)` (§9.5.4)
- [ ] `testing` blocks test ONE thing — not 4+ `is` in a row (§9.5.5)
- [ ] No tests on private symbols (`#'ns/_internal`) (§9.5.6)
- [ ] No commented-out deftests (§9.5.8)

**Integration tests**

- [ ] Each critical user-flow is covered (§15.1 matrix gaps)
- [ ] Integration does NOT duplicate the unit-test layer (§15.2)
- [ ] All integration tests go through golden-bootstrap (§15.3)
- [ ] One user-flow per NS (§15.4)

**Browser tests**

- [ ] A new UI feature → a new `*.test.js` OR an explicit "not needed"
      (§16.1 matrix)
- [ ] Cleanup gate in every `*.test.js` with a RUN_ID (§16.4.1)
- [ ] No `page.waitForTimeout(N)` without justification (§16.4.2)
- [ ] Selectors — `data-testid` / `getByRole`, not `:nth-child`
      (§16.4.3)
- [ ] Auth-token via `process.env`, not hardcoded (§16.4.4)

**Commit hygiene**

- [ ] Each commit is a separate concept-value-unit with a verified-by
      line in the body
