---
name: graphden-packages-quality
description: Качество `resources/packages/**/{fns.edn,impls.clj}` — узкие типы и type-aliases, минимальные base-fn impls, **аудит ЛЮБЫХ Clojure-helpers в impls (включая private `defn-`, middleware closures, handler wraps — не только `defbase` тела)**, корректное использование именованных vs анонимных fn-defs. Применяй при ЛЮБОМ касании пакетного слоя — даже если правка выглядит как «просто добавил private helper в impls» или «Ring middleware glue», это всё равно пакетный слой и кандидат на graph-decomposition. Также как explicit-проверка существующего ("пройдись по пакетам", "сузь типы", "почисти fns.edn", "проверь impls на лишнюю логику"). Триггеры — фразы вроде "fn-def", "fns.edn", "impls.clj", "base-fn", "private helper", "defn- в impls", "middleware", "ring wrap", "handler closure", "orchestration", "cache wrap", "post-process", "тип слишком широкий", ":jsonb", ":any", "type alias", "длинный union", ":nullable-*", "именованный или анонимный", "extract в helper", "impl содержит логику", "MI vs single-parent", "namespace для fn", "переиспользование fn-def", "доступно для админа", "должно быть конкретным типом". SKIP для: чисто Clojure src/test — кода (→ `graphden-code-quality`), pure REPL-debug гипотез (→ `graphden-repl`), frontend (.js/.css) — отдельный скилл.
---

# graphden-packages-quality — типы, impls, fn-defs в `resources/packages/`

Задача этого скилла: **держать пакетный слой Graphden в форме, в которой
он остаётся объяснимым внешнему контрибьютору** — узкие типы, named-and-
reused aliases, минимальные base-fn impls (вся композиция в графе), и
правильное использование named vs anonymous fn-defs.

Этот скилл — общий вход. Для деталей он делегирует:

- **`graphden-fn-refactor`** — декомпозиция больших / неатомарных
  base-fn impl. Применяется И для нового кода (новые impls тоже должны
  быть атомарными), И для аудита существующих.
- **`graphden-fn-design`** — naming rules для fn-defs (`_`-private vs
  public, MI vs single parent, namespaces, `:const`-обёртки). Применяется
  И для нового кода, И для аудита существующих.
- **`graphden-code-quality`** — Clojure-сторона (src/), если правка
  тащит за собой src-изменения.

Этот файл добавляет **специфичные пакетному слою правила**, которых нет
в делегатах: type-narrowing, type-aliasing, и порядок проверки.

**Применять и при письме нового кода, и как explicit-проверку
существующего.** Если новый fn-def / type / impl проходит этот скилл с
первого захода — не придётся возвращаться. Если зовут «проверь типы» /
«почисти пакет» — это explicit-rerun по тому же списку.

## 0. Sanity checks перед началом

```bash
# Должны быть нулями. Если красное — закрывай ДО рефакторинга.
bb check                   # lint
clojure -M:dev tools/reachability_audit.clj | grep -E 'Unreachable COMPOSED|^$' -A1 | head -10
```

В REPL (`graphden-repl` skill) — для проверки гипотезы по
текущему живому графу:

```clojure
(types/resolve-alias :nullable-text)      ; => [:union :null :text]
(registry/rich-type-of :my-fn)            ; => {:return … :args … :effects …}
```

## 1. Типы должны быть максимально узкими

**Anti-pattern**: писать `:jsonb` или `:any` в slot type, когда реальный
контракт — конкретный record / refinement / union. Широкий тип ломает:

1. **Type-checker не видит ошибки** — `:jsonb` принимает что угодно
   jsonb-shaped; реальный mismatch обнаружится в runtime.
2. **Editor не показывает правильный type-chip** — пользователь не
   видит чего слот ждёт.
3. **`form-picker` (`/api/value-form`) не предлагает правильный widget**
   — `:jsonb` → generic JSON editor; `:port` → number-input с range
   validation.

### 1.1 Что сужать в первую очередь

| Симптом | Реплейс |
|---|---|
| `:jsonb` под record-shape вход | inline `{:k1 T1 :k2 T2}` или named record type-row |
| `:jsonb` под map-shape вход | `[:map K V]` (`:keyword-map`, `:text-keyed-map`, `:text-map`) |
| `:jsonb` под list | `[:list T]` |
| `:any` под callable | `:fn` (HOF-wrap) или `[:fn args ret]` (структурный) |
| `:any` под уже-готовое значение | конкретный type-row или `:jsonb` если действительно jsonb-shaped |
| `:int` под HTTP-port | `:port` или `:user-port` (refinement) |
| `:text` под URL | `:url` (refinement) |
| `:text` под required-non-blank | `:non-blank-text` |

**Когда `:jsonb` / `:any` ОПРАВДАН**:

- `:jsonb` — действительно произвольный JSON-shaped payload (e.g. user-
  supplied request body before parsing).
