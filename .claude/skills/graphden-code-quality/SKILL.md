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

## 15. Связи с другими скиллами

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

## 16. Что считается «не докопаться»

Финальный self-check перед закрытием:

- [ ] `bb check` зелёный (0 warnings)
- [ ] focused-тесты touched ns'ов зелёные
- [ ] `bb test` или `bb ci` (в зависимости от scope) зелёный
- [ ] Reachability audit не показывает новых unreachable (если
      менял `fns.edn`)
- [ ] Нет TODO/FIXME/XXX/HACK маркеров без issue link
- [ ] Нет дубликатных deftests с одинаковыми observable assertion'ами
- [ ] Каждая функция ≥ 100 LOC ОБОСНОВАНА (см. §1.5) либо распилена
- [ ] User-facing `:error` / `:reason` поля nil-safe
- [ ] Каждый секретный compare — constant-time
- [ ] Каждый sleep в тестах либо оправдан runtime-контрактом, либо
      заменён на poll-with-deadline
- [ ] Каждый commit — отдельная concept-value-unit с verified-by
      линией в body
