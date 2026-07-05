---
name: graphden-code-quality
description: Качество Clojure-кода в `src/` и `test/` — DRY, мёртвый код, декомпозиция больших функций/файлов, N+1, безопасность, nil-safety, скорость тестов, разумное покрытие. Применяй при ЛЮБОМ касании Clojure-кода (новый код пишем сразу чисто, чтобы потом не переделывать) И как explicit-проверку существующего кода ("пройдись по проекту", "почисти `src/`", "пройдись ещё раз — что ещё можно улучшить"). Триггеры — фразы вроде "рефакторинг", "почисть", "DRY", "большой файл", "большая функция", "мёртвый код", "дубликаты", "оптимизируй", "N+1", "ускорь тесты", "флака", "test flake", "повысь качество", "security", "альфа-релиз", "перед релизом". SKIP для: чисто frontend-вопросов (.js/.css/.html — отдельные скиллы), вопросов про `fns.edn`/`impls.clj` (→ `graphden-packages-quality`), pure REPL-debug гипотез (→ `graphden-repl`), business-feature implementation.
---

# graphden-code-quality — чистый Clojure src/ и test/

Задача этого скилла: **открыть код перед посторонним разработчиком и не
поморщиться**. Это не «накатывать линтеры до зелёного» (`bb check` всё
равно прогоняем), а ловить семантические проблемы, которые линтер не
видит: дубликаты, N+1, простыни-функции, флаки, мёртвый код,
несимметричные защитные шапки.

**Применять и при письме нового кода, и как explicit-проверку
существующего.** Если новый код проходит этот скилл с первого захода —
не придётся возвращаться. Если зовут «пройдись по проекту» — это
explicit-rerun по тому же списку.

## 0. Sanity checks перед началом — что у нас сейчас

Перед тем как править существующее, сними отпечаток:

```bash
bb check                   # clj-kondo + splint + cljstyle, должен быть 0 warnings
bb test                    # должны быть зелёные; запомни slowest (kaocha profiling)
clojure -M:dev tools/reachability_audit.clj  # дерево достижимости fn-defs
```

Если `bb check` уже что-то ругается — закрывай ДО рефакторинга. Чужие
ошибки потом замаскируют твои.

## 1. Декомпозиция больших функций — порог 100 строк

**Функция ≥ 100 строк — обязательная проверка на split.** Не всегда
обязательно резать (см. §1.5 ниже), но обязательно ОБОСНОВАТЬ
оставление.

### 1.1 Как искать кандидатов

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

Регэксп пропускает `defrecord` / `defprotocol` — это правильно, они
часто содержат много protocol-method declarations, что НЕ refactor target.

### 1.2 Как резать — правило name-the-phases

Подходящие швы — это **именуемые фазы** работы функции:

| Шов | Признаки |
|---|---|
| `parse`/`classify` | Разбор сырого входа в структурированную форму |
| `validate` | Проверки, возвращающие либо go-ahead, либо отказ-маркер |
| `compute`/`derive` | Чистая трансформация без I/O |
| `apply`/`write` | Сторонние эффекты (DB-запись, NOTIFY, log) |
| `finalize`/`respond` | Формирование возвращаемого значения для caller'а |
| `throw-*!` | Канонический ex-info — выносить если ≥2 каллера |

**Пример (этой сессии)** — `crud/entities/apply-create-core` был 106
строк = nested let + cond. Распилен на:

- `humanise-create-exception` (16 lines) — формат сообщения
- `try-create-or-error` (24 lines) — capability-gate + create-entity wrap
- `forward-rename-slot!` (10 lines) — Phase 6c side-effect
- `post-create-type-check-fn-id` (10 lines) — fn-id resolution
- `verify-post-create-or-rollback!` (20 lines) — type-check + rollback
- `apply-create-core` (16 lines) — orchestrator

Каждый helper имеет имя, docstring (1-3 lines), независимо читается.
Сама `apply-create-core` теперь рассказывает story: «create → maybe
rename-slot → maybe rollback».

### 1.3 Когда у helper'а появляются 6+ параметров

Если extracted helper'у приходится передать > 5 аргументов, значит швы
не там. Варианты:

- Объединить связанные параметры в map (`{:storage :ctx :row}` → один
  `ctx`-map).
- Найти другой шов — может, helper включает в себя ещё одну фазу,
  которая закрывает половину параметров.

### 1.4 Когда НЕ резать — линейный pipeline через `let`

Если функция — линейная цепочка преобразований (`indexes →
sorted → reduced`), и каждое следующее значение реально нужно
следующему, дробить на helper'ы делает её ХУЖЕ. Пример:
`executor/compile/lookups/build-lookups` (104 строки) — это
linear-let constructing 8 index maps from raw rows. Распил создал
бы 8 helper'ов с 3-4 параметрами каждый, читаемость упала бы.

Решение: оставить как один let, но убедиться что каждая binding имеет
ОСМЫСЛЕННОЕ имя.

### 1.5 Когда можно оставить большую функцию

- Линейный pipeline через `let` (§1.4)
- `defrecord` / `defprotocol` с protocol-method declarations
- Data-heavy definitions (schema declarations, big enum value maps) —
  `extend-builder`, `value-kinds` и т.п.
- Алгоритм с инвариантом (`letfn` со взаимной рекурсией, shared
  cycle-set) — нерезаемое тело алгоритма

В docstring или в комментарии перед функцией явно сказать почему НЕ
режем.

