---
name: graphden-packages-quality
description: Качество `resources/packages/**/{fns.edn,impls.clj}` — узкие типы и type-aliases, минимальные base-fn impls, корректное использование именованных vs анонимных fn-defs. Применяй при ЛЮБОМ касании пакетного слоя (новые типы / impls / fn-defs пишем сразу чисто) И как explicit-проверку существующего ("пройдись по пакетам", "сузь типы", "почисти fns.edn", "проверь impls на лишнюю логику"). Триггеры — фразы вроде "fn-def", "fns.edn", "impls.clj", "base-fn", "тип слишком широкий", ":jsonb", ":any", "type alias", "длинный union", ":nullable-*", "именованный или анонимный", "extract в helper", "impl содержит логику", "MI vs single-parent", "namespace для fn", "переиспользование fn-def", "доступно для админа", "должно быть конкретным типом". SKIP для: чисто Clojure src/test — кода (→ `graphden-code-quality`), pure REPL-debug гипотез (→ `graphden-repl`), frontend (.js/.css) — отдельный скилл.
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

## 5. Порядок проверки существующих пакетов

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

## 6. Тесты для нового / правленого пакета

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

## 7. Workflow

### 7.1 Новый base-fn

1. Перед написанием — пройди user-composability test (`graphden-fn-
   refactor` §3) — может, это композиция, а не base-fn.
2. Если всё-таки base-fn: пиши impl минимально (1-2 строки тела),
   объяви тип в `fns.edn` (узкий тип, см. §1; alias если повторяется,
   см. §2).
3. Если security-critical (любой compare-with-secret, любой `:secret
   T` потребитель) — пиши test-sentinel (см. §6).
4. `bb rebuild` → `bb verify` → smoke.

### 7.2 Новый fn-def

1. Реши: named (public) / `_`-private / inline (см. §4).
2. Объяви через `:parent <p>` (или `:parents [a b]` для MI).
3. Сузь типы slot'ов (см. §1); используй alias если структура
   повторяется (см. §2).
4. Если ≥ 4-5 ref-bindings → подумай о decomposition (`graphden-fn-
   design` §7).
5. `bb rebuild` → smoke. Если тест не покрывает — пиши hello-world.

### 7.3 Audit существующих пакетов

1. **Baseline** — §0 sanity + reachability audit + список slow tests.
2. **Сканируй по §5** — сколько кандидатов на каждый вид правки.
3. **Резюме** перед правками — приоритет: security/correctness >
   widening types > dead code > naming hygiene > alias unification.
4. **Правь per-target, per-commit** — каждое значимое изменение —
   отдельный commit. Применяй `graphden-code-quality` §13.3 commit
   rules.
5. **`bb rebuild` + `bb verify` + focused tests** после каждого
   commit'а.
6. **Финальный sweep** — `bb test` или `bb ci`.

## 8. Анти-паттерны

- **«Сужу типы» без проверки runtime-семантики.** Slot type'а
  `:jsonb` → `:keyword-map` — добавь breakpoint / REPL-проверку:
  binding'и в самом деле всегда keyword-keyed? Сужать без verify =
  ломать runtime.
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

## 9. Связи с другими скиллами

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

## 10. Что считается «не докопаться» (для пакетного слоя)

Финальный self-check перед закрытием:

- [ ] `bb check` зелёный (0 warnings)
- [ ] `bb rebuild` успешен, `bb verify` показывает синхронные секции
- [ ] Reachability audit — нет НОВЫХ unreachable composed fn-defs
- [ ] Все `:jsonb` / `:any` в новых declarations ОБОСНОВАНЫ (§1.1)
- [ ] Все длинные inline unions либо использованы 1-2 раза, либо
      получили alias имя (§2.2)
- [ ] Все новые base-fn impls прошли user-composability test (§3)
- [ ] Все security-critical impls покрыты regression sentinel'ом
      (§6)
- [ ] Каждый новый named fn-def оправдан reuse'ом или domain-
      сущностью (§4.1, прав. 1)
- [ ] Каждый `_`-private fn-def оправдан (§4.1, прав. 2)
- [ ] Каждый commit — отдельная concept-value-unit (см.
      `graphden-code-quality` §13.3)
