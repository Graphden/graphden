# graph-data-schema

Схема данных для графа функций.

## Назначение

Определяет схему сущностей для визуальной среды функционального программирования:

- **fn-schema** — схема функции (сигнатура)
- **arg-schema** — схема аргумента функции
- **fn** — экземпляр функции
- **arg-value** — значение аргумента (литерал или ссылка)

## Зависимости

- `data-schema-protocol` — протоколы DataSchema и DataSchemaBuilder
- `field-types` — поддерживаемые типы данных

## Сущности

### fn-schema

Схема функции — определяет сигнатуру:

| Поле | Тип | Описание |
|------|-----|----------|
| `id` | uuid | Первичный ключ (неявный) |
| `name` | text | Уникальное имя функции |
| `returned-type` | enum:value-kind | Тип возвращаемого значения |

**Constraints:** `UNIQUE(name)`

### arg-schema

Схема аргумента — определяет параметр функции:

| Поле | Тип | Описание |
|------|-----|----------|
| `id` | uuid | Первичный ключ (неявный) |
| `fn-schema-id` | ref:fn-schema | К какой функции относится |
| `name` | text | Имя аргумента |
| `type` | enum:value-kind | Тип аргумента |

**Constraints:** `UNIQUE(fn-schema-id, name)`

### fn

Экземпляр функции — конкретное применение схемы:

| Поле | Тип | Описание |
|------|-----|----------|
| `id` | uuid | Первичный ключ (неявный) |
| `name` | text | Уникальное имя экземпляра |
| `fn-schema-id` | ref:fn-schema | Какую схему реализует |

**Constraints:** `UNIQUE(name)`

### arg-value

Значение аргумента для экземпляра функции:

| Поле | Тип | Описание |
|------|-----|----------|
| `id` | uuid | Первичный ключ (неявный) |
| `owner-fn-id` | ref:fn | Какой fn принадлежит |
| `arg-schema-id` | ref:arg-schema | Какой аргумент |
| `value` | union | Значение (см. ниже) |

**Constraints:** `UNIQUE(owner-fn-id, arg-schema-id)`

## Union type для value

Поле `value` может содержать:

1. **ref:fn** — ссылка на другую функцию (результат будет вычислен)
2. **Литералы** — uuid, text, int, bool, numeric, timestamptz, jsonb, bytes

```clojure
;; Литерал
{:value 42}

;; Ссылка на функцию
{:value #uuid "fn-id-here"}
```

## Enum value-kind

Перечисление поддерживаемых типов:

```clojure
#{:null          ; void/nil
  :uuid          ; UUID
  :text          ; String
  :int           ; Integer
  :bool          ; Boolean
  :numeric       ; Number
  :timestamptz   ; Timestamp
  :jsonb         ; JSON
  :bytes}        ; Binary
```

`:null` используется для функций без возвращаемого значения (side-effects).

## Пример графа

```
fn-schema: http-request
  returned-type: :jsonb
  arg-schemas:
    - url: :text
    - method: :text
    - body: :jsonb

fn: get-users (schema: http-request)
  arg-values:
    - url: "https://api.example.com/users"
    - method: "GET"

fn: create-user (schema: http-request)
  arg-values:
    - url: ref<get-users>  ; Используем URL из другой fn
    - method: "POST"
    - body: {...}
```

## API

### build-schema

Строит схему графа используя предоставленный builder:

```clojure
(require '[graphden.graph-data-schema.interface :as graph]
         '[graphden.malli-data-schema.interface :as mds])

(def schema
  (graph/build-schema (mds/create-builder)))

;; Проверка
(ds/entities schema)
;; => (:fn-schema :arg-schema :fn :arg-value)

(ds/enums schema)
;; => {:value-kind {:uuid #uuid "..." :values {...}}}
```

## Стабильные UUID

Каждый элемент схемы имеет фиксированный UUID, сгенерированный один раз:

```clojure
;; Сущности
fn-schema-entity-uuid  = #uuid "dc2df695-..."
arg-schema-entity-uuid = #uuid "946c1f9c-..."
fn-entity-uuid         = #uuid "986e8a2a-..."
arg-value-entity-uuid  = #uuid "afb02fb7-..."

;; Enum
value-kind-enum-uuid   = #uuid "b79e6e8b-..."
```

Это позволяет storage-бэкендам отслеживать переименования через UUID.

## Расширения (в разработке)

### parent-fn-id для наследования

```clojure
fn:
  parent-fn-id: ref<fn> (nullable)
```

Позволит наследовать arg-values от родительской функции:

```
fn: base-api
  arg-values: {url: "https://api.example.com", timeout: 30}

fn: auth-api (parent: base-api)
  arg-values: {headers: {"Authorization": "..."}}
  ; Наследует: url, timeout
```

### base-fn-name для базовых функций

```clojure
fn-schema:
  base-fn-name: text (nullable)
```

Имя Clojure-функции для выполнения. `null` означает составную функцию.

### required для аргументов

```clojure
arg-schema:
  required: bool (default true)
```

## Тесты

```bash
bb test
```

Тесты покрывают:
- Наличие всех сущностей
- Наличие enum value-kind
- Корректность полей каждой сущности
- Валидацию данных
