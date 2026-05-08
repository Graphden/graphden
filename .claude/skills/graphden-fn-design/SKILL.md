---
name: graphden-fn-design
description: Design rules for creating Graphden fn-defs — when to give a fn an explicit public name vs make it private (`_`-prefix), when to use multiple inheritance vs a single parent, when to extract a subgraph into its own private namespace, and how the auto-naming / inline-display rules work. Use when writing or restructuring `fns.edn` entries, splitting a large fn-def, deciding whether to introduce a new helper, or auditing existing definitions for naming hygiene. Triggers on phrases like "как назвать", "анонимный или именованный", "сделать общим", "extract a helper", "куда положить fn", "MI или один родитель", "переиспользование", "приватная функция".
---

# graphden-fn-design — правила объявления fn-def

Задача скилла: при добавлении / реструктуризации fn-def'ов чётко знать
**нужно ли явное имя**, **класть ли в private namespace**, **использовать
MI или нет**, и какие display-последствия вытекают из выбора. Эта
система — аналог Clojure'овских `defn` / `defn-` / `let` / `letfn`,
переведённый на graph-storage.

## Базовая аналогия с Clojure

```clojure
;; Clojure                                  ;; Graphden EDN
(defn web-server [port handler] …)          {:name :web-server
                                              :parent :http-server
                                              :args {:port 8080
                                                     :handler :_app-ring-response}}

(defn- _app-ring-response [req] …)          {:name :_app-ring-response
                                              :parent :router
                                              :args {…}}

(defn make-server [p h]                     ;; inline composite type:
  {:port p :handler h})                     ;; {:input {:port :int :handler :fn}}
```

| Концепт | Clojure | Graphden |
|---|---|---|
| Public reusable fn | `defn` | `:name :public-name` (без `_`) |
| Private helper | `defn-` | `:name :_private-name` (`_`-prefix) |
| Inline value | `(let [x …] …)` | `{:value x}` binding |
| Inline composite type | inline map literal | `:input {:k T}` / `:type {:k T}` (anonymous-hash deduped) |

## 1. Public name vs `_`-private — решение в момент написания EDN

**Ставь `_`-префикс, если все три условия выполняются:**

1. **fn-def нет в API/контракте.** Это деталь реализации какой-то более
   крупной публичной fn — а не самостоятельная единица.
2. **Не планируется reuse.** Один use-site сегодня и завтра один.
3. **Имя само по себе не несёт смысла.** Если без контекста родительского
   fn-def'а название «не звучит» (`_app-ring-response` понятно только
   рядом с `web-server`), это маркер `_`.

**Имя без префикса — если хотя бы одно из:**

- fn-def переиспользуется (≥ 2 use-site сегодня или планируется завтра).
- fn-def — узнаваемая сущность доменного словаря (`web-server`,
  `http-server`, `json-ok-response`).
- На неё хочется сослаться извне пакета (когда экспортируем).

**`_`-префикс — это UI-маркер, не отдельная сущность.** Под капотом это
обычная fn с обычным именем. UI:
- скрывает имя на графе (показывает inline в теле родителя при expand);
- при наводке `i`-tooltip показывает auto-name + опцию rename;
- в боковом меню `_`-fn'ы спрятаны под фолд / в private-namespace.

## 2. Auto-name для private fn — формат

Когда автор НЕ указал имя явно (anonymous fn-def через UI «выделить в
helper» или импорт legacy-EDN без имени), генерируем стабильно:

```
auto-name = "_" + <parent-fn-name> + "-" + <slot-name>
namespace = <parent-fn-namespace>
```

Пример: при «выделить в helper» биндинга `:handler` на fn `web-server`
(namespace `app.server`) → `:_web-server-handler` в namespace
`app.server`.

**Правило стабильности:** при повторном sync'е того же EDN auto-name
должно совпадать → детерминированный UUID через `(parent-name,
slot-name)`. Конфликт имён (тот же auto-name уже занят) → суффикс `-2`,
`-3`, …

Этот алгоритм **per-use-site**: два разных места, выделивших одинаковый
кусок логики, получат **разные** fn-rows. Дедупа нет — это сознательный
выбор для private fn-defs (у них ссылочная семантика, не value).

## 3. Inline composite types — другой механизм (shape-dedup)

`:input {:k T}` / `:type {:k T}` объявляют **анонимный composite type**
(record-shape). Тут наоборот: один и тот же shape в двух местах
**делит** одну fn-row через `anonymous-hash` UNIQUE constraint.

```edn
{:name :greet-handler-A
 :input {:user-name :text :greeting :text}}   ; ← shape-hash X

{:name :greet-handler-B
 :input {:user-name :text :greeting :text}}   ; ← тот же shape-hash X → та же fn-row
```

