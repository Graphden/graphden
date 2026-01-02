# datomic-storage

Datomic реализация протоколов Storage и StorageIntrospection.

## Назначение

Storage на базе Datomic с:
- Иммутабельной историей
- EAVT моделью данных
- Поддержкой Datomic Local и Pro

## Зависимости

- `storage-protocol` — протоколы Storage и StorageIntrospection
- `data-schema-protocol` — протокол DataSchema
- Datomic Local или Datomic Pro/Cloud

### Clojure зависимости

- `com.datomic/local` — Datomic Local (для разработки)
- `com.datomic/client-cloud` — Datomic Cloud (опционально)

## API

### create-storage

Создаёт новый экземпляр Datomic storage:

```clojure
(require '[graphden.datomic-storage.interface :as datomic]
         '[graphden.storage-protocol.interface :as sp])

;; In-memory (по умолчанию)
(def storage (datomic/create-storage {:db-name "my-db"}))

(sp/initialize storage my-schema)

;; Использование...

(sp/close storage)
```

### Параметры

| Параметр | Тип | По умолчанию | Описание |
|----------|-----|--------------|----------|
| `:db-name` | string | "graphden" | Имя базы данных |
| `:client-config` | map | in-memory | Конфигурация клиента |

### Конфигурации

```clojure
;; In-memory (для тестов и разработки)
(datomic/create-storage {:db-name "test-db"})

;; Файловое хранилище
(datomic/create-storage
  {:db-name "my-db"
   :client-config {:server-type :datomic-local
                   :storage-dir "/path/to/data"
                   :system "my-system"}})

;; Peer-server (Datomic Pro)
(datomic/create-storage
  {:db-name "my-db"
   :client-config {:server-type :peer-server
                   :endpoint "localhost:8998"
                   :secret "your-secret"
                   :access-key "your-key"}})
```

### default-local-config

Предопределённая конфигурация для разработки:

```clojure
datomic/default-local-config
;; => {:server-type :datomic-local
;;     :storage-dir :mem
;;     :system "graphden-dev"}
```

## Маппинг типов

| field-types | Datomic |
|-------------|---------|
| `:uuid` | `:db.type/uuid` |
| `:text` | `:db.type/string` |
| `:int` | `:db.type/long` |
| `:bool` | `:db.type/boolean` |
| `:numeric` | `:db.type/bigdec` |
| `:timestamptz` | `:db.type/instant` |
| `:jsonb` | `:db.type/string` (EDN) |
| `:bytes` | `:db.type/bytes` |
| `:ref` | `:db.type/ref` |
| `:enum` | `:db.type/ref` (idents) |
| `:union` | `:db.type/string` (EDN) |

## Схема атрибутов

### Именование

Атрибуты именуются как `entity/field`:

```clojure
:user/name      ; поле name сущности user
:user/email     ; поле email сущности user
```

### Enum значения

Enum значения создаются как idents:

```clojure
:status.value/active    ; значение :active enum :status
:status.value/inactive  ; значение :inactive enum :status
```

### Метаданные

Метаданные хранятся в атрибутах `graphden.metadata/*`:

```clojure
{:graphden.metadata/uuid #uuid "..."
 :graphden.metadata/kind :entity    ; :entity, :field, :enum, :enum-value
 :graphden.metadata/name :user
 :graphden.metadata/parent-uuid #uuid "..."     ; для field/enum-value
 :graphden.metadata/field-type :text            ; для field
 :graphden.metadata/field-nullable false}       ; для field
```

## Unique constraints

Поддерживаются только single-field unique constraints:

```clojure
;; Работает
(ds/add-constraint :user {:type :unique :fields [:email]})
;; => :db/unique :db.unique/value

;; Composite unique НЕ поддерживается напрямую
(ds/add-constraint :user {:type :unique :fields [:tenant-id :name]})
;; => Игнорируется (требует application-level проверки)
```

## Миграции

### Поддерживаемые изменения

| Операция | Реализация |
|----------|------------|
| Добавление сущности | Добавление атрибутов |
| Добавление поля | `d/transact` нового атрибута |
| Добавление enum значения | Создание нового ident |
| Переименование | Через метаданные (атрибуты не переименовываются) |

### Особенности Datomic

В Datomic атрибуты нельзя удалить или переименовать напрямую. Переименования отслеживаются через метаданные.

## Потокобезопасность

- Все операции защищены блокировкой
- Connection создаётся при `initialize`
- Client и connection хранятся в атомах

## Пример полного использования

```clojure
(require '[graphden.datomic-storage.interface :as datomic]
         '[graphden.storage-protocol.interface :as sp]
         '[graphden.graph-data-schema.interface :as graph]
         '[graphden.malli-data-schema.interface :as mds])

;; Создаём схему графа
(def schema (graph/build-schema (mds/create-builder)))

;; Создаём storage
(def storage (datomic/create-storage {:db-name "graphden-dev"}))

;; Инициализируем
(try
  (let [changes (sp/initialize storage schema)]
    (println "Created:" (get-in changes [:entities :created])))
  (finally
    (sp/close storage)))
```

## Интроспекция

```clojure
;; Текущие сущности (namespaces атрибутов)
(sp/current-entities storage)
;; => #{:fn-schema :arg-schema :fn :arg-value}

;; Поля сущности (из метаданных)
(sp/current-fields storage :fn-schema)
;; => {:name {:type :text :nullable? false}
;;     :returned-type {:type :enum :nullable? false}}

;; Enum типы (из .value namespaces)
(sp/current-enums storage)
;; => #{:value-kind}

;; Значения enum
(sp/current-enum-values storage :value-kind)
;; => #{:null :uuid :text :int :bool :numeric :timestamptz :jsonb :bytes}
```

## Особенности close

`sp/close` удаляет базу данных. Это сделано для:
- Очистки тестовых данных
- Идемпотентности в dev-окружении

Для продакшена с персистентностью используйте отдельную стратегию управления БД.

## Ограничения

- Composite unique constraints не поддерживаются (Datomic ограничение)
- `:jsonb` и `:union` хранятся как EDN строки (не нативный JSON)
- Переименования требуют обновления метаданных, не атрибутов
- **Неатомарное обновление метаданных**: При обновлении схемы метаданные обновляются
  в двух транзакциях (retract старых, assert новых), т.к. Datomic не позволяет
  retract и assert одного unique значения в одной транзакции.
  В случае сбоя между транзакциями метаданные могут быть потеряны.

## Тесты

```bash
bb test
```

Тесты покрывают:
- Инициализацию с in-memory storage
- Создание схемы атрибутов
- Интроспекция
- Миграции (добавление полей/значений)
- Метаданные

## Требования

- Java 11+
- Datomic Local (включён) или Datomic Pro license