## 2. Декомпозиция больших файлов — порог 1000 LOC

**Файл > 1000 строк** — повод задуматься. Не каждый такой файл
обязательно резать, но проверь:

1. Есть ли в файле > 1 темы? Если да — режь по темам.
2. Есть ли семантически-обособленная группа функций, которой можно
   дать имя? Если да — выноси в отдельный namespace.

**Пример good split** (исторический): `crud/entities.clj` → `crud/
entities.clj` + `crud/request.clj` + `crud/validation.clj` + …

**Не режь файл, если:**

- Все функции — одна логическая ответственность (`types/check.clj` —
  type-checker, 2858 LOC, но это ОДИН алгоритм).
- Декомпозиция оставит много cross-references — плохой knife.

## 3. DRY — поиск дубликатов

### 3.1 Одинаковые блоки `(throw (ex-info …))`

```bash
grep -rEn 'ex-info\s+\(str\s+"' src --include='*.clj' | head -20
```

Если ОДИН и тот же ex-info `:type` бросается из ≥2 мест с одинаковой
data-shape — лифти в helper `(defn- throw-<X>! [arg] …)`.

Пример (этой сессии): `executor/compile_runtime.clj` бросал
`:execution-error/fn-not-found` из `execute` и `make-single-arg-
callable`. Helper `throw-fn-not-found!` снял дубль и упростил
let-binding: `(or (get reg fn-id) (throw-fn-not-found! fn-id))`.

### 3.2 Inline let-bound helper, повторяющийся между двумя ветками cond

Если две ветки `cond`/`if` делают `(let [eff (compute-eff) outcome
(->> base (stamp-touched-secret …) (redact-outcome …))] (write …)
(unregister …) outcome)` — это `finalize-X` helper.

Пример: `crud/fn-execution/apply-execute` имел два почти-одинаковых
финализатора для `:succeeded` и `:failed`. Лифт в
`finalize-inline-outcome` упростил cond.

### 3.3 Одинаковая последовательность let-bindings внутри одной функции

Если внутри одной функции ОДНА и та же тройка `let`-bindings (или
больше) вычисляется ДВАЖДЫ — это баг или забытый рефакторинг.

Пример: `types/check/check-fn-def!` вычислял `parent-list +
type-row-fields + parent-args` дважды — один раз для pre-pass
валидаторов, второй раз для inference body. Второй блок был полная
копия первого + одно cosmetic `cond` → `if`. Лифт в общий outer
`let` снял 12 lines.

### 3.4 Помощник для cond-tree с одинаковой формой результата

Если в большом `(cond …)`-дереве каждая ветка возвращает map с
одинаковыми ключами (`{:type T :value V}`), а различия только в
значениях — extract в classifier:

```clojure
(defn- binding-info-entry
  "..."
  [b-form]
  (cond
    (rename-binding? b-form)   {:type … :value nil}
    (value-binding?  b-form)   {:type … :value … :value-present true}
    …))
```

Пример: `types/check/bindings-info-for-rule` имела 80 lines `cond`
inside an `into {} (map …)`. Extracted `binding-info-entry`.

## 4. Мёртвый код — `tools/reachability_audit.clj`

Запуск:

```bash
clojure -M:dev tools/reachability_audit.clj
```

Печатает «Unreachable COMPOSED fn-defs» — это кандидаты на удаление.
Перед удалением **проверь грепом** что имя не используется
где-то ещё (комментарий, docstring, `requiring-resolve`-строка). Если
есть только декларативные ссылки — удаляй и оставь комментарий
«удалено, замещено X» в месте.

**Type-rows и base-fn'ы в «Unreachable» — НЕ мёртвый код.** Это
словарь языка: подмножество используется приложением, остальное
ждёт пользовательских fn-def'ов.

Для `src/`-кода аналога нет — `clj-kondo --unused-private-vars`
ловит unused private vars, но не cross-namespace. Если подозрение
на мёртвый src-код — грепни `(<symbol>` по проекту, отсей
комментарии.

## 5. Сложная / непонятная логика — что искать

### 5.1 Глубокий nested let (> 4 уровней)

```bash
grep -rEnB1 'let\s+\[.*\n.*let\s+\[.*\n.*let\s+\[.*\n.*let' src --include='*.clj'
```

Если найдено — рассмотри extraction: каждый внутренний `let` обычно
носит имя «делаю X, потом Y».

### 5.2 cond-tree с > 6 веток

Каждая ветка должна иметь docstring-комментарий, ИЛИ всё дерево
должно быть простым диспетчем по shape (см. §3.4). Если ни то ни
другое — это маркер «много неназванных кейсов».

### 5.3 Чрезмерное `(or x y)` для null-handling

```bash
grep -rEn '\(or\s+\(\.\S+\s+\S+\)\s+""' src --include='*.clj'
```

Здесь часто нужна обёртка `(str (.getMessage e))` — `str` коэрсит
nil в `""`, а двойной `or` нечитаем.

### 5.4 Cyclic deps / `requiring-resolve` хаос

```bash
grep -rEn 'requiring-resolve' src --include='*.clj' | wc -l
```

`requiring-resolve` — это **valid escape hatch** для размыкания
циклических зависимостей (executor ↔ registry), но если их > 10
по проекту — структура зависимостей выкручена.

## 6. N+1 queries — DB-access patterns

