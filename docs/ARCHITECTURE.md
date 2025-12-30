# Graphden: Система визуального функционального программирования

## Часть 1: Критический анализ модели

### Проблема "переопределения аргументов"

**Текущий подход (наследование через parent-fn-id):**

```
fn: A (parent: null)
  arg-values: {x: 1}

fn: B (parent: A)
  arg-values: {y: 2}  ← OK

fn: C (parent: B)
  arg-values: {x: 5}  ← ЗАПРЕЩЕНО: x уже определён в A
```

**Как это проверить?**

| Storage | Реализация | Проблемы |
|---------|------------|----------|
| PostgreSQL | Trigger + рекурсивный CTE | Сложно, медленно на глубоких цепочках |
| Datomic | Transaction function + query | Сложно, нужен дополнительный запрос |
| Memory | Код при записи | Дублирование логики |

**Это ПЛОХО** — constraint не декларативный, требует кода, легко сломать.

---

### Альтернатива 1: Иммутабельность + Копирование

**Идея**: Отказаться от "живого" наследования. При создании fn копировать arg-values из "базы".

```
fn: A
  arg-values: {x: 1}

fn: B (based-on: A)
  // При создании скопировали x: 1 из A
  arg-values: {x: 1, y: 2}

fn: C (based-on: B)
  // При создании скопировали x: 1, y: 2 из B
  arg-values: {x: 1, y: 2, z: 3}
```

**Плюсы:**
- Нет parent-fn-id → нет рекурсивных проверок
- Каждая fn — самодостаточна
- Constraint "один arg-schema-id на fn" = простой unique(owner-fn-id, arg-schema-id)

**Минусы:**
- Нет "живого" обновления: изменил A → B и C не обновятся
- Дублирование данных

**Митигация минусов:**
- "Живое" обновление редко нужно на практике
- Можно добавить операцию "обновить из базы" если нужно
- Дублирование — не проблема для небольших значений

---

### Альтернатива 2: Версионирование

**Идея**: Каждая fn иммутабельна, изменение = новая версия.

```
fn: base-api@v1 {url: "https://old.api"}
fn: base-api@v2 {url: "https://new.api"}

fn: create-user (based-on: base-api@v1)
  // Привязан к конкретной версии
```

**Плюсы:**
- Полная предсказуемость
- История изменений
- Можно откатиться

**Минусы:**
- Сложнее UX
- Больше данных

---

### Альтернатива 3: Граф без наследования

**Идея**: Отказаться от концепции "наследования". Каждая fn полностью определяет свои аргументы.

```
fn: create-user
  fn-schema: http-request
  arg-values: {
    url: "https://api.example.com",  // Можно скопировать из другой fn через UI
    method: "POST",
    headers: {...},
    body: {...},
    timeout: 30
  }
```

**Плюсы:**
- Максимальная простота
- Нет проблемы переопределения вообще

**Минусы:**
- Дублирование при ручном создании
- Потеря концепции "частичного применения"

**Но**: UI может предлагать "создать на основе" = копирование с возможностью изменить.

---

### Выбранный подход: Живое наследование + Явные ограничения

**Схема с наследованием:**

```
fn:
  id: uuid (PK)
  name: text (UNIQUE)
  fn-schema-id: ref<fn-schema>
  parent-fn-id: ref<fn> (nullable)  // Живое наследование

arg-value:
  id: uuid (PK)
  owner-fn-id: ref<fn>
  arg-schema-id: ref<arg-schema>
  value: union<ref<fn> | literal-types...>
  UNIQUE(owner-fn-id, arg-schema-id)
```

**Ограничения вынесены в явный протокол** — каждый storage ОБЯЗАН их реализовать.

---

## Часть 2: Протокол ограничений (GraphConstraints)

### Новый протокол