Auto-name для таких rows: `_anon-<shape-hash[0..7]>` в namespace того
fn-def'а, который их объявил (если их два, выбирается лексикографически
первый — sync детерминирован).

**Когда писать inline composite vs `_`-private fn:**

| Хочется | Используй |
|---|---|
| Описать форму записи (record / тип) | inline composite (`:input` / `:type`) |
| Описать поведение (граф вычислений) | `_`-private fn-def с `:parent` |

Composite — это описание значения. Private fn — это описание вычисления.
Не путать.

## 4. Multiple inheritance — когда оправдано

`:parents [:a :b]` (вместо `:parent :a`) — это **mix-in**: fn получает
slots обоих родителей. Используется в трёх ситуациях:

1. **Категоризация поведения через separate concerns.** `:assoc-handler
   :parents [:assoc-fn :assoc-empty]` — `:assoc-fn` приносит политику
   типа (slot `:value` имеет тип `:fn`), `:assoc-empty` приносит
   стартовый состав (пустую запись). Каждый родитель — одна
   ортогональная характеристика.
2. **Composition по типу trait'ов.** `:authed-route :parents [:get-route
   :auth-required]` — `:get-route` даёт structure (`:path`, `:handler`),
   `:auth-required` подмешивает middleware-стек.
3. **Refinement без копирования.** Когда уже есть две fn'и `:a` и `:b`,
   у которых полезно объединить slot-наборы без переписывания.

**MI противопоказано, когда:**

- Slot'ы родителей конфликтуют по `(name, type)` — sync будет error'ом.
  Проверить можно через `bb test` интеграционные тесты или через
  `composition.validation`.
- Один из родителей сам composed (parents-of-parents) и его slots
  пересекаются с другим — диамант. Технически работает, но читаемость
  падает. Лучше выделить общего деда явным `_`-частным фундаментом.
- «Хочется чтобы fn делала и X и Y» — это плохой повод. MI описывает
  **shape**, а не behavior-композицию. Behavior-композиция = обычные
  ref-биндинги внутри `:args`.

**Эвристика:** если после прочтения `:parents [a b]` непонятно, какие
slots откуда приходят, перепиши на single-parent + `_`-helper.

## 5. Группировка в namespace — когда выделить отдельный

Каждый fn лежит в namespace (берётся из `:namespace` поля fns.edn-файла).
Создавай новый namespace, когда:

1. **≥ 5 fn-defs объединены общей темой** (`web.html`, `core.arithmetic`,
   `app.editor`). Меньше — пихай в существующий.
2. **Все fn'ы внутри — private (`_`-prefix или planned-private).**
   Тогда namespace становится «папкой для внутренней реализации» —
   editor-сайдбар может collapse его по умолчанию.
3. **Естественный uses-from-elsewhere boundary.** То есть pattern такой:
   из других пакетов импортируют 2-3 публичные fn'и, а private остаются
   локальными.

**Не создавай namespace ради:**
- Одной fn (даже сложной) — она просто живёт в существующем.
- Технического разделения по типу (`utils`, `helpers`) — bikeshed без
  семантической нагрузки.

**`:private?` flag на namespace** (если ввели) — намекает редактору
collapse-by-default + новые fn'ы в нём auto-получают `_`-prefix.
Эквивалентно тому, что каждая fn в нём имела бы `_`. Используй для
крупных deeply-private модулей (типа `app.server.internal`).

## 6. Decomposition — когда «делить» большую fn

В Clojure'е `(defn big-fn [x] (let [a (...) b (...) c (...)] (...)))` ←
если let'ов много, выносим в `defn-`. Тот же критерий тут:

**Делить fn на private helpers, когда:**

- Тело fn имеет ≥ 4-5 ref-биндингов на разные intermediate-вычисления.
- При expand'е в редакторе появляется так много узлов, что не
  читается.
- Какой-то слой (preprocessing, validation, post-format) семантически
  обособлен.

**Не делить, когда:**

- 1-2 шага вычислений → inline.
- Один и тот же shape повторяется → используй inline composite type
  вместо private fn (точнее по семантике, дедуплицируется).
- Decomposition не имеет естественной границы — попытка чисто
  «уменьшить тело» создаст плохо названные `_step1`, `_step2`.

## 7. Display rules — что увидит пользователь

| Тип fn | Sidebar | На графе при expand | `i`-tooltip |
|---|---|---|---|
| Public (`name` без `_`) | видна | отдельный узел | имя + namespace + description |
| Private (`_name`) **с одним use-site** | спрятана / в private ns | **inline в теле родителя** | auto/explicit-name + rename-affordance |
| Private (`_name`) **с ≥ 2 use-site'ами** | спрятана / в private ns | **отдельный узел** (как public, но имя dimmed / без `_` в лейбле) | то же |
| Anonymous composite (inline `:input`) | не видна | не показывается отдельно (структура входит в parent's slot list) | `_anon-<hash>` в `i` родителя |

UI rules:
1. **`_`-prefix или `:private? true`** — это маркер «не API-поверхность» —
   определяет sidebar-видимость и hidden-prefix-в-лейбле.
2. **Inline vs отдельный узел** — определяется ОТДЕЛЬНО, по
   количеству use-site'ов: один = inline (тело родителя), два и
   больше = отдельный узел (это уже shared subroutine, рисовать N
   копий тела бессмысленно).
3. Auto-инлайн только для **named-by-author**-private (т.е. фактически
   написанных в EDN с `_`-prefix). Auto-named-by-shape (inline composite
   types через `anonymous-hash`) — display-логика встроена в parent
   (показ через `:input`/`:type` slots самой fn'и).

Так концепция «открыть граф = увидеть тело» работает в estественной
интуиции: open-rate `_`-fn'у с одним use-site — он inline-инлайнится,
fan-out не происходит. У `_`-fn'и с переиспользованием — отдельный
узел, но имя тише (без `_`-префикса в лейбле, dim).

Если `_`-fn внезапно стала переиспользоваться (1 → 2 use-site'а) —
display автоматически переключится на «отдельный узел» при следующем
рендере. Решение per-render, не per-decl.

## 8. Quick decision flowchart

```
Хочешь добавить fn-def?
  │
  ├─ Это форма данных (record / тип значения)?
  │    └→ inline composite в `:input` / `:type` родителя.
  │
  ├─ Это поведение, переиспользуется?
  │    └→ public name (без `_`), в семантически правильный namespace.
  │
  ├─ Это поведение, один use-site, имя несамостоятельное?
  │    └→ `_<parent>-<slot>` private name, в namespace родителя.
  │
  └─ Это shape, используется в N местах с одинаковой структурой?
       └→ inline composite — anonymous-hash schлопнёт в одну row.