```bash
echo '=== loop over sp/read-entity ==='
grep -rEC1 'doseq\s+\[.*\n.*sp/read-entity' src --include='*.clj'

echo '=== map over sp/query-entities ==='
grep -rEn '\(map\s+#\(.*sp/query-entities' src --include='*.clj'
```

Если найдено — заменить на batch API:

- `sp/read-entities` (batch read by ids)
- `sp/query-entities` с `:id` IN clause

**Не каждый doseq над `sp/`** — N+1. Если итерация по 4-5 entity-types
(сам список фиксированный мал) — это не N+1, это итерация по словарю.

## 7. Безопасность

### 7.1 Сравнение секретов — constant-time

```bash
grep -rE ':equal\?' resources/packages/web/ring-adapter resources/packages/app
```

Bearer-token / HMAC-tag / vault-token сравнение должно идти через
`:constant-time-equal?` (`MessageDigest/isEqual`), а не `:equal?`
(`=`). `=` short-circuit'ит на первом несовпадающем байте — timing-
channel, утечка по одному байту за раунд probing'а.

Для нового кода с секретами: всегда используй `:constant-time-equal?`.

### 7.2 SQL injection / shell injection

```bash
grep -rEn '\(jdbc/execute!\s+\w+\s+\(str\s+' src --include='*.clj'  # → должно быть пусто
grep -rEn 'shell\s+\(str|sh\s+\(str' src --include='*.clj'           # → должно быть пусто
```

В Graphden HoneySQL покрывает все известные пути; raw `(str ...)` в
JDBC — повод глубоко покопать.

### 7.3 Read-string / eval на user-input

```bash
grep -rEn 'read-string|eval\s+\(' src --include='*.clj'
```

`packages/loader.clj`'s `read-string` читает CLASSPATH-resource — OK
(supply-chain, не runtime). Другие читать не должно.

### 7.4 SQL — HoneySQL по умолчанию, raw-string только по carve-out

**Правило**: каждый новый JDBC-запрос строится через `honey.sql/format`
по data-map'е, не через `(str "SELECT … " var " …")`. Это уже
сегодня доминирующий стиль в `storage/postgres/*.clj` (90%+ сайтов);
любой новый raw-string в `src/` — повод обосновать carve-out.

**Зачем:**

- **Safety**: HoneySQL автоматически параметризует значения
  (`?`-placeholders), исключает identifier-injection через
  user-supplied table-name. Raw `(str "\"" jt "\"")` требует
  ручного escape'а — легко забыть.
- **Composability**: query — data; шаги (where, order-by) могут
  собираться `cond->` / `merge` без string-concat акробатики.
- **Consistency**: codebase уже HoneySQL-heavy; новые raw-сайты
  ломают навигацию и стиль ревью.
- **Refactor-friendly**: column rename = keyword edit, не grep+sed
  по SQL-фрагментам.

**Когда raw-string ОПРАВДАН** (carve-outs):

| Carve-out | Пример | Reason |
|---|---|---|
| PG built-in RPC | `SELECT pg_notify(?, ?)`, `pg_try_advisory_lock(?)` | HoneySQL покрытие функций PG-RPC слабое; raw — идиоматично + 1 строка |
| DDL edges | `CREATE TYPE … AS ENUM (…)`, динамические `CREATE INDEX` имена | HoneySQL DDL coverage частичное; ENUM с runtime-values неудобно |
| Однострочный SQL без runtime-данных | `"SELECT pg_advisory_unlock_all()"` | Нечего параметризовать; HoneySQL overhead = чистый шум |

**Detection:**

```bash
# Raw SQL стрингов с runtime-данными:
grep -rEnB1 '\(str\s+"(SELECT|INSERT|UPDATE|DELETE|CREATE|ALTER|VALUES|WITH)\b' src --include='*.clj' | head

# JDBC execute с явно-строковым query (без HoneySQL):
grep -rEn 'execute!.*\[\s*"(SELECT|INSERT|UPDATE|DELETE)\b' src --include='*.clj' | head
```

Каждое попадание — либо carve-out из таблицы выше (с inline-
комментарием почему), либо migration-кандидат.

**HoneySQL gotchas:**

- **PG reserved words** (`user`, `order`, `group`, `from`, …) — entity
  names могут попасть в table-arg. Без quoting `UPDATE user …` упадёт.
  Решение: `(sql/format … {:quoted true})` для query-builder'ов,
  принимающих entity-name снаружи. Внутри-storage queries с фиксированными
  identifier'ами quoting не нужен. См. `build-batch-update-sql` в
  `storage/postgres/crud.clj`.
- **Batch INSERT через `jdbc/execute-batch!`** — JDBC API: один SQL
  template + N param-sets. HoneySQL даёт `[sql & params]`, для
  execute-batch берётся `(first formatted)` — SQL с `?`-placeholders.
  Pattern: `insert-junction-sql` в `storage/postgres/junction.clj`.
- **Per-cell casts в `VALUES (...)`** — для PG-specific type coercion
  каждой ячейки используй `[:cast value :uuid]`. HoneySQL рендерит как
  `CAST(? AS UUID)` — семантически идентично `?::uuid`.
- **Derived table с column-aliases**: `:from [[{:values …}
  [:v {:columns [:id :col1 :col2]}]]]` → `FROM (VALUES …) AS v(id,
  col1, col2)`. Pattern: `build-batch-update-sql`.

**Migration reference** (для аналогичных задач):