```clojure
(defprotocol GraphConstraints
  "Ограничения целостности графа функций.
   Каждый storage ОБЯЗАН реализовать этот протокол.
   Нарушение любого ограничения = выброс исключения."

  (validate-parent-same-schema!
    [this fn-id parent-fn-id]
    "Ограничение: parent-fn должен иметь тот же fn-schema-id, что и fn.
     Вызывается при создании/изменении fn с parent-fn-id.
     Throws: :constraint-violation/parent-schema-mismatch")

  (validate-no-arg-override!
    [this fn-id arg-schema-id]
    "Ограничение: arg-schema-id не должен быть уже определён в цепочке родителей.
     Вызывается при создании arg-value.
     Throws: :constraint-violation/arg-already-defined")

  (validate-arg-schema-belongs-to-fn!
    [this fn-id arg-schema-id]
    "Ограничение: arg-schema должен принадлежать fn-schema этой fn.
     Вызывается при создании arg-value.
     Throws: :constraint-violation/arg-schema-mismatch")

  (validate-no-inheritance-cycle!
    [this fn-id parent-fn-id]
    "Ограничение: установка parent-fn-id не должна создавать цикл наследования.
     Вызывается при создании/изменении fn с parent-fn-id.
     Throws: :constraint-violation/inheritance-cycle")

  (validate-no-dependency-cycle!
    [this owner-fn-id target-fn-id]
    "Ограничение: ссылка на target-fn не должна создавать цикл зависимостей.
     Вызывается при создании arg-value с value = ref<fn>.
     Throws: :constraint-violation/dependency-cycle"))
```

### Contract Tests

Создаём набор тестов, которые КАЖДЫЙ storage должен пройти:

```clojure
(defn constraint-tests [create-storage-fn]
  (testing "parent-same-schema constraint"
    (let [storage (create-storage-fn)]
      ;; Setup: create fn-schema-1, fn-schema-2, fn-a (schema-1)
      ;; Test: create fn-b (schema-2) with parent = fn-a
      ;; Expected: throws :constraint-violation/parent-schema-mismatch
      ))

  (testing "no-arg-override constraint"
    (let [storage (create-storage-fn)]
      ;; Setup: fn-a with arg-value for :x, fn-b (parent: fn-a)
      ;; Test: create arg-value for :x on fn-b
      ;; Expected: throws :constraint-violation/arg-already-defined
      ))

  ;; ... остальные тесты
  )
```

### Реализация в каждом storage

| Storage | Где реализовано | Как |
|---------|-----------------|-----|
| memory | При записи в atom | Clojure код с запросами к state |
| postgres | TRIGGER + Clojure fallback | SQL trigger для производительности, Clojure для сложных случаев |
| datomic | Transaction function | `:db/txFn` с Datomic queries |

### README для каждого storage

Каждый storage-компонент получит README.md с описанием:

```markdown
# memory-storage

## Реализованные ограничения GraphConstraints

| Ограничение | Реализация | Файл |
|-------------|------------|------|
| parent-same-schema | Проверка при `create-fn` | `core.clj:45` |
| no-arg-override | DFS по parent chain | `constraints.clj:12` |
| arg-schema-belongs-to-fn | Join check | `constraints.clj:28` |
| no-inheritance-cycle | DFS | `constraints.clj:35` |
| no-dependency-cycle | DFS по arg-values | `constraints.clj:52` |

## Тесты

Все contract tests проходят: `bb test:memory-storage`
```

---

## Часть 3: Рекурсия и циклы

### Рекурсия

**Проблема**: Функция может ссылаться на саму себя.

```
fn: factorial
  arg-values: {
    n: <входной аргумент>,
    recursive-call: ref<factorial>  // Ссылка на себя
  }
```

**При ленивом исполнении это работает**, если есть базовый случай:

```clojure
;; Базовая функция factorial
(defn base-factorial [{:keys [n recursive-call]}]
  (if (<= n 1)
    1
    (* n (execute-fn recursive-call {:n (dec n)}))))
```

**Опасность**: Бесконечная рекурсия при отсутствии базового случая.

**Решения:**
1. **Ограничение глубины** — executor имеет max-depth (например, 1000)
2. **Таймаут** — максимальное время исполнения
3. **Детекция в runtime** — отслеживать стек вызовов

**Рекомендация**: Все три. Это стандартная практика (JVM имеет StackOverflowError, браузеры имеют таймауты).

### Циклические зависимости (не рекурсия)

**Проблема:**

```
fn: A
  arg1: ref<B>

fn: B
  arg1: ref<A>
```

**При попытке вычислить A** → нужен B → нужен A → бесконечность.

**Отличие от рекурсии**: Рекурсия — это одна функция вызывает себя (контролируемо). Цикл — две функции вызывают друг друга (неконтролируемо).

**Решение**: Запретить циклы при создании arg-value.

| Storage | Реализация |
|---------|------------|
| PostgreSQL | Trigger + рекурсивный CTE для детекции цикла |
| Datomic | Transaction function + query |
| Memory | DFS при записи |

