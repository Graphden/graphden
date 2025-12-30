# storage-protocol

Протоколы для реализации storage-бэкендов.

## Назначение

Определяет интерфейсы, которые должны реализовать все storage-бэкенды (memory, PostgreSQL, Datomic). Обеспечивает:

- Единый контракт для всех хранилищ
- UUID-based миграции (детекция переименований)
- Безопасные изменения схемы (запрет деструктивных)
- Утилиты для вычисления diff между схемами

## Зависимости

- `data-schema-protocol` — протокол DataSchema для чтения схемы

## Протоколы

### Storage

```clojure
(defprotocol Storage
  (initialize [this schema]
    "Синхронизирует storage с DataSchema.
     Возвращает map изменений или бросает исключение на деструктивных.")

  (close [this]
    "Освобождает ресурсы (соединения, хэндлы)."))
```

### StorageIntrospection

```clojure
(defprotocol StorageIntrospection
  (current-entities [this]
    "Возвращает set имён сущностей в storage.")

  (current-fields [this entity-name]
    "Возвращает map полей сущности: {field-name {:type :text ...}}")

  (current-enums [this]
    "Возвращает set имён enum-типов.")

  (current-enum-values [this enum-name]
    "Возвращает set значений enum.")

  (schema-metadata [this]
    "Возвращает сохранённые UUID→name маппинги."))
```

## Безопасность миграций

### Разрешённые изменения

| Изменение | Пример |
|-----------|--------|
| Добавление сущности | Новая таблица |
| Добавление поля | Новая колонка |
| Переименование | UUID остаётся, имя меняется |
| Расширение типа | `int` → `numeric`, `text` → `jsonb` |
| Nullable: false→true | Разрешение NULL |

### Запрещённые изменения (бросают исключение)

| Изменение | Причина |
|-----------|---------|
| Удаление сущности | Потеря данных |
| Удаление поля | Потеря данных |
| Сужение типа | `text` → `int` — невозможная конверсия |
| Nullable: true→false | Существующие NULL станут невалидными |

## Типы и эквивалентность

```clojure
;; Расширение типов (без потери данных)
(def type-widening
  {:int #{:numeric :text :jsonb}
   :bool #{:text :jsonb}
   :numeric #{:text :jsonb}
   :text #{:jsonb}
   :uuid #{:text}
   :timestamptz #{:text}})

;; Эквивалентные типы (хранятся одинаково)
(def type-equivalents
  #{#{:uuid :ref}      ; :ref хранится как UUID
    #{:jsonb :union}}) ; :union хранится как JSONB
```

## Утилиты

### Проверка безопасности изменений

```clojure
(safe-type-change? :int :numeric)   ; => true
(safe-type-change? :text :int)      ; => false

(safe-nullable-change? false true)  ; => true
(safe-nullable-change? true false)  ; => false
```

### Проверка с выбросом исключений

```clojure
(check-type-change! :user :email :text :int)
;; => throws ExceptionInfo {:type :destructive-change ...}

(check-removed! "entities" old-uuids new-uuids name-fn)
;; => throws if any UUID removed
```

### Вычисление diff

```clojure
(build-metadata-from-schema schema)
;; => {:entities {uuid->name}
;;     :fields {uuid->{:entity :field}}
;;     :enums {uuid->name}
;;     :enum-values {uuid->{:enum :value}}}

(compute-entity-changes old-metadata schema)
;; => {:created [:new-entity] :renamed {:old :new}}

(compute-field-changes old-metadata schema)
;; => {:created [{:entity :e :field :f}]
;;     :renamed [{:entity :e :old-field :o :new-field :n}]}
```

## Пример использования

```clojure
(require '[graphden.storage-protocol.interface :as sp])

;; Имплементация создаёт storage
(def storage (create-my-storage))

;; Инициализация/миграция
(let [changes (sp/initialize storage my-schema)]
  (println "Created entities:" (get-in changes [:entities :created]))
  (println "Renamed fields:" (get-in changes [:fields :renamed])))

;; Интроспекция
(sp/current-entities storage)     ; => #{:user :post}
(sp/current-fields storage :user) ; => {:name {:type :text} ...}

;; Закрытие
(sp/close storage)
```

## Планируемые расширения

### StorageCRUD (в разработке)

```clojure
(defprotocol StorageCRUD
  (create [this entity-name data])
  (read-by-id [this entity-name id])
  (update-by-id [this entity-name id data])
  (delete-by-id [this entity-name id])
  (query [this entity-name where]))
```

### GraphConstraints (в разработке)

Протокол для ограничений целостности графа функций:

```clojure
(defprotocol GraphConstraints
  (validate-parent-same-schema! [this fn-id parent-fn-id])
  (validate-no-arg-override! [this fn-id arg-schema-id])
  (validate-arg-schema-belongs-to-fn! [this fn-id arg-schema-id])
  (validate-no-inheritance-cycle! [this fn-id parent-fn-id])
  (validate-no-dependency-cycle! [this owner-fn-id target-fn-id]))
```

## Реализации

- [memory-storage](../memory-storage/) — In-memory (для тестов и разработки)
- [postgres-storage](../postgres-storage/) — PostgreSQL
- [datomic-storage](../datomic-storage/) — Datomic

## Тесты

```bash
bb test
```

Тесты покрывают:
- Проверку безопасности типов (`safe-type-change?`, `safe-nullable-change?`)
- Утилиты проверки (`check-type-change!`, `check-nullable-change!`)
- Построение metadata (`build-metadata-from-schema`)
- Вычисление изменений (`build-first-init-changes`, `check-all-removals!`)