- `storage/postgres/junction.clj` (6 raw-сайтов → HoneySQL,
  preserved `execute-batch!`)
- `storage/postgres/crud.clj build-batch-update-sql` (UPDATE FROM
  VALUES + per-cell casts + RETURNING)

## 8. Nil safety — Throwable/.getMessage и аналоги

Java-API контракт: `.getMessage` может вернуть null. Если результат
идёт в user-facing `:error` / `:reason` / message field — оберни в
`str`:

```clojure
;; BAD — JSON выдаст null, UI отрендерит "rejected, no reason"
{:reason (Throwable/.getMessage e)}

;; GOOD — пустая строка, UI хотя бы видит что-то
{:reason (str (Throwable/.getMessage e))}
```

Если идёт ВНУТРЬ `(str "prefix " (.getMessage e))` — внешний `str`
уже коэрсит nil, **inner str избыточен** (splint ругнётся).

Та же проверка на `:cause`, `:caused-by` поля. На `ex-data`-payload
— не критично (`null` сериализуется и парсится корректно).

## 9. Тесты — флаки и анти-паттерны

### 9.1 Фиксированный `Thread/sleep` перед assert — флака под параллельной нагрузкой

```bash
grep -rEn 'Thread/sleep\s+[1-9][0-9]{2,}' test --include='*.clj' | head
```

`(Thread/sleep N)` перед `(is (>= @iters K))` или подобной проверкой
— под parallel-test CPU contention поток может НЕ успеть выполнить
итерации в N ms, тест ляжет.

Заменять на poll-with-deadline:

```clojure
(let [deadline (+ (System/currentTimeMillis) 2000)]
  (while (and (< @iters K)
              (< (System/currentTimeMillis) deadline))
    (Thread/sleep 20)))
```

### 9.2 `(is true)` / `(is (= 1 1))` — пустые assertions

```bash
grep -rEnB1 '\(is\s+true\)' test --include='*.clj'
```

Если функция «не должна бросать» — её вызов САМ ПО СЕБЕ это проверит
(если бросит — kaocha сообщит). `(is true)` не добавляет ничего.

Заменять на observable check: `(is (nil? (get-X)))` после операции
которая не должна была ничего записать.

### 9.3 Дубликаты тестов между файлами

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

Истинные дубликаты — две функции тестирующие ОДНО (one is strict
subset). Удалить subset, оставить superset.

НЕ дубликат — тесты с одинаковым именем на разные ASPECT'ы
(`read-config-test` в `interface-test.clj` про unit-семантику,
`read-config-test` в `executor-runtime/core-test.clj` про
интеграцию). Их объединять не нужно — каждый покрывает свой
слой.

### 9.4 `with-redefs` на non-`^:dynamic` var вне `^:serial` ns

```bash
for f in $(grep -rl 'with-redefs' test --include='*.clj'); do
  head -3 "$f" | grep -q '\^:serial' || echo "  $f"
done
```

`with-redefs` модифицирует root binding (НЕ thread-local). В parallel
kaocha NS другие потоки видят рекдеф. Если NS не `^:serial`, флаки.
Решение: либо `^:serial` метa на ns, либо вынести тест в отдельный
`^:serial`-NS.

### 9.5 Quality of assertions — слабые `(is)` ничего не доказывают

Скилл §9.1-9.4 ловит ПУСТЫЕ assertions. §9.5+ ловит **зелёные тесты,
которые проверяют не то что заявляют**.

#### 9.5.1 Tautologies — `(is (= X X))`

```bash
# (is (= literal literal)) — обе стороны идентичны:
grep -rEn '\(is\s+\(=\s+(:?\w+)\s+\1\s*\)' test --include='*.clj'

# (is (= (f arg) (f arg))) — одинаковый вызов в обе стороны:
grep -rEn '\(is\s+\(=\s+(\([^)]+\))\s+\1\s*\)' test --include='*.clj'
```

Always green, проверяет НИЧЕГО. Удалить или заменить на конкретный
expected value.

#### 9.5.2 Лень: `(is (some? …))` / `(is (not= nil …))` где известен expected

```bash
# (is (some? (function-call ...))) — где можно проверить точное значение:
grep -rEn '\(is\s+\(some\?\s+\(' test --include='*.clj' | head -10
```

`(is (some? (read-entity ...)))` зелёный для любого non-nil — НЕ
гарантирует что данные правильные. `(is (= expected-shape (read-entity
...)))` ловит regression на shape change.

**Когда `(some? ...)` оправдан**:

- Проверяемое значение — opaque handle (UUID, future, atom) без stable
  representation
- Тест на «не упало» в инициализации (но тогда лучше `(is (nil?
  (init)))` — observable check)

#### 9.5.3 `(is (thrown? Exception ...))` без класса/regex

```bash
grep -rEn '\(is\s+\(thrown\?\s+Exception\b' test --include='*.clj' | head -10
grep -rEn '\(is\s+\(thrown\?\s+Throwable\b' test --include='*.clj' | head -10
```

`Exception` ловит ВСЁ — включая `NullPointerException` от опечатки в
test setup. Заменить:

- `(is (thrown-with-msg? ClassName #"specific msg" ...))` — точный
  контракт
- `(is (thrown? ClassName ...))` — конкретный класс (`ExceptionInfo`,
  `ArithmeticException`, etc.)

#### 9.5.4 Логика внутри `(is)` — `loop` / `if` / `cond`