**Это сложно**, но необходимо. Без этого система может зависнуть.

**Альтернатива**: Детекция в runtime (при исполнении). Проще реализовать, но ошибка обнаружится позже.

**Рекомендация**: Детекция при записи + защита в runtime (на случай гонок или багов).

### Какие алгоритмы НЕВОЗМОЖНЫ без рекурсии?

**Короткий ответ**: Почти все нетривиальные.

- Обход деревьев/графов
- Сортировки (quicksort, mergesort)
- Парсинг рекурсивных структур
- Многие численные методы

**Вывод**: Рекурсия ОБЯЗАТЕЛЬНА. Нужно разрешить её с защитными механизмами.

### Взаимная рекурсия

```
fn: is-even (n) → if n=0 then true else is-odd(n-1)
fn: is-odd (n) → if n=0 then false else is-even(n-1)
```

Технически это цикл (A→B→A), но это ВАЛИДНЫЙ паттерн.

**Как отличить от "плохого" цикла?**
- Плохой цикл: A нужен результат B, B нужен результат A (deadlock)
- Хороший цикл: A вызывает B с ДРУГИМИ аргументами

**Решение**: Не запрещать на уровне схемы. Защита только в runtime (глубина, таймаут).

---

## Часть 4: Итоговая схема данных

### Сущности

```
┌─────────────────────────────────────────────────────────────────┐
│ fn-schema (схема функции)                                       │
├─────────────────────────────────────────────────────────────────┤
│ id: uuid (PK)                                                   │
│ name: text (UNIQUE)                                             │
│ returned-type: enum<value-kind>                                 │
│ base-fn-name: text (nullable) — имя Clojure-функции            │
│                                 null = составная функция        │
└─────────────────────────────────────────────────────────────────┘
         │
         │ 1:N
         ▼
┌─────────────────────────────────────────────────────────────────┐
│ arg-schema (схема аргумента)                                    │
├─────────────────────────────────────────────────────────────────┤
│ id: uuid (PK)                                                   │
│ fn-schema-id: ref<fn-schema>                                    │
│ name: text                                                      │
│ type: enum<value-kind>                                          │
│ required: bool (default true)                                   │
│ UNIQUE(fn-schema-id, name)                                      │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│ fn (экземпляр функции)                                          │
├─────────────────────────────────────────────────────────────────┤
│ id: uuid (PK)                                                   │
│ name: text (UNIQUE)                                             │
│ fn-schema-id: ref<fn-schema>                                    │
│ parent-fn-id: ref<fn> (nullable) — живое наследование          │
└─────────────────────────────────────────────────────────────────┘
         │
         │ 1:N
         ▼
┌─────────────────────────────────────────────────────────────────┐
│ arg-value (значение аргумента)                                  │
├─────────────────────────────────────────────────────────────────┤
│ id: uuid (PK)                                                   │
│ owner-fn-id: ref<fn>                                            │
│ arg-schema-id: ref<arg-schema>                                  │
│ value: union<ref<fn> | literal-types...>                        │
│ UNIQUE(owner-fn-id, arg-schema-id)                              │
└─────────────────────────────────────────────────────────────────┘
```

### Ограничения и их реализация

| # | Ограничение | PostgreSQL | Datomic | Memory |
|---|-------------|------------|---------|--------|
| 1 | fn-schema.name уникален | UNIQUE constraint | :db/unique :db.unique/identity | Set в индексе |
| 2 | fn.name уникален | UNIQUE constraint | :db/unique | Set в индексе |
| 3 | arg-schema уникален в рамках fn-schema | UNIQUE(fn-schema-id, name) | Composite tuple + unique | Map<[fn-schema-id, name], id> |
| 4 | arg-value уникален в рамках fn | UNIQUE(owner-fn-id, arg-schema-id) | Composite tuple + unique | Map<[fn-id, arg-schema-id], id> |
| 5 | arg-value.arg-schema-id соответствует owner-fn.fn-schema-id | TRIGGER или CHECK с subquery | :db.attr/preds | Валидация при записи |
| 6 | Нет циклов в графе fn через arg-value | TRIGGER + рекурсивный CTE | Transaction function | DFS при записи |

### Ограничение #5 подробнее

**Проблема**: arg-value ссылается на arg-schema, который принадлежит fn-schema. owner-fn тоже ссылается на fn-schema. Они должны совпадать.

