# malli-data-schema

Реализация протокола DataSchema на основе [Malli](https://github.com/metosin/malli).

## Назначение

Предоставляет конкретную реализацию `DataSchemaBuilder` и `DataSchema` протоколов с использованием Malli для валидации данных.

## Зависимости

- `data-schema-protocol` — протоколы DataSchema и DataSchemaBuilder
- `metosin/malli` — библиотека валидации

## API

### create-builder

Создаёт новый builder для построения схемы:

```clojure
(require '[graphden.malli-data-schema.interface :as mds])

(def builder (mds/create-builder))
```

### schema->malli

Возвращает Malli-схему для сущности (для продвинутой интроспекции):

```clojure
(mds/schema->malli schema :user)
;; => [:map {:closed true}
;;     [:id :uuid]
;;     [:name :string]
;;     ...]
```

## Маппинг типов

| field-types | Malli |
|-------------|-------|
| `:uuid` | `:uuid` |
| `:text` | `:string` |
| `:int` | `:int` |
| `:bool` | `:boolean` |
| `:numeric` | `[:or :int :double]` |
| `:timestamptz` | `inst?` |
| `:jsonb` | Рекурсивная JSON-схема |
| `:bytes` | `bytes?` |
| `:ref` | `:uuid` (хранится как UUID) |
| `:enum` | `[:enum :val1 :val2 ...]` |
| `:union` | `[:or schema1 schema2 ...]` |

## JSONB схема

Для типа `:jsonb` используется рекурсивная Malli-схема:

```clojure
[:or
 :nil
 :boolean
 :int
 :double
 :string
 [:vector [:ref ::json]]
 [:map-of :string [:ref ::json]]]
```

## Валидации при построении

### Проверки add-enum

- `enum-name` должен быть keyword
- `enum-uuid` должен быть UUID
- `values` — непустой вектор `{:uuid ... :value ...}`
- Нет дублирующихся имён или UUID

### Проверки add-entity

- `entity-name` должен быть keyword
- `:id` зарезервирован
- Каждое поле должно иметь `:uuid` и `:type`
- Нет дублирующихся UUID (глобально)

### Проверки add-constraint

- `:type` должен быть известным (`:unique`)
- `:fields` — непустой вектор keywords
- Нет дублирующихся constraints

### Проверки build

- Все `:ref-entity` ссылаются на существующие сущности
- Все `:enum-name` ссылаются на существующие enum
- Union variants не пустые и не дублируются

## Пример полного использования

```clojure
(require '[graphden.malli-data-schema.interface :as mds]
         '[graphden.data-schema-protocol.interface :as ds])

(def schema
  (-> (mds/create-builder)
      ;; Enum
      (ds/add-enum :role #uuid "10000000-0000-0000-0000-000000000001"
                   [{:uuid #uuid "10000000-0000-0000-0000-000000000002" :value :admin}
                    {:uuid #uuid "10000000-0000-0000-0000-000000000003" :value :user}])
      ;; Entity
      (ds/add-entity :user #uuid "20000000-0000-0000-0000-000000000001"
                     {:name {:uuid #uuid "20000000-0000-0000-0000-000000000002"
                             :type :text}
                      :role {:uuid #uuid "20000000-0000-0000-0000-000000000003"
                             :type :enum
                             :enum-name :role}
                      :manager-id {:uuid #uuid "20000000-0000-0000-0000-000000000004"
                                   :type :ref
                                   :ref-entity :user
                                   :nullable? true}})
      ;; Constraint
      (ds/add-constraint :user {:type :unique :fields [:name]})
      ;; Build
      ds/build))

;; Использование
(ds/entities schema)
;; => (:user)

(ds/validate-entity schema :user
  {:id (random-uuid)
   :name "Alice"
   :role :admin
   :manager-id nil})
;; => nil (valid)

(ds/validate-entity schema :user
  {:id (random-uuid)
   :role :admin})
;; => {:errors {:name ["missing required key"]}}
```

## Union типы

```clojure
(ds/add-entity builder :arg-value #uuid "..."
  {:value {:uuid #uuid "..."
           :type :union
           :variants [{:type :ref :ref-entity :fn}  ; Ссылка на функцию
                      {:type :int}                   ; Литерал int
                      {:type :text}                  ; Литерал text
                      {:type :bool}]}})              ; Литерал bool
```

Варианты union не могут иметь `:nullable?` или `:uuid` — это атрибуты только для top-level полей.

## Тесты

```bash
bb test
```

Тесты покрывают:
- Маппинг типов
- Валидацию данных
- Ошибки построения (дубликаты, неверные ссылки)
- Union типы
- Constraints