```bash
grep -rEn '\(is\s+\((loop|if|cond|when|when-let|let)\b' test --include='*.clj' | head -10
```

```clojure
;; BAD — логика внутри is. Если loop bug'нул, "тест зелёный" может
;;       значить разные вещи.
(is (loop [n 0]
      (if (>= n 100) true (recur (compute n)))))

;; GOOD — логика ДО is, is проверяет результат.
(let [result (loop [n 0]
               (if (>= n 100) :done (recur (compute n))))]
  (is (= :done result)))
```

`when` / `when-let` особенно коварны: они возвращают nil когда
условие false, и `(is nil)` — это FAIL! Так что `(is (when X Y))` —
лишний слой.

#### 9.5.5 Множественные `is` в одном `testing` без явной связи

```bash
# `testing` с >3 `is` подряд — кандидат на дробление:
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

Если в `(testing "X" ...)` есть 4+ `is` НЕ-связанных проверок (разные
аспекты системы) — failure не показывает что сломалось, и rerun-after-
fix не понятен. Дроби на отдельные `testing` блоки.

#### 9.5.6 Тестируется impl, не contract

Маркер: тест ссылается на private symbols (`#'ns/private-fn`) или
проверяет внутренние data structures.

```bash
grep -rEn "#'\S+/_?[a-z]" test --include='*.clj' | head -10
```

```clojure
;; BAD — testing private impl
(is (= 42 (#'my.ns/internal-counter-state ctx)))

;; GOOD — testing observable contract
(is (= 42 (public-api/get-counter ctx)))
```

Tests of private impl ломаются при refactor'ах, которые НЕ ломают
поведение — это false negative debt.

#### 9.5.7 Test names — описывают что проверяется

```bash
# Имена test'ов вида test-1 / my-test / works:
grep -rE '^\(deftest\s+(test-?[0-9]+|my-?test|works?|test|t)\b' test --include='*.clj'

# deftest без -test suffix:
grep -rE '^\(deftest\s+[a-z]\w*[^-][^t]\b' test --include='*.clj' | grep -v '\-test\b' | head -5
```

Хорошее имя: `<concept>-<scenario>-test` или `<concept>-<expected-
behavior>-test`. Пример: `register-base-fns-handles-empty-defs-map-
test` лучше чем `test-1`.

#### 9.5.8 Закомментированные тесты

```bash
grep -rEn '^\s*;;\s*\(deftest|^\s*\(comment\s+\(deftest' test --include='*.clj' | head -5
```

`(comment (deftest ...))` или `;;; (deftest ...)` — забытый код. Либо
удалить, либо включить (если тест должен работать). Никогда не
оставлять «на потом» — превращается в перманентный шум.

#### 9.5.9 Inter-test dependencies через global state

```bash
# defonce / def в test ns — потенциальный shared state:
grep -rEn '^\(defonce\b' test --include='*.clj' | head -5
grep -rEn '^\(def\s+\^:private\s+\S+\s+\(atom' test --include='*.clj' | head -5
```

Тесты, делящие state через `defonce` атомы или global Vars, ломаются
по порядку выполнения. Каждый test должен начинать с known state —
через fixture `:each` или явный setup в `let`.

#### 9.5.10 Over-mocking в integration-тестах