```sql
-- PostgreSQL: CHECK constraint (медленно, но декларативно)
ALTER TABLE arg_value
ADD CONSTRAINT arg_value_schema_match CHECK (
  (SELECT fn_schema_id FROM arg_schema WHERE id = arg_schema_id) =
  (SELECT fn_schema_id FROM fn WHERE id = owner_fn_id)
);

-- Или TRIGGER (быстрее, но императивно)
CREATE FUNCTION check_arg_value_schema() RETURNS TRIGGER AS $$
BEGIN
  IF (SELECT fn_schema_id FROM arg_schema WHERE id = NEW.arg_schema_id) !=
     (SELECT fn_schema_id FROM fn WHERE id = NEW.owner_fn_id) THEN
    RAISE EXCEPTION 'arg-schema does not belong to fn schema';
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;
```

**Datomic**: `:db.attr/preds` с функцией валидации.

**Memory**: Проверка в коде при insert/update.

### Ограничение #6 подробнее (циклы)

**При создании arg-value с value = ref<fn>:**

1. Получить target-fn-id из value
2. Рекурсивно собрать все fn, на которые ссылается target-fn через arg-values
3. Если owner-fn-id в этом множестве → REJECT (цикл)

```sql
-- PostgreSQL: рекурсивный CTE
WITH RECURSIVE deps AS (
  -- Базовый случай: target fn
  SELECT target_fn_id AS fn_id
  UNION
  -- Рекурсия: все fn, на которые ссылаются arg-values
  SELECT (av.value->>'ref')::uuid
  FROM deps d
  JOIN arg_value av ON av.owner_fn_id = d.fn_id
  WHERE av.value->>'type' = 'ref'
)
SELECT EXISTS (SELECT 1 FROM deps WHERE fn_id = owner_fn_id);
```

---

## Часть 5: Модель исполнения

### Ленивость и thunks

```clojure
(defprotocol IThunk
  (force-value [this context]))

(defrecord LiteralThunk [value]
  IThunk
  (force-value [_ _] value))

(defrecord FnRefThunk [fn-id provided-args]
  IThunk
  (force-value [_ context]
    (execute-fn fn-id provided-args context)))

(defrecord LazyFnThunk [fn-id]
  ;; Для аргументов типа :fn — не вычисляем, передаём как есть
  IThunk
  (force-value [_ _] fn-id))  ; Возвращаем fn-id, не результат
```

### Типы аргументов и их обработка

| type в arg-schema | Значение в arg-value | Thunk | Поведение |
|-------------------|---------------------|-------|-----------|
| :int, :text, etc. | Литерал | LiteralThunk | force → литерал |
| :int, :text, etc. | ref<fn> | FnRefThunk | force → execute fn |
| :fn | ref<fn> | LazyFnThunk | force → fn-id (для HOF) |

### Базовые функции и их типы

```clojure
;; Обычная функция — все аргументы вычисляются
(defn base-add [{:keys [a b]}]
  (+ (force a) (force b)))

;; Условие — ленивые ветки
(defn base-if [{:keys [condition then else]}]
  (if (force condition)
    (force then)
    (force else)))

;; HOF — f передаётся как fn-id
(defn base-map [{:keys [f coll]} context]
  (let [coll-value (force coll)
        f-id (force f)]  ; Это fn-id, не результат!
    (mapv (fn [item]
            (execute-fn f-id {:item item} context))
          coll-value)))
```

### Контекст исполнения

```clojure
(defrecord ExecutionContext
  [depth         ; Текущая глубина (для защиты от бесконечной рекурсии)
   max-depth     ; Максимальная глубина
   start-time    ; Время начала (для таймаута)
   timeout-ms    ; Максимальное время
   call-stack])  ; Стек вызовов (для отладки)

(defn execute-fn [fn-id provided-args context]
  ;; Проверки безопасности
  (when (> (:depth context) (:max-depth context))
    (throw (ex-info "Max recursion depth exceeded" {:depth (:depth context)})))
  (when (> (- (System/currentTimeMillis) (:start-time context)) (:timeout-ms context))
    (throw (ex-info "Execution timeout" {})))

  ;; Исполнение
  (let [graph (resolve-fn fn-id)
        thunks (build-thunks graph provided-args)
        new-context (update context :depth inc)]
    (call-base-fn (:base-fn-name graph) thunks new-context)))
```

### Адресация свободных аргументов

**Проблема**: fn A использует fn B дважды. У B есть свободный аргумент x. Как передать разные значения x?