Несколько родителей?
  ├─ Каждый родитель приносит ОРТОГОНАЛЬНЫЙ slot-набор → MI ок.
  └─ Иначе → single parent + `_`-helper для общего фундамента.

Намечаешь декомпозицию большой fn?
  ├─ Естественные слои (validation / format / etc) → `_`-helpers.
  ├─ Один и тот же кусок повторяется → public, не private.
  └─ Просто хочется уменьшить тело без логического разреза → НЕ делить.
```

## 9. Anti-patterns

- **`_`-prefix на public-API fn.** Если кто-то ссылается из другого
  пакета — это уже не private.
- **Public name без reuse.** «На всякий случай назовём» — зря засоряет
  namespace + sidebar. Делай `_`-private.
- **MI ради feature-mix'а behavior'а.** MI про slot-shape, не про
  «мне нужно склеить логику A и B». Логика клеится через ref-биндинги
  в `:args`.
- **Namespace per fn.** `app.server.web-server` ns с одной fn-def
  внутри — overhead. Положи в `app.server`.
- **Auto-name руками.** Не пиши `_anon-3f2a` сам — это служебное имя,
  оно генерируется. Если хочется явно назвать — назови по-человечески.

## 10. Связи с другими местами

- Реализация `_`-prefix UI rules: `editor-overlays.js`,
  `editor-sidebar.js`.
- Загрузка fn-def'ов из EDN: `src/graphden/packages/loader.clj`.
- Парсер shape-dedup для inline composite: `src/graphden/packages/records.clj`
  (`shape-hash`, `anonymous-fn-id`).
- Validate-no-duplicate-names + правила naming: `composition.validation`.
- Live-проверка fn-def'а в REPL: см. `graphden-repl` skill.

## 11. Что планируется (не реализовано прямо сейчас)

- **Export пакета как EDN.** В планах фича: выгрузить пользовательские
  fn-defs как переносимый пакет. Поэтому **все** fn'ы должны иметь
  стабильные имена (включая ones, которые сейчас anonymous через
  shape-dedup) — иначе им неоткуда взяться при импорте на другой инстанс.
  Auto-naming делает эту фичу подъёмной.
- **`:private?` flag** на namespace + на отдельных fn-def'ах — UI hint
  для display rules. Эквивалент `_`-prefix конвенции. Когда введём,
  выберем одну из двух как канон.
- **«Выделить в helper»** UI-кнопка в редакторе — берёт inline-биндинг
  и автоматически создаёт `_<parent>-<slot>` private fn с рефакторингом
  ссылок.