- `:any` — escape hatch для уже-готовой Clojure-функции (НЕ для
  callable, для уже-built fn-value) или для passthrough-семантики
  (`:const :value` — приведёт обратно через rule).

### 1.2 Как искать кандидатов на сужение

```bash
# `:jsonb` в slot types — посмотри каждое use-site:
grep -rE ':type :jsonb|"jsonb"' resources/packages --include='*.edn' | head -20

# `:any` в slot types — то же:
grep -rE ':type :any' resources/packages --include='*.edn' | head -20

# `[:union :null …]` длиннее одной строки — кандидат на alias:
grep -rEn '\[:union :null :[a-z]' resources/packages --include='*.edn' | head -10
```

Для каждого случая — спросить:
1. Что реально читает этот слот в runtime?
2. Может ли значение быть нарушающим (record вместо int)?
3. Есть ли реальный известный тип? Если да — сузить.

## 2. Type aliases — повторяющиеся shape'ы получают имя

**Правило**: если **одна и та же структурная форма** (`[:union :null
:text]` / `[:map :keyword :any]` / т.п.) появляется в **5+ местах**
по пакетам — она заслуживает name и переиспользования.

### 2.1 Готовый набор aliases

Все живут в `resources/packages/core/refinements/fns.edn`:

| Alias | Структура | Зачем |
|---|---|---|
| `:nullable-text` | `[:union :null :text]` | 37+ inline sites до aliasing |
| `:nullable-uuid` | `[:union :null :uuid]` | 24+ sites |
| `:nullable-jsonb` | `[:union :null :jsonb]` | 6+ sites |
| `:nullable-int` | `[:union :null :int]` | sequence-position, optional limits |
| `:nullable-keyword` | `[:union :null :keyword]` | variant tags from optional sources |
| `:keyword-or-text` | `[:union :keyword :text]` | post-JSONB-roundtrip identifiers |
| `:type-expression` | `[:union :keyword :text [:list :any] :keyword-map]` | type expr representations |
| `:keyword-map` | `[:map :keyword :any]` | decoded entity rows, parsed forms |
| `:nullable-keyword-map` | `[:union :null :keyword-map]` | read-or-nil entity sites |
| `:text-map` | `[:map :text :text]` | HTTP headers, form-urlencoded, vault metadata |
| `:text-keyed-map` | `[:map :text :any]` | raw JDBC rows, layout expansion maps |
| `:path-segment` | `[:union :keyword :int :text]` | `:get-in` / `:assoc-in` segments |
| `:positive-int` / `:non-negative-int` / `:negative-int` | refinements on `:int` | numeric bounds |
| `:port` (`1..65535`) / `:user-port` (`1024..`) / `:http-status` (`100..599`) | refinements on `:int` | domain ranges |
| `:percent` (`0..100`) / `:probability` (`0..1`) | refinements on `:numeric` | bounded numerics |
| `:non-empty-text` / `:non-blank-text` / `:url` | refinements on `:text` | text invariants |

Refinements (`:_refinement-narrow` template) дают runtime check —
`:ensure-positive-int :args {:value 42}` → `42` или throw
`:refinement/violated`.

### 2.2 Когда вводить новый alias

**ДА** (имя оправдано):
- ≥ 5 use-site'ов одинаковой структурной формы.
- Семантическое имя добавляет смысл (`:port` лучше `[:int {:constraint
  [:and [:>= 1] [:<= 65535]]}]`).
- Reuse внутри одного домена (HTTP-related shapes, secret-flow shapes).

**НЕТ** (alias не нужен):
- 1-2 use-site'а — inline form проще читать.
- «На всякий случай назовём» — засоряет alias-неймспейс.
- Aliasing внутри одной fn-def — это inline composite type (см.
  `graphden-fn-design` §3), не alias.

### 2.3 Куда положить новый alias

| Группа | Файл |
|---|---|
| Generic shapes (`:nullable-*`, `:keyword-map`) | `core/refinements/fns.edn` |
| HTTP-specific shapes (`:ring-request-shape`, `:ring-response-shape`) | `web/ring-adapter/fns.edn` |
| Domain-specific shapes (`:security-headers-shape`) | соответствующий пакет |
| Refinement narrowers (`:ensure-X`) | вместе с alias, в `core/refinements/fns.edn` |

При добавлении alias **обнови comment-блок** в начале секции с
актуальным call-site count (источник истины для «> 5 inline sites»).

### 2.4 Sync-time gotcha — type-aliases должны быть зарегистрированы ДО parsing'а

Сейчас `initialize-with-base-fns!` вызывает `register-type-aliases!`
через `requiring-resolve` перед base-fn validation. Если ввёл новый
alias и base-fn ссылается на него в `:return-type` — sync должен
зарегистрировать alias ПЕРЕД tем как валидировать base-fn'ы.