**Решение**: Путь через arg-value-id.

```clojure
;; В БД:
;; arg-value-1: {owner: A, arg-schema: arg1-of-A, value: ref<B>}
;; arg-value-2: {owner: A, arg-schema: arg2-of-A, value: ref<B>}
;;
;; У B есть свободный arg-schema: x-of-B

;; Запрос на исполнение A:
{:fn-id A-id
 :args {[arg-value-1-id x-of-B-id] 100   ; x для первого B
        [arg-value-2-id x-of-B-id] 200}} ; x для второго B
```

**Реализация**:

При сборке thunks для A:
1. Для arg1-of-A создаём FnRefThunk с fn-id=B и provided-args, отфильтрованными по arg-value-1-id
2. Для arg2-of-A создаём FnRefThunk с fn-id=B и provided-args, отфильтрованными по arg-value-2-id

---

## Часть 6: План реализации

### Фаза 0: Документация

**0.1 Основной README.md проекта**

Файл: `README.md`

Содержание:
- Видение проекта (визуальное функциональное программирование)
- Архитектура системы (граф функций в БД)
- Ключевые концепции (fn-schema, fn, arg-value, наследование)
- Модель исполнения (ленивость, thunks)
- Ссылки на README компонентов

**0.2 README для каждого компонента**

| Компонент | Описание |
|-----------|----------|
| `storage-protocol` | Протоколы Storage, StorageCRUD, GraphConstraints |
| `data-schema-protocol` | Протокол DataSchema, типы полей |
| `field-types` | Поддерживаемые типы данных |
| `malli-data-schema` | Malli-реализация схемы |
| `graph-data-schema` | Схема графа функций (fn-schema, fn, arg-value) |
| `memory-storage` | In-memory реализация + ограничения |
| `postgres-storage` | PostgreSQL реализация + ограничения |
| `datomic-storage` | Datomic реализация + ограничения |

Каждый README содержит:
- Назначение компонента
- Зависимости
- Основные функции/протоколы
- Примеры использования
- Для storage: таблица реализации ограничений

---

### Фаза 1: Схема данных и ограничения

**1.1 Обновить graph-data-schema**

Файл: `components/graph-data-schema/src/graphden/graph_data_schema/interface.clj`

- Добавить `parent-fn-id` в `:fn`
- Добавить `required` в `:arg-schema`
- Добавить `base-fn-name` в `:fn-schema`

**1.2 GraphConstraints протокол**

Файл: `components/storage-protocol/src/graphden/storage_protocol/interface.clj`

```clojure
(defprotocol GraphConstraints
  (validate-parent-same-schema! [this fn-id parent-fn-id])
  (validate-no-arg-override! [this fn-id arg-schema-id])
  (validate-arg-schema-belongs-to-fn! [this fn-id arg-schema-id])
  (validate-no-inheritance-cycle! [this fn-id parent-fn-id])
  (validate-no-dependency-cycle! [this owner-fn-id target-fn-id]))
```

**1.3 Contract tests для GraphConstraints**

Файл: `components/storage-protocol/test/graphden/storage_protocol/constraint_contract_test.clj`

**1.4 Реализовать ограничения в каждом storage**

---

### Фаза 2: CRUD операции

**2.1 StorageCRUD протокол**

Файл: `components/storage-protocol/src/graphden/storage_protocol/interface.clj`

```clojure
(defprotocol StorageCRUD
  (create [this entity-name data])      ; → id
  (read-by-id [this entity-name id])    ; → data | nil
  (update-by-id [this entity-name id data]) ; → data
  (delete-by-id [this entity-name id])  ; → boolean
  (query [this entity-name where]))     ; → [data...]
```

**2.2 Реализовать CRUD в каждом storage**

| Storage | Файлы |
|---------|-------|
| memory | `components/memory-storage/src/graphden/memory_storage/crud.clj` |
| postgres | `components/postgres-storage/src/graphden/postgres_storage/crud.clj` |
| datomic | `components/datomic-storage/src/graphden/datomic_storage/crud.clj` |

### Фаза 3: Исполнитель

**3.1 graph-resolver** — новый компонент

Файл: `components/graph-resolver/src/graphden/graph_resolver/interface.clj`

- `resolve-fn [storage fn-id]` → собрать граф с arg-values и родителями

**3.2 thunk-builder**

Файл: `components/executor/src/graphden/executor/thunks.clj`

