# data-schema-protocol

Протокол для определения схемы данных.

## Назначение

Определяет абстрактный интерфейс для описания сущностей, их полей и ограничений. Схема используется storage-бэкендами для создания таблиц/коллекций.

## Зависимости

Нет внешних зависимостей (базовый протокол).

## Протоколы

### DataSchema

Протокол для чтения схемы данных:

```clojure
(defprotocol DataSchema
  (entities [this]
    "Возвращает последовательность имён сущностей.")

  (entity-uuid [this entity-name]
    "Возвращает UUID сущности (для детекции переименований).")

  (entity-fields [this entity-name]
    "Возвращает map полей: {field-name {:uuid ... :type ... :nullable? ...}}")

  (enums [this]
    "Возвращает map enum-типов: {enum-name {:uuid ... :values {...}}}")

  (enum-uuid [this enum-name]
    "Возвращает UUID enum-типа.")

  (validate-entity [this entity-name data]
    "Валидирует данные. Возвращает nil или {:errors {...}}")

  (entity-constraints [this entity-name]
    "Возвращает вектор ограничений: [{:type :unique :fields [:f1 :f2]}]"))
```

### DataSchemaBuilder

Протокол для построения схемы:

```clojure
(defprotocol DataSchemaBuilder
  (add-enum [this enum-name enum-uuid values]
    "Добавляет enum-тип.")

  (add-entity [this entity-name entity-uuid fields]
    "Добавляет сущность с полями.")

  (add-constraint [this entity-name constraint]
    "Добавляет ограничение к сущности.")

  (build [this]
    "Строит и валидирует финальную DataSchema."))
```

## Типы полей

### Базовые типы

| Тип | Описание | Атрибуты |
|-----|----------|----------|
| `:uuid` | UUID идентификатор | `:uuid`, `:type`, `:nullable?` |
| `:text` | Строка | `:uuid`, `:type`, `:nullable?` |
| `:int` | Целое число | `:uuid`, `:type`, `:nullable?` |
| `:bool` | Boolean | `:uuid`, `:type`, `:nullable?` |
| `:numeric` | Число (int или double) | `:uuid`, `:type`, `:nullable?` |
| `:timestamptz` | Timestamp с timezone | `:uuid`, `:type`, `:nullable?` |
| `:jsonb` | JSON данные | `:uuid`, `:type`, `:nullable?` |
| `:bytes` | Бинарные данные | `:uuid`, `:type`, `:nullable?` |

### Специальные типы

| Тип | Описание | Дополнительные атрибуты |
|-----|----------|------------------------|
| `:ref` | Ссылка на другую сущность | `:ref-entity` (имя сущности) |
| `:enum` | Перечисление | `:enum-name` (имя enum-типа) |
| `:union` | Один из нескольких типов | `:variants` (вектор спецификаций) |

## Неявное поле :id

Каждая сущность автоматически получает поле `:id` типа `:uuid` — первичный ключ.

## UUID для идентификации

Каждый элемент схемы имеет стабильный UUID:

- **Сущности** — `entity-uuid`
- **Поля** — `:uuid` в спецификации поля
- **Enum-типы** — `:uuid` в описании enum
- **Enum-значения** — UUID для каждого значения

UUID позволяет storage-бэкендам отличать переименование от удаления/создания.

## Пример использования

```clojure
(require '[graphden.data-schema-protocol.interface :as ds])

;; Чтение схемы
(ds/entities schema)
;; => [:user :post :comment]

(ds/entity-fields schema :user)
;; => {:name {:uuid #uuid "..." :type :text}
;;     :email {:uuid #uuid "..." :type :text :nullable? true}
;;     :role {:uuid #uuid "..." :type :enum :enum-name :user-role}}

(ds/enums schema)
;; => {:user-role {:uuid #uuid "..."
;;                 :values {:admin #uuid "..."
;;                          :user #uuid "..."}}}

;; Валидация
(ds/validate-entity schema :user {:id (random-uuid) :name "Alice"})
;; => nil (valid)

(ds/validate-entity schema :user {:id (random-uuid)})
;; => {:errors {:name ["missing required key"]}}

;; Ограничения
(ds/entity-constraints schema :user)
;; => [{:type :unique :fields [:email]}]
```

## Построение схемы

```clojure
(require '[graphden.data-schema-protocol.interface :as ds])

(-> builder
    ;; Сначала enum-типы
    (ds/add-enum :user-role #uuid "..."
                 [{:uuid #uuid "..." :value :admin}
                  {:uuid #uuid "..." :value :user}])

    ;; Затем сущности
    (ds/add-entity :user #uuid "..."
                   {:name {:uuid #uuid "..." :type :text}
                    :role {:uuid #uuid "..." :type :enum :enum-name :user-role}})

    ;; Ограничения
    (ds/add-constraint :user {:type :unique :fields [:name]})

    ;; Построение
    ds/build)
```

## Реализации

- [malli-data-schema](../malli-data-schema/) — реализация на основе Malli

## Тесты

Contract tests находятся в реализациях (например, `malli-data-schema`).