```bash
# Интеграционные тесты с >3 with-redefs:
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

Integration test с 3+ `with-redefs` — это unit test с заглушками,
проиграл свой смысл (проверять production-shape). Либо убрать
mocking, либо переименовать в unit test и переехать в `test/graphden/
<module>/`.

## 10. Скорость тестов — что реально стоит чинить

### 10.1 Heavy fixtures — golden-bootstrap pattern

Интеграционные тесты, нуждающиеся в полном package-set'е, должны
идти через `setup/bootstrap-crud-graph-from-golden!` (TEMPLATE
clone, ~100ms / NS), не через `setup/bootstrap-crud-graph!`
(полный sync, 10-14s / NS).

Проверка:

```bash
grep -lE 'bootstrap-crud-graph!' test/graphden -r | xargs grep -L 'from-golden'
```

Если найдено — мигрировать на golden, кроме случаев когда тесту
нужен сам процесс bootstrap (например, тест проверяет sync).

### 10.2 Long Thread/sleep в integration-тестах с polling-доступным контрактом

Если тест проверяет cron / future / async-flow с фиксированным
sleep, а у системы есть polling-семантика (deadline + poll) — это
зря потраченные секунды.

Не каждый sleep можно убрать: «cron `* * * * * ?` срабатывает раз в
секунду» — 1100ms sleep оправдан, потому что таково ОБЯЗАТЕЛЬСТВО
cron'а.

### 10.3 Configurable poll-timeout для polling-based примитивов

Пример: `pg-notify/create-listener` принимает optional
`:poll-timeout-ms`. Производство — 1000ms (низкий idle-CPU), тесты —
250ms (быстрый wake-up). Аналогичный паттерн в любом «idle pod
polls» примитиве.

## 11. Производительность — не оптимизируй без замера

Перед оптимизацией:

```bash
# Найди slow operation:
clojure -M:dev:test -m kaocha.runner --focus ...   # kaocha profiling plugin печатает top-slow
```

Анти-паттерны новой производительности:

- **Atom-кэш в hot path БЕЗ замера** — мог уменьшить throughput из-за
  contention.
- **Memoize над функцией с unbounded args** — memory leak.
- **Eager-load big result set'а** в `(into [])`, когда `lazy-seq`
  достаточно.

## 12. Тестовое покрытие — не ради процента

**Наш baseline уже alpha-grade** (93.91% line / 84.54% form в
`bb coverage-full`). Дальше тесты добавлять только когда:

1. **Регрессионный sentinel** на критичный invariant (security,
   versioned-storage merge, executor compile). Пример (этой
   сессии) — `logic_test.clj` для `:constant-time-equal?` чтобы
   будущая «оптимизация» не сломала constant-time.
2. **Покрытие нового сценария** — фича добавила user-visible behavior.
3. **Reproduce-on-CI существующего бага** — perpetual regression
   guard.

**НЕ добавлять тест:**

- Чтобы поднять coverage% на defensive `log/warn` в catch — log
  путь тестировать дорого, regression-риск низкий.
- Дубликат уже-покрытого пути — см. §9.3.
- «На всякий случай» без named regression mode.

## 13. Workflow — где править и как чекать

### 13.1 Письмо нового кода

1. Перед написанием — `bb check` зелёный.
2. Пишешь модуль — сразу следуя этому скиллу (короткие функции, DRY,
   ясные имена, nil-safety).
3. Перед коммитом — `bb check` + focused-тесты touched ns'ов.

### 13.2 Проверка существующего кода

1. **Baseline** — `bb check`, `bb test`, reachability audit. Запиши
   что сейчас зелёное, что красное.
2. **Сканируй по §1-12** — каждый раздел даёт `grep`/`python`-один-
   лайнер для поиска кандидатов.
3. **Резюме** перед правками — сколько кандидатов нашлось, какие
   приоритеты (security > nil-safety > dead code > DRY > splits).
4. **Правь по приоритету** — на каждое значимое изменение **commit
   per checkpoint** (рекомендация из CLAUDE.md), не пакетом-простынёй.
5. **Verify после каждого commit'а** — `bb check` + focused-тесты
   touched ns'ов.
6. **Финальный sweep** — `bb test` или `bb ci` (зависит от
   amount of changes).

### 13.3 Commit rules — каждый коммит — value sam по себе

Из round-1 + round-2 сессий:

- **Один концептуальный change на commit** — security-fix и DRY-
  refactor не должны быть в одном commit'е.
- **Subject ≤ 70 chars** — «refactor(types/check): extract closure-
  strip + literal-bound throw». Префикс показывает тип: `refactor`,
  `fix`, `security`, `perf`, `test`, `style`, `docs`.
- **Body explains WHY** — не WHAT (diff показывает what); WHY = «было
  X-shape, причина / эффект Y».
- **Verified-by lines** — какие тесты подтвердили no-regression
  (`23 tests / 78 assertions, 0 failures`).

## 14. Анти-паттерны (не делать)

- **Big-bang refactor.** «Распилим все 5 больших файлов одним
  коммитом» — гарантированно сломаешь что-то незаметно. Per-target,
  per-commit.
- **Refactor + behavior change в одном коммите.** Refactor = same
  observable behavior; mixing с фиксом меняет smell-test.
- **Молчаливый рерайт docstring'а** при extract'е helper'а. Перенос
  логики ОК; перенос + переформулировка причины — теряется история.
- **Удаление кода без grep'а по имени.** Имя может встречаться в
  комментарии — комментарий устареет.
- **Premature optimization** — добавление кэша / batching без
  baseline-замера. Кода больше, выигрыша нет.
- **`bb coverage-full` после каждой правки.** ~35 минут — это
  периодический audit, не CI-gate. Day-to-day — `bb check` +
  focused-тесты.

## 15. Integration tests — `test/graphden/integration/`

Integration suite сидит в `test/graphden/integration/` (11 NSes на
момент 2026-07-05). Каждый — `^:integration` meta, идёт через
shared PG testcontainer + golden-bootstrap. Это самое дорогое
тестирование (`bb test` integration занимает ~70% wall-time), поэтому
качество тут критично.

### 15.1 Coverage matrix — какие user-flow ДОЛЖНЫ быть покрыты

Список критических user-flows и их покрытия:

| User-flow | Integration test | Если нет — risk |
|---|---|---|
| Server bootstrap (full sys/start-with-overrides!) | `smoke-pass-test` | regression в integrant wiring проходит до prod |
| `/api/execute` happy path + cancellation + timeout | `execute-http-test` | execute pipeline регрессия не ловится unit'ами |
| `/api/secrets/*` end-to-end (vault create/rotate/delete) | `secret-flow-test` | vault integration ломается тихо |
| Cron `:schedule` → service registration → reconciler-driven fire | `cron-schedule-service-test` | cron breakage обнаруживается только в prod |
| `find-fn-usages` через граф | `find-fn-usages-graph-test` | usage graph regression hides |
| Storage protocol contract (any backend) | `storage-protocol-poc-test` | future backends не проверены |
| **Branches** (create, switch, diff, merge) | `branches-lifecycle-test` | — |
| **Services** (full reconciler lifecycle for HTTP server) | `http-server-service-lifecycle-test` | — |
| **Auth middleware** (real bearer-token request → 200 / 401) | `auth-middleware-test` | — |
| Tenancy (FaaS addon-active harness) | `faas-app-test` | — |
| Admin grants (per-request-scope router) | `grants-admin-test` | — |

Все критические флоу сейчас покрыты. Для НОВОГО критического флоу: заведи
integration test (sentinel для regression) ИЛИ явно обоснуй, почему
unit-level достаточен.

### 15.2 Duplication audit — что integration НЕ ДОЛЖЕН делать

Integration test НЕ дублирует unit test. Сценарий:

| Слой | Что проверяется | Кому отдать |
|---|---|---|
| Unit | Pure logic (parse / validate / format / classify) | `test/graphden/<module>/<file>_test.clj` |
| Integration через graph | DB write + read через VersionedStorage | `test/graphden/crud/<file>-graph-test.clj` (НЕ `integration/`) |
| Integration через full system | Полный sys/start-with-overrides! + HTTP roundtrip + cleanup | `test/graphden/integration/` |

Если integration test'а можно повторить через `crud/...-graph-test`
без bootstrap'а full system — это перерасход. Перенести.

### 15.3 Производительность integration suite

```bash
# Total wall time per integration test:
clojure -M:dev:test -m kaocha.runner --config-file tests.edn :integration --reporter kaocha.report/documentation 2>&1 | tail -30
```

Целевые числа:

- `smoke-pass-test`: 30-60 s (полный bootstrap + 1 проход)
- `cron-schedule-service-test`: 60-120 s (включает 1+ s cron-fire)
- `execute-http-test`: < 20 s
- `secret-flow-test`: < 30 s

Если test > target: либо лишний bootstrap (использовать golden), либо
лишние `Thread/sleep`, либо реально heavy work — обосновать.

```bash
# Какие integration tests НЕ через golden?
grep -L 'bootstrap-crud-graph-from-golden' test/graphden/integration/*_test.clj 2>/dev/null
```

### 15.4 Что должно быть в каждом integration test

- **Один user-flow per NS** — НЕ ставить 5 несвязанных в одно
  `^:integration` (один failure кидает всё)
- **Cleanup gate** — каждый test чистит за собой (либо `:each` fixture
  c clean-db, либо явный `(finally (sys/stop! system))`)
- **Sleep по контракту, не по надежде** — `Thread/sleep 1100` для cron
  оправдан (per-second contract); 5 s «на всякий случай» — нет
- **Один assert цикл** — `(testing "complete flow" ...)`, НЕ
  10 раздельных testing с независимыми submission'ами

## 16. Browser tests — `tools/browser-test/*.test.js`

Browser suite — 56 Playwright e2e-test'а в `tools/browser-test/`

- visual-snapshot suite в `tools/visual-tests/`. ~9000 LOC JS,
покрывают UI flow редактора.

### 16.1 Coverage matrix — UI features → test files

Editor `editor-*.js` модули и их e2e покрытие:

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
| **Build hash verify** | **❌ gap** | нет browser-теста на `window.BUILD_HASH` после deploy |
| **Layout edge labels click → expand** | **❌ gap** | сложный flow без e2e cover |
| **Visual regression** | ✅ | `tools/visual-tests/*` (separate suite) |

### 16.2 Duplication audit между browser tests

```bash
# Сколько раз каждый prefix tested:
ls /root/projects/graphden/tools/browser-test/*.test.js | xargs -n1 basename | \
  sed -E 's/(edit-[a-z]+|regression|type-system-ui).*/\1/' | sort | uniq -c | sort -rn