- Создание LiteralThunk, FnRefThunk, LazyFnThunk

**3.3 executor**

Файл: `components/executor/src/graphden/executor/interface.clj`

- `execute [storage fn-id args context]` → результат
- Защита: max-depth, timeout

**3.4 base-functions** — реестр базовых функций

Файл: `components/base-functions/src/graphden/base_functions/interface.clj`

---

### Фаза 4: Базовые функции

**4.1 Арифметика и строки**
- +, -, *, /, mod
- str, subs, str/join, etc.

**4.2 Коллекции**
- first, rest, cons, conj
- get, assoc, dissoc

**4.3 Условия и HOF**
- if, cond
- map, filter, reduce

**4.4 I/O (клиент)**
- http-request (http-kit client)
- file operations

**4.5 I/O (сервер)**
- http-server (http-kit server)

**Запуск долгоживущих сервисов:**

Проблема: http-server должен работать постоянно, а не "вычислиться и вернуть результат".

Решение: **Service Manager** — отдельный компонент для управления долгоживущими процессами.

```clojure
(defprotocol ServiceManager
  (start-service [this service-fn-id])   ; → service-instance-id
  (stop-service [this instance-id])      ; → boolean
  (list-services [this])                 ; → [{:id :fn-id :status :started-at}]
  (service-status [this instance-id]))   ; → {:status :logs :metrics}
```

HTTP-server как базовая функция:
```clojure
;; base-fn-name: "graphden/http-server"
;; args: {:port int, :handler fn}
;;
;; Эта функция НЕ возвращает результат, а регистрирует сервис
(defn base-http-server [{:keys [port handler]} context]
  (let [server (http-kit/run-server
                 (fn [req] (execute-fn handler {:request req} context))
                 {:port (force port)})]
    ;; Возвращаем handle для остановки
    {:stop-fn server
     :type :http-server
     :port (force port)}))
```

Service Manager хранит запущенные сервисы и предоставляет API для управления.

---

### Фаза 5: UI/API

**5.1 REST API**
- CRUD endpoints для всех сущностей
- POST /execute — запуск функции

**5.2 Веб-интерфейс**
- Список функций
- Редактор графа
- Кнопка выполнения

---

## Часть 7: Планы на будущее

### Система типов (алгебра типов)

**Цель**: Статическая проверка типов, подсказки в UI, автоматический вывод типов.

**Что нужно:**
- Типы для fn-schema (входные типы → выходной тип)
- Параметрический полиморфизм (List[T], Map[K,V])
- Вывод типов для композиций (Hindley-Milner или подмножество)
- Типы для HOF: `map : (a -> b) -> List[a] -> List[b]`

**Сложность**: Высокая. Это отдельный большой проект.

---

### Git-like версионирование

**Цель**: История изменений, откат, ветки, merge.

**Модель:**
- Каждое изменение fn/arg-value — это commit
- Можно откатиться к любой версии
- Ветки для экспериментов
- Merge для объединения изменений

**Реализация:**
- Либо event sourcing (хранить все изменения)
- Либо snapshot + diff
- Интеграция с реальным git для экспорта/импорта

---

### Система пользователей и прав

**Цель**: Разграничение доступа.

**Модель прав:**
```
User:
  id, name, email

Role:
  id, name

Permission:
  - view(fn-id)      — видеть функцию
  - edit(fn-id)      — редактировать
  - execute(fn-id)   — выполнять
  - admin(fn-id)     — управлять правами

UserRole:
  user-id, role-id

RolePermission:
  role-id, permission
```

**Применение:**
- При CRUD операциях — проверка прав
- При выполнении — проверка execute permission
- В UI — фильтрация видимых функций

---

## Часть 8: Честные ограничения системы

### Что НЕ получится сделать элегантно

1. **Constraint #5 и #6** требуют тригеров/кода — нет декларативного способа в SQL/Datomic
2. **Взаимная рекурсия** — нельзя отличить "хорошую" от "плохой" статически
3. **Полный вывод типов** — это отдельная большая задача

### Что может сломаться

1. **Бесконечная рекурсия** — защита через depth/timeout, но ошибка в runtime
2. **Гонки при детекции циклов** — если два процесса создают arg-values одновременно
3. **Производительность на глубоких графах** — много запросов к БД

### Митигация

1. Агрессивное кэширование resolved graphs
2. Транзакции для атомарности
3. Мониторинг и алерты на глубокие/долгие исполнения