Это handled by `system/core/register-type-aliases!`. Не нужно ничего
дополнительно делать **если alias живёт в `core/refinements/`**
(оно load'ится первым). Если — внутри `web/`/`app/` — может быть
load-order issue; обычно решается тем, что мы делаем alias в `core`.

## 3. base-fn impls — минимальность

**Делегирует `graphden-fn-refactor`** (§3 user-composability test, §4
рецепт декомпозиции). Кратко:

- **Один прямой library/Java-вызов** + boundary-coercion → OK.
- **Executor-ядро** (`if`/`cond`/`try`/`atom`/`future`/`sleep`/`=`) → OK.
- **Алгоритм с инвариантом** (journalled-txn с rollback, cycle-guarded
  recursion) → OK.
- **Всё остальное** — композиция, переехать в `fns.edn`.

### 3.1 Доп-критерий для НОВЫХ impls (этот скилл, не fn-refactor)

При написании **нового** impl: всегда спрашивай user-composability
test ПЕРЕД написанием.

> Если пользователь Graphden'а захочет варьировать ОДИН из шагов моей
> новой impl — придётся ли ему писать новый Clojure-impl?

Если ДА — switch'ай на decompose-from-scratch, не «начну с большой
impl, потом распилим». Получится дешевле.

### 3.2 Sanity checks для impl

```clojure
;; В REPL:
(:impl @(resolve 'graphden.packages.core.logic.impls/equal?-fn))
;; → #object[...] — функция

;; Тело — ровно одна или две стороки реального кода?
;; Если distinct cond/case/let — кандидат на распил.
```

```bash
# Длинные defbase в impls.clj — кандидаты на §1 fn-refactor:
python3 << 'EOF'
import re, os
for root, _, files in os.walk('/root/projects/graphden/resources/packages'):
    for f in files:
        if f != 'impls.clj': continue
        path = os.path.join(root, f)
        with open(path) as fh: content = fh.read()
        matches = list(re.finditer(r'^\(defbase\s+(\S+)', content, re.M))
        for i, m in enumerate(matches):
            start = m.start()
            end = matches[i+1].start() if i+1 < len(matches) else len(content)
            n = content[start:end].count('\n')
            if n >= 20:
                print(f"  {n:4d} {m.group(1):30s} {path.split('packages/')[1]}")
EOF
```

≥ 20 строк defbase — пройди по `graphden-fn-refactor` §3-§4. Каждое
обоснование «не режу» — explicit (§1.5 fn-refactor).

### 3.3 Скрытая композиция в private helpers (не только в `defbase`)

**Самая частая дыра:** ты добавляешь `(defn- foo …)` в `impls.clj` для
«склейки» (Ring middleware, cache wrap, multi-step orchestration). С
точки зрения существующих чек-листов это не `defbase`, не fn-def, не
тип — формально проскальзывает. Но семантически это **композиция,
которой место в графе**.

Симптомы (любой ≥ 1 — повод остановиться):

| Симптом | Что это значит |
|---|---|
| `defn-` возвращает `(fn [req] …)` (closure-handler) | Wrap-style middleware — должен быть fn-def через `:if`/`:call`/`:cond` (паттерн `:branch-routing-wrap` в `web/branch-router/fns.edn`). |
| `defn-` оркеструет ≥ 3 шага: `(let [a (step1 …) b (step2 a) …] (final …))` | Это композиция. Каждый шаг — кандидат в base-fn, склейка — fn-def. |
| `defn-` имеет ветвление по условию response/request shape (`if-let`, `cond` по headers, `when` по content-type) | Условная логика принадлежит графу (`:if`/`:cond` over predicate base-fns). Pure runtime branching — единственное исключение. |
| `defn-` мутирует state (`swap!`/`reset!`/`alter`) И принимает данные с request-side | Mutation — нормально в impls (state живёт там), НО доступ к ней должен быть через узкие base-fn'ы (`*-get`, `*-put!`), а решение «когда читать / когда писать» — в fn-def. |
| `defn-` использует фразу «orchestrate», «process», «pipeline», «wrap», «chain» в имени или docstring | Семантический маркер композиции. |
| `defn-` вызывается из `defbase` body как «удобный helper» | Если базовый impl делегирует в helper — composition уже скрыта. Извлеки helper в отдельный base-fn (или серию base-fn'ов) и склей через fn-def. |

```bash
# Find every private helper in impls.clj — каждый > 10 lines проверь:
python3 << 'EOF'
import re, os
for root, _, files in os.walk('/root/projects/graphden/resources/packages'):
    for f in files:
        if f != 'impls.clj': continue
        path = os.path.join(root, f)
        with open(path) as fh: content = fh.read()
        matches = list(re.finditer(r'^\(defn-?\s+(\S+)', content, re.M))
        for i, m in enumerate(matches):
            start = m.start()
            end = matches[i+1].start() if i+1 < len(matches) else len(content)
            n = content[start:end].count('\n')
            if n >= 10:
                line = content[:start].count('\n') + 1
                print(f"  {n:4d} {m.group(1):28s} {path.split('packages/')[1]}:{line}")
EOF
```

```bash
# Closure-returning helpers (wraps/middleware) — почти всегда композиция:
grep -rEn '^\(defn-?\s+\S+.*\n.*\(fn\s+\[req' resources/packages --include='impls.clj' | head
# Pipeline helpers с тремя+ шагами:
grep -rEnB1 '\(->>\s+\S+\s+\S+\s+\S+\s+\S+' resources/packages --include='impls.clj' | head
# Имена с маркерами оркестрации:
grep -rEn '^\(defn-?\s+(\S*orchestr|\S*pipeline|\S*-wrap|run-handler|process-\S+|chain-)' resources/packages --include='impls.clj'
```

**Рефактор-рецепт:**

1. **Расщепи** private helper на 2-N узких base-fn'ов — каждый делает
   один шаг (cache lookup, encode-body, header-attach, etc.). Их impl —
   одна-две строки.
2. **Объяви** каждый base-fn в `fns.edn` рядом — типы аргументов,
   возврат, effects.
3. **Склей** их в graph wrap через `:if` / `:cond` / `:call` —
   эталонный пример `:branch-routing-wrap` в
   `resources/packages/web/branch-router/fns.edn`:
   ```edn
   {:name :branch-routing-wrap
    :parent :if
    :args {:test :_branch-router-installed?
           :then :_branch-dispatched
           :else :base-handler-fallback
           :base-handler {:type [:fn …] :description "…"}}}
   ```
4. **Удали** старый private helper. Composition теперь видна.
5. **Перекройся тестом** на graph-уровне — handler chain через wrap
   должен работать end-to-end (smoke + integration suite).

**Когда private helper в impls OK:**

- Тонкая boundary-coercion для library-call (`String/.getBytes`,
  `(java.io.InputStream/.read …)`, etc.) внутри одного base-fn.
- Один-выражение helper (≤ 3 строки), нет ветвления, нет state.
- Internal state-management для одной atomic примитивы (FIFO
  eviction inside a cache-put base-fn — но если eviction-decision
  зависит от данных request, она в графе).

## 4. fn-defs — named vs anonymous

**Делегирует `graphden-fn-design`** (§1 public vs `_`-private, §2 auto-
name, §3 inline composite, §5 MI vs single-parent, §6 namespaces, §7
decomposition). Кратко:

- **Public name (без `_`)** — fn переиспользуется (≥ 2 use-site сегодня
  или планируется), или это узнаваемая доменная сущность.
- **`_`-private** — одна use-site, имя не несёт смысла вне родителя.
- **Inline composite** (`:input {:k T}` / `:type {:k T}`) — анонимный
  record-shape; shape-deduped через `anonymous-hash`.
- **MI (`:parents [a b]`)** — ортогональные slot-наборы (mix-in trait),
  не «склейка behavior».

### 4.1 Доп-критерий для НОВЫХ fn-defs (этот скилл, не fn-design)

При написании **нового** fn-def: спрашивай ПЕРЕД именованием.

**Правило 1 (when name is required)**: дать explicit-public-name —
обязательно если:
- Узнаваемая доменная сущность (`web-server`, `json-ok-response`).
- Уже планируется ≥ 2 use-site (явно сейчас или в roadmap).
- Будет export'нуто из пакета (consumer'ы из других пакетов).

**Правило 2 (when `_`-private suffices)**: `_`-private name — когда
один use-site + имя «звучит» только рядом с родителем. Это
эквивалент Clojure'овского `defn-`.

**Правило 3 (when anonymous suffices)**: inline `{:parent :X :args
{:value …}}` — когда нужна разовая literal-wrapping одного use-site,
и `_`-name был бы синтетический («step1»). Это эквивалент Clojure'
овского `let`.

**Anti-pattern**: дать public name «на всякий случай». Засоряет
namespace + sidebar. → Делай `_`-private; promote'нем когда reuse
появится.

### 4.2 Sanity checks для fn-defs

```bash
# Найди fn-def, явно declared, но НЕ зарегистрирован в reachability:
clojure -M:dev tools/reachability_audit.clj 2>&1 | grep -A50 'Unreachable COMPOSED'

# Может быть:
# - Прозрачный мёртвый код (удалять).
# - Dynamic-dispatch false positive (e.g. `:postgres-storage-impl` —
#   bound runtime). Грепни имя по `src/` — если есть строка-ref,
#   оставь и добавь комментарий «dynamic dispatch».
```

```bash
# Имена fn-def'ов глобально уникальны (даже `_`-private). Перед именованием:
grep -rE ":name :the-target-name\b|defbase the-target-name\b" resources/packages/
```

### 4.3 Multi-parent (`:parents [A B]`) — правило

`graphden-fn-design` §5 даёт три «оправданных» случая (категоризация,
trait-mixin, refinement). Здесь — более жёсткое **БИНАРНОЕ ПРАВИЛО для
момента написания**, плюс `bb`-проверяемый sanity-test.

**Правило (formulated):**

> MI оправдан **тогда и только тогда**, когда каждый родитель
> представляет **отдельную ось описания** child'а — а не отдельный шаг
> в его поведении. Каждая ось добавляет НЕ-пересекающийся набор слотов
> и НЕ-конфликтующий контракт.

**Тест осей — «конъюнкция существительных» vs «конъюнкция глаголов»:**
переведи `(child :parents [A B])` в естественный язык:

- ✅ **Существительные** (this **IS-A** A AND **IS-A** B):
  - «`:postgres-storage-impl` IS-A `:Storage` (type-row protocol)
    AND IS-A concrete-impl-with-binding-set (own slots для pg-query
    binding'ов)». Type-row + impl-shape = две ортогональные оси.
  - «`:authed-get-route` IS-A `:get-route` (path + handler shape)
    AND IS-A `:auth-required` (middleware chain)». Маршрут-форма +
    capability-marker.
  - «`:assoc-handler` IS-A `:assoc-fn` (slot types) AND IS-A
    `:assoc-empty` (empty-map seed)». Type-shape + initial-value.
- ❌ **Глаголы** (this **DOES** A AND **DOES** B):
  - «`:_my-handler` parses AND validates AND writes» — это поведение
    в три шага, оно собирается через `:if`/`:cond`-скреп +
    ref-биндинги в `:args` (см. `graphden-fn-refactor` § «handler =
    parse → validate → apply»), НЕ через MI.

**Бинарный slot-collision тест (то, что sync будет проверять
автоматически)**: пусть `own-slots(P)` — set of slot-names, которые
parent `P` ВНОСИТ в своё `:fn-slots`-junction. MI допустим iff:

```
own-slots(A) ∩ own-slots(B)  ⊆  {slots that child OVERRIDES via :args}
```

Если пересечение не покрыто override'ами — sync упадёт на slot-
collision check (`composition.validation`). Если ты «покрываешь
override'ами потому что семантически парятся, но я зажму обе» —
**это смесь**: ты уже не описываешь shape, ты режешь конфликт. В
таком случае переписывай на single-parent + composition.

**Эвристика отказа `MI экономит запись»**: если выбор между
single-parent + 5 ref-биндингов **vs** двух parents без ref-биндингов
делается ради **краткости** — MI не выбор. MI описывает что child IS,
не строит behavior через короткий путь.

**Sanity check для существующей MI fn-def — через БД** (см. § 5 ниже —
БД лучше grep'а для этого):

```clojure
;; В REPL — реальные slot-имена fn-def'а после MI-merge:
(let [fn-id (:id (first (sp/query-entities storage :fn {:name "my-mi-fn"})))]
  (->> (sp/query-entities storage :fn-slot {:fn-id fn-id})
       (map (fn [fs]
              (let [slot (sp/read-entity storage :slot (:slot-id fs))
                    type-fn (sp/read-entity storage :fn (:type-fn-id slot))]
                {:slot-name (:name slot)
                 :slot-type (:name type-fn)
                 :from-parent? (not= (:fn-id fs) fn-id)})))))
;; Каждый слот должен быть объясним: "это от parent A" / "от parent B"
;; / "own override". Слот «не пойми откуда» → parent внёс лишнего →
;; MI не оправдан, разбирай.
```

**Common MI-в-сегодняшнем-графе примеры** для калибровки чутья:

| Fn-def | Parents | Почему MI |
|---|---|---|
| `:postgres-storage-impl` | `[:Storage]` (singleton) | type-row impl pattern; type-row сам устанавливает protocol-обязательства, child привязывает их к pg-query |
| `:authed-get-route` | `[:get :auth-required]` | route-shape + middleware (две оси) |
| `:resolve-versioned-rows` | `[:filter :ResolveVersionedRowsInput]` | поведение filter'а + type-row контракт входа (`:version-id-field` etc.) |

## 5. EDN-grep vs БД-query — методология

EDN — это **исходник**, БД (после `bb rebuild` / `bb deploy`) — это
**синхронизованный граф**. У них разный уровень видимости, и для
разных вопросов правильный инструмент разный.

### 5.1 Когда БД лучше grep'а

| Вопрос | Почему БД | EDN-grep промахнётся |
|---|---|---|
| «Где используется fn-def `X`?» | `:binding :ref-fn-id X` + `:binding-list-item :ref-fn-id X` | EDN не видит synthetic `_anon-*` ref'ы, которые parser создал из inline `{:parent :X …}` форм |
| «Какие fn-def'ы дублируются по shape?» | `(group-by :anonymous-hash)` — shape-dedup сделан БД-уровне | EDN видит две `{:input {:a :int}}` — но не знает что они shape-deduped в одну fn-row |
| «Какие slot'ы реально у fn-def `X` после MI?» | `:fn-slot {:fn-id X}` (полный набор после parent BFS) | EDN видит только OWN slots, не унаследованные |
| «Где используется тип `:jsonb`?» | `(query-entities :slot {:type-fn-id :jsonb-id})` | EDN видит `:type :jsonb` в declarations, но не computed types (когда type-checker вывел shape) |
| «Какие refinement'ы по факту фигурируют в graph?» | `(query-entities :fn {})` filter by `:base-fn-id` | EDN видит declarations, но не runtime-эффективный набор |
| «Что у fn-def `X` за computed return-type?» | rich-types registry в JVM — не сериализовано в БД, но из REPL виден | EDN видит DECLARED return-type, не INFERRED |

### 5.2 Когда EDN-grep правильный

| Вопрос | Почему EDN |
|---|---|
| «Где declared `:type :jsonb`?» (для сужения) | Источник правки — EDN; нужно найти DECLARATIONS, не runtime-эффект |
| «Где docstring / `:description` упоминает X?» | БД хранит description, но grep по тексту EDN читабельнее |
| «Куда вписать новую fn-def?» (namespace pick) | Нужно посмотреть как соседи структурированы — EDN с комментариями понятнее БД-dump'а |
| «Какой shape у inline literal в `:value`?» | Литералы хранятся как JSONB — EDN читать проще |

### 5.3 Идиоматический workflow для поиска / правки

1. **Запрос к БД** (REPL `sp/query-entities`, `curl /api/graph/entities`,
   `pg-query` базовой fn в живом графе). Получи список fn-name'ов /
   fn-id'ов.
2. **Грепни по fn-name в EDN** — `grep -rE ":name :the-name\b"
   resources/packages` чтобы найти исходник.
3. **Правь EDN**, делай `bb rebuild`.
4. **Verify через БД** — повтори тот же query и убедись, что результат
   изменился как ожидалось.

### 5.4 Практические запросы

```clojure
;; ── В REPL ────────────────────────────────────────────────────────
;; Из живой системы (`bb repl` подключился к dev) или через test:
(require '[graphden.storage.protocol.core :as sp])
(def storage (-> integrant.repl.state/system :db/versioned))

;; (a) Все ref'ы на :equal? — где и в каком слоте:
(let [equal?-id (:id (first (sp/query-entities storage :fn {:name "equal?"})))]
  (->> (sp/query-entities storage :binding {:ref-fn-id equal?-id})
       (map (fn [b]
              {:owner (-> (sp/read-entity storage :fn (:fn-id b)) :name)
               :slot  (-> (sp/read-entity storage :slot (:slot-id b)) :name)}))))
;; → [{:owner "_bearer-equals-env?" :slot "a"} …]

;; (b) Все fn-row с одинаковым shape (структурные дубликаты):
(->> (sp/query-entities storage :fn {})
     (filter :anonymous-hash)
     (group-by :anonymous-hash)
     (filter (fn [[_ fns]] (> (count fns) 1))))
;; → пусто = shape-dedup отработал; иначе — баг в parser'е

;; (c) Все slot'ы типа :jsonb (потенциально слишком широкие):
(let [jsonb-id (:id (first (sp/query-entities storage :fn {:name "jsonb"})))]
  (->> (sp/query-entities storage :slot {:type-fn-id jsonb-id})
       (map (fn [s]
              {:slot-name (:name s)
               :owners (->> (sp/query-entities storage :fn-slot {:slot-id (:id s)})
                            (map #(-> (sp/read-entity storage :fn (:fn-id %)) :name)))}))))
;; → группированно по slot-name; смотри лишние widely-shaped declarations
```

```bash
# ── Через curl + jq ───────────────────────────────────────────────
AUTH=Bearer $AUTH_TOKEN  # if /api/graph/entities is auth-required

# (a) Все fns с заданным name-prefix:
curl -s http://localhost:8080/api/graph/entities -H "Authorization: $AUTH" \
  | jq '.fns | map(select(.name | startswith("_secret-")))'

# (b) Композированные fn-def'ы, у которых пусто parent-ids — кандидаты
#     на type-row OR base-fn (по impl-hash отличить):
curl -s http://localhost:8080/api/graph/entities -H "Authorization: $AUTH" \
  | jq '.fns | map(select((.parent_ids == null or (.parent_ids | length == 0))
                          and (.impl_hash == null)
                          and (.name != null)))'
# → type-rows (base-fn'ы имели бы impl_hash; composed имели бы parent_ids)
```

```clojure
;; ── Через :pg-query base-fn (если хочется выполнять из самого графа) ──
;; В REPL:
(exec/execute-by-name *context* "pg-query"
                      {:hsql {:select [:name]
                              :from [:fn]
                              :where [:and
                                      [:= :impl_hash nil]
                                      [:is :parent_ids nil]
                                      [:not= :name nil]]}})
;; → список type-row names
```

### 5.5 Когда БД ещё не в нужном состоянии

Если вы только что добавили fn-def в EDN, БД его ещё не видит до
`bb rebuild` / `bb deploy`. Декларативный sync **не удаляет** строки,
выпавшие из EDN — они копятся в dev-БД. Поэтому:

- Для **поиска мёртвого кода** (что в БД, но не в EDN) — нужен
  `bb deploy` (truncate + clean sync), не `bb rebuild` (см.
  `graphden-fn-refactor` §7).
- Для **поиска что-в-EDN-но-сломано** — `bb rebuild` достаточен.
- Для **production-debug** — БД production-сервера, БЕЗ rebuild'а
  (его делают только при деплое — НИКАКОГО `bb rebuild` против prod).

## 6. Порядок проверки существующих пакетов

```bash
# 1. Reachability — есть ли мёртвый код?
clojure -M:dev tools/reachability_audit.clj 2>&1 | grep -A20 'Unreachable COMPOSED'

# 2. Слишком широкие типы в slot declarations:
grep -rEn ':type :jsonb|:type :any' resources/packages --include='*.edn'

# 3. Длинные inline unions — кандидаты на alias:
grep -rEnB1 ':type \[:union :null :' resources/packages --include='*.edn' | head -20

# 4. Длинные base-fn impls — кандидаты на §3 этого скилла:
# (см. §3.2 выше — Python-script)

# 5. anonymous fn-def с явно проставленным `_anon-…` именем — bug:
grep -rE ':name :_anon-' resources/packages --include='*.edn'

# 6. Public-named fn-def с одним use-site — кандидат на `_`-private:
# (требует analyzed reachability + grep'а по `:parent :X` / `:ref X`;
#  делается интерактивно, не по checklist'у)
```

## 7. Тесты для нового / правленого пакета

**Tests для security-critical fn'ов** (см. `graphden-code-quality` §12)
— обязательны как regression sentinel.

Пример (этой сессии): `:constant-time-equal?` добавлен → `test/graphden/
packages/core/logic_test.clj` создан с тестом на:
- matching strings → true,
- non-matching → false (mismatch at first / last / length boundary),
- nil / non-string → false (отличается от `:equal?` поведения).

**Pattern**: slurp+eval `impls.clj` через loader's `load-module-impls`
(см. `concurrency_test.clj` / `logic_test.clj` / `refinements_test.clj`
для шаблона). Это unit-уровень — не нужен полный bootstrap.

**Когда unit'а недостаточно** — добавь behavioural тест через
`bootstrap-crud-graph-from-golden!` (см. `executor/compile-packages-
test.clj` / `refinements_test.clj`). Драйверит fn-def через executor
по реально synced graph.

## 8. Workflow

### 8.1 Новый base-fn

1. Перед написанием — пройди user-composability test (`graphden-fn-
   refactor` §3) — может, это композиция, а не base-fn.
2. Если всё-таки base-fn: пиши impl минимально (1-2 строки тела),
   объяви тип в `fns.edn` (узкий тип, см. §1; alias если повторяется,
   см. §2).
3. Если security-critical (любой compare-with-secret, любой `:secret
   T` потребитель) — пиши test-sentinel (см. §7).
4. `bb rebuild` → `bb verify` → smoke.

### 8.2 Новый fn-def

1. Реши: named (public) / `_`-private / inline (см. §4).
2. Объяви через `:parent <p>` (для одного родителя) или
   `:parents [a b]` для MI — НО только когда §4.3 binary-test
   проходит. По умолчанию single-parent + ref-биндинги в `:args`.
3. Сузь типы slot'ов (см. §1); используй alias если структура
   повторяется (см. §2).
4. Если ≥ 4-5 ref-bindings → подумай о decomposition (`graphden-fn-
   design` §7).
5. `bb rebuild` → smoke. **Verify через БД** (см. §5.3 шаг 4) —
   повтори ту же query, что использовалась для поиска, и убедись
   что результат изменился ожидаемо.

### 8.3 Audit существующих пакетов

1. **Baseline** — §0 sanity + reachability audit + список slow tests.
2. **Сканируй по §6** — сколько кандидатов на каждый вид правки.
   **Для structural-вопросов используй БД** (см. §5.1), не grep.
3. **Резюме** перед правками — приоритет: security/correctness >
   widening types > dead code > naming hygiene > alias unification >
   MI-clean-up.
4. **Правь per-target, per-commit** — каждое значимое изменение —
   отдельный commit. Применяй `graphden-code-quality` §13.3 commit
   rules.
5. **`bb rebuild` + `bb verify` + focused tests** после каждого
   commit'а. **Verify через БД** что правка достигла цели.
6. **Финальный sweep** — `bb test` или `bb ci`.

## 9. Анти-паттерны

- **«Сужу типы» без проверки runtime-семантики.** Slot type'а
  `:jsonb` → `:keyword-map` — добавь breakpoint / REPL-проверку
  (см. §5.4): binding'и в самом деле всегда keyword-keyed? Сужать
  без verify = ломать runtime.
- **Alias ради alias'а.** Имя нужно только когда оно добавляет
  СМЫСЛ. `:int-or-text` хуже чем inline `[:union :int :text]` —
  потому что для тех 2 use-site чтение `[:union :int :text]`
  понятнее.
- **`_`-prefix на public-API fn-def.** Если кто-то ссылается из
  другого пакета — это уже не private. Promote'ни (см.
  `graphden-fn-design` §1).
- **Inline composite type для одного-use-site, который ВДРУГ был
  скопипащен в двое-three места.** → Переименуй в `:_some-shape`
  alias (или regular type-row) и используй по имени. Иначе
  shape-hash дедупит, но семантика спрятана.
- **Удаление dead fn-def без grep'а по комментариям.** Имя может
  быть упомянуто в docstring'е соседней fn-def — комментарий
  устареет.
- **«Перепишу всё на refinement'ы»** — `:int → :positive-int`
  везде. Refinements добавляют runtime check (`:ensure-X` throw on
  violation); если поток данных не контролируется на входе,
  получишь runtime crash. Сузай типы там, где входной контракт
  явный (admin-formy, parsed-bodies); НЕ для transit-types между
  internal fn'ями.
- **MI ради «склейки behavior».** «Я хочу что-fn делала и X и Y» —
  это композиция шагов, не axes-of-shape (см. §4.3). MI описывает
  что child IS-A, не то что он DOES. Переписывай через `:if`/`:cond`
  + ref-биндинги.
- **MI вместо single-parent + ref-биндингов «ради краткости».**
  Если выбор «двух parents без `:args`» vs «одного parent + 5
  `:args` биндингов» делается ради компактного fns.edn — это
  иллюзия экономии. Sync-time slot-collision check ловит часть
  таких смесей, но не все — некоторые проползают и читатель видит
  слоты «откуда-то».
- **Grep по EDN там, где нужна БД.** Поиск «где используется fn-def
  `X`» через grep по `:parent :X` промахивается на synthetic
  `_anon-*` refs (parser создал из inline `{:parent :X …}` форм).
  Аналогично: grep по shape промахивается на `anonymous-hash`-
  deduped fns. Для structural-вопросов используй БД (см. §5.1).
- **БД-query без `bb rebuild` после правки EDN.** Декларативный sync
  привязывает EDN ⇄ БД только при rebuild'е. Правка → ОБЯЗАТЕЛЬНО
  rebuild → verify через БД. Без rebuild'а query покажет старое
  состояние, легко поверить «не сработало».

## 10. Связи с другими скиллами

- **`graphden-fn-refactor`** — детали по декомпозиции impls (§3 user-
  composability test, §4 рецепт). Этот скилл вызывает его для
  конкретики.
- **`graphden-fn-design`** — детали по naming / MI / namespaces /
  `:const`-обёртки. Этот скилл вызывает его для конкретики.
- **`graphden-code-quality`** — Clojure src/ — sister-скилл. Если
  правка пакета тащит за собой src-change (новая impl нуждается в
  helper'е в `src/`, или `system/core` нуждается в seeding) —
  переключайся.
- **`graphden-repl`** — отладка гипотез по живому графу.
  Используется ВСЕГДА при правке runtime-важных fn'ов.
- **CLAUDE.md** + **docs/PACKAGES.md § Composition Best Practices** —
  первоисточник проектных принципов. Этот скилл — operational арм.

## 11. Что считается «не докопаться» (для пакетного слоя)

Финальный self-check перед закрытием:

- [ ] `bb check` зелёный (0 warnings)
- [ ] `bb rebuild` успешен, `bb verify` показывает синхронные секции
- [ ] Reachability audit — нет НОВЫХ unreachable composed fn-defs
- [ ] Все `:jsonb` / `:any` в новых declarations ОБОСНОВАНЫ (§1.1)
- [ ] Все длинные inline unions либо использованы 1-2 раза, либо
      получили alias имя (§2.2)
- [ ] Все новые base-fn impls прошли user-composability test (§3)
- [ ] Все security-critical impls покрыты regression sentinel'ом
      (§7)
- [ ] Каждая `:parents [A B]` декларация прошла §4.3 binary-test
      (axes-of-shape, не behavior-mix) И REPL-проверку реальных
      slot-имён (нет «откуда это?»-слотов)
- [ ] Для structural-вопросов (use-sites, дубликаты, computed
      shapes) был использован БД-query (§5.1), не EDN-grep
- [ ] Каждая значимая правка верифицирована через БД (§5.3 шаг 4) —
      повторённый query показывает ожидаемое НОВОЕ состояние
- [ ] Каждый новый named fn-def оправдан reuse'ом или domain-
      сущностью (§4.1, прав. 1)
- [ ] Каждый `_`-private fn-def оправдан (§4.1, прав. 2)
- [ ] Каждый commit — отдельная concept-value-unit (см.
      `graphden-code-quality` §13.3)