```

Если у prefix > 5 файлов и все они тестируют похожее API — кандидаты
на слияние. Пример: 7 `edit-type-*` файлов — но каждый тестирует
СВОЁ (variant / record / list / record-remove etc.) — это OK,
single-test-per-shape.

**Pattern smell**: два файла с почти-одинаковым setup (>50% same code)

- разная assertion — кандидат на параметризацию (одна test-функция, два
вызова с разными scenario'ами).

```bash
# Найди тесты которые открывают тот же initial state:
grep -lE 'navigateTo.*"web-server"' tools/browser-test/*.test.js | wc -l
grep -lE 'createComposedFn.*const' tools/browser-test/*.test.js | wc -l
```

### 16.3 Performance — total wall time + parallelization

```bash
# Сколько файлов = сколько Playwright процессов (если не paralleled):
ls tools/browser-test/*.test.js | wc -l
echo "Per-test setup cost: chromium launch + page navigation ~ 3-5 s"
echo "Sequential total: ~50 tests * 30 s avg = 25 min"
```

Современные best practices:

- **Shared `browser.newContext()` per file**, не per test (если файл
  имеет 1 test — ok; если несколько — shared)
- **Parallel run через `npx playwright test`** (если using `@playwright/
  test` runner). Сейчас наши файлы — standalone `node *.js` scripts,
  parallel НЕ работает out of the box → migration target
- **Visual regression suite — отдельная фаза CI** (не каждый PR)

### 16.4 Reliability — cleanup, wait strategies, selectors

#### 16.4.1 Cleanup race

```bash
# Каждый browser test должен начинать с cleanup:
grep -LE 'cleanup\(|deleteFnByName|delete.*before' tools/browser-test/*.test.js | head
```

Browser tests параллельно — каждый seed'ит fn-def с unique `RUN_ID`
(`process.pid + Date.now`). Если cleanup не работает — мусор копится
в dev-DB → следующий full reset через `bb deploy`.

**Pattern check**: `RUN_ID = '-' + process.pid + '-' + Date.now()` —
каждый probe-fn должен иметь suffix.

#### 16.4.2 Wait strategies — `waitForSelector` > `page.waitForTimeout`

```bash
# page.waitForTimeout — флаки под загрузкой:
grep -rEn 'waitForTimeout\s*\(\s*[1-9]' tools/browser-test/*.test.js | head
```

Fixed-time waits в browser tests = same as `Thread/sleep` в Clojure
tests (см. §9.1). Заменять на polling: `page.waitForSelector(...)`,
`page.waitForFunction(...)`, `await assert(...)` с retry.

#### 16.4.3 Brittle selectors — `:nth-child` / class-by-text

```bash
# nth-child / nth-of-type — фрагильно к UI rearrangement:
grep -rEn ':nth-child\(|:nth-of-type\(' tools/browser-test/*.test.js | head

# CSS class by content — может ломаться при theme refactor:
grep -rEn 'querySelector.*\.[\w-]+:has-text' tools/browser-test/*.test.js | head
```

Стабильные селекторы (от лучшего к худшему):

1. `data-testid="foo"` — explicit test handle
2. `getByRole('button', {name: 'Save'})` — semantic
3. `text=Save` — content-based (ломается при i18n)
4. `.css-class` — break on style refactor
5. `:nth-child(3)` — break on layout change

#### 16.4.4 Auth-token leakage в test output

```bash
# Tokens hardcoded vs env-var:
grep -rEn 'Bearer\s+[a-zA-Z0-9]' tools/browser-test/*.test.js | head
# Должно быть только process.env.AUTH_TOKEN
```

### 16.5 Что должно быть в каждом browser test

- **Header docstring** — что тестирует + run command + exit codes
- **Unique RUN_ID** — `'-' + process.pid + '-' + Date.now().toString(36)`
- **Cleanup gate** — try/finally + `cleanup(page)` обёртка
- **Console error listener** — `page.on('console', ...)` ловит UI
  exception'ы во время теста
- **Dialog handler** — `page.on('dialog', d => d.accept())` если
  cleanup может trigger confirm-dialog
- **Final `process.exit(0|1)`** — exit code определяет PASS / FAIL
- **No `console.log` после assert success** — output чистый

## 17. Связи с другими скиллами

- **`graphden-packages-quality`** — те же принципы для `fns.edn` +
  `impls.clj` (типы, fn-def naming, минимальные base-fn). Если работа
  идёт в `resources/packages/` — переключайся.
- **`graphden-fn-design`** — деталь по naming / namespaces / MI для
  fn-def. Вызывается `graphden-packages-quality` для конкретики.
- **`graphden-fn-refactor`** — декомпозиция base-fn impls. Вызывается
  `graphden-packages-quality` для конкретики.
- **`graphden-repl`** — отладка гипотезы перед `bb rebuild`.
  Используется ВСЕГДА когда нужно проверить «что вернёт эта функция
  сейчас».
- **CLAUDE.md** — первоисточник проектных принципов. Этот скилл — его
  operational арм.

## 18. Что считается «не докопаться»

Финальный self-check перед закрытием:

**Code & lint**

- [ ] `bb check` зелёный (0 warnings)
- [ ] focused-тесты touched ns'ов зелёные
- [ ] `bb test` или `bb ci` (в зависимости от scope) зелёный
- [ ] Reachability audit не показывает новых unreachable (если
      менял `fns.edn`)
- [ ] Нет TODO/FIXME/XXX/HACK маркеров без issue link

**Structure**

- [ ] Каждая функция ≥ 100 LOC ОБОСНОВАНА (см. §1.5) либо распилена
- [ ] User-facing `:error` / `:reason` поля nil-safe
- [ ] Каждый секретный compare — constant-time
- [ ] Каждый новый JDBC-запрос через HoneySQL `sql/format`; raw-string
      только по carve-out из §7.4 (PG-RPC / DDL edge / нет runtime-данных)

**Unit tests**

- [ ] Нет дубликатных deftests с одинаковыми observable assertion'ами
- [ ] Каждый sleep в тестах либо оправдан runtime-контрактом, либо
      заменён на poll-with-deadline
- [ ] Нет tautological `(is (= X X))` / `(is (some? …))` где есть
      конкретный expected (§9.5.1-9.5.2)
- [ ] Нет `(is (thrown? Exception …))` без класса/regex (§9.5.3)
- [ ] Нет логики (`loop` / `if` / `when`) внутри `(is …)` (§9.5.4)
- [ ] `testing`-блоки тестируют ОДНУ вещь — не 4+ `is` подряд (§9.5.5)
- [ ] Нет тестов на private symbols (`#'ns/_internal`) (§9.5.6)
- [ ] Нет закомментированных deftests (§9.5.8)

**Integration tests**

- [ ] Каждый критический user-flow покрыт (§15.1 matrix gaps)
- [ ] Integration НЕ дублирует unit-test слой (§15.2)
- [ ] Все integration tests через golden-bootstrap (§15.3)
- [ ] Один user-flow per NS (§15.4)

**Browser tests**

- [ ] Новый UI feature → новый `*.test.js` ИЛИ explicit «не нужно»
      (§16.1 matrix)
- [ ] Cleanup gate в каждом `*.test.js` с RUN_ID (§16.4.1)
- [ ] Нет `page.waitForTimeout(N)` без обоснования (§16.4.2)
- [ ] Селекторы — `data-testid` / `getByRole`, не `:nth-child`
      (§16.4.3)
- [ ] Auth-token через `process.env`, не hardcoded (§16.4.4)

**Commit hygiene**

- [ ] Каждый commit — отдельная concept-value-unit с verified-by
      линией в body
