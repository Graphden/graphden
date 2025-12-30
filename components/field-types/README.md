# field-types

Централизованное определение поддерживаемых типов полей.

## Назначение

Единый источник истины для типов данных, поддерживаемых всеми компонентами системы. Это гарантирует консистентность между:

- `data-schema-protocol` — определение схем
- `malli-data-schema` — валидация
- `*-storage` — хранение в БД

## Зависимости

Нет внешних зависимостей (листовой компонент).

## API

### types

Map с метаданными каждого типа:

```clojure
(def types
  {:uuid        {:description "UUID identifier"}
   :text        {:description "Text/string value"}
   :int         {:description "Integer number"}
   :bool        {:description "Boolean true/false"}
   :numeric     {:description "Numeric value (int or double)"}
   :timestamptz {:description "Timestamp with timezone"}
   :jsonb       {:description "JSON data"}
   :bytes       {:description "Binary data"}})
```

### supported-types

Set поддерживаемых типов:

```clojure
(def supported-types
  #{:uuid :text :int :bool :numeric :timestamptz :jsonb :bytes})
```

## Типы данных

| Тип | Clojure | PostgreSQL | Datomic |
|-----|---------|------------|---------|
| `:uuid` | `java.util.UUID` | `uuid` | `:db.type/uuid` |
| `:text` | `String` | `text` | `:db.type/string` |
| `:int` | `Long` | `bigint` | `:db.type/long` |
| `:bool` | `Boolean` | `boolean` | `:db.type/boolean` |
| `:numeric` | `Number` | `numeric` | `:db.type/double` |
| `:timestamptz` | `java.time.Instant` | `timestamptz` | `:db.type/instant` |
| `:jsonb` | Clojure data | `jsonb` | EDN string |
| `:bytes` | `byte[]` | `bytea` | `:db.type/bytes` |

## Специальные типы (не в этом компоненте)

Следующие типы определены в `data-schema-protocol`, но не входят в `field-types`:

| Тип | Описание | Хранение |
|-----|----------|----------|
| `:ref` | Ссылка на сущность | UUID |
| `:enum` | Перечисление | Зависит от storage |
| `:union` | Один из типов | JSONB |

## Пример использования

```clojure
(require '[graphden.field-types.interface :as ft])

;; Проверка поддержки типа
(contains? ft/supported-types :text) ; => true
(contains? ft/supported-types :xml)  ; => false

;; Получение описания
(:description (get ft/types :uuid))
; => "UUID identifier"

;; Итерация по типам
(doseq [[type-kw info] ft/types]
  (println type-kw "->" (:description info)))
```

## Расширение типов

Для добавления нового типа:

1. Добавить в `types` map в этом компоненте
2. Добавить Malli-схему в `malli-data-schema`
3. Добавить маппинг в каждом storage (`memory`, `postgres`, `datomic`)

## Тесты

```bash
bb test
```

Тесты проверяют консистентность между `types` и `supported-types`.
