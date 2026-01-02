# postgres-storage

PostgreSQL реализация протоколов Storage и StorageIntrospection.

## Назначение

Production-ready хранилище на базе PostgreSQL с:
- Connection pooling (HikariCP)
- DDL-миграциями
- Поддержкой всех типов полей
- Кэшированием метаданных

## Зависимости

- `storage-protocol` — протоколы Storage и StorageIntrospection
- `data-schema-protocol` — протокол DataSchema
- PostgreSQL 12+

### Clojure зависимости

- `com.zaxxer/HikariCP` — connection pool
- `org.postgresql/postgresql` — JDBC драйвер
- `com.github.seancorfield/next.jdbc` — JDBC обёртка

## API

### create-storage

Создаёт новый экземпляр PostgreSQL storage:

```clojure
(require '[graphden.postgres-storage.interface :as pg]
         '[graphden.storage-protocol.interface :as sp])

(def storage
  (pg/create-storage {:jdbc-url "jdbc:postgresql://localhost:5432/mydb"
                      :username "user"
                      :password "pass"}))

(sp/initialize storage my-schema)

;; Использование...

(sp/close storage)
```

### Параметры подключения

| Параметр | Тип | По умолчанию | Описание |
|----------|-----|--------------|----------|
| `:jdbc-url` | string | (required) | JDBC URL |
| `:username` | string | (required) | Имя пользователя |
| `:password` | string | (required) | Пароль |
| `:pool-size` | int | 10 | Размер пула |
| `:min-idle` | int | 2 | Минимум idle соединений |
| `:connection-timeout` | ms | 30000 | Таймаут подключения |
| `:idle-timeout` | ms | 600000 | Таймаут простоя |
| `:max-lifetime` | ms | 1800000 | Макс. время жизни соединения |
| `:leak-detection-threshold` | ms | 60000 | Детекция утечек |

## Маппинг типов

| field-types | PostgreSQL |
|-------------|------------|
| `:uuid` | `uuid` |
| `:text` | `text` |
| `:int` | `bigint` |
| `:bool` | `boolean` |
| `:numeric` | `numeric` |
| `:timestamptz` | `timestamptz` |
| `:jsonb` | `jsonb` |
| `:bytes` | `bytea` |
| `:ref` | `uuid` (FK) |
| `:enum` | PostgreSQL ENUM |
| `:union` | `jsonb` |

## DDL операции

### Создание таблицы

```sql
CREATE TABLE IF NOT EXISTS user (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name text NOT NULL,
  email text,
  role user_role NOT NULL
);
```

### Создание enum

```sql
CREATE TYPE user_role AS ENUM ('admin', 'user', 'guest');
```

### Добавление поля

```sql
ALTER TABLE user ADD COLUMN bio text;
```

### Переименование

```sql
ALTER TABLE old_name RENAME TO new_name;
ALTER TABLE user RENAME COLUMN old_field TO new_field;
```

## Метаданные

Хранятся в таблице `_schema_metadata`:

```sql
CREATE TABLE _schema_metadata (
  uuid uuid PRIMARY KEY,
  kind text NOT NULL,        -- 'entity', 'field', 'enum', 'enum_value'
  name text NOT NULL,
  parent_uuid uuid,          -- для field: entity uuid
  field_type text,           -- для field: тип поля
  field_nullable boolean     -- для field: nullable?
);
```

## Потокобезопасность

- Connection pool управляется HikariCP
- Метаданные кэшируются с блокировкой
- `initialize` инвалидирует кэш

## Миграции

### Поддерживаемые изменения

| Операция | DDL |
|----------|-----|
| Добавление сущности | `CREATE TABLE` |
| Добавление поля | `ALTER TABLE ADD COLUMN` |
| Переименование сущности | `ALTER TABLE RENAME` |
| Переименование поля | `ALTER TABLE RENAME COLUMN` |
| Расширение типа | `ALTER TABLE ALTER COLUMN TYPE` |
| Nullable: false→true | `ALTER TABLE ALTER COLUMN DROP NOT NULL` |

### Проверки перед миграцией

- Удаление сущности → ошибка
- Удаление поля → ошибка
- Сужение типа → ошибка
- Nullable: true→false → ошибка

## Пример полного использования

```clojure
(require '[graphden.postgres-storage.interface :as pg]
         '[graphden.storage-protocol.interface :as sp]
         '[graphden.graph-data-schema.interface :as graph]
         '[graphden.malli-data-schema.interface :as mds])

;; Создаём схему графа
(def schema (graph/build-schema (mds/create-builder)))

;; Создаём storage
(def storage
  (pg/create-storage {:jdbc-url "jdbc:postgresql://localhost:5432/graphden"
                      :username "graphden"
                      :password "secret"}))

;; Инициализируем (создаём таблицы)
(try
  (let [changes (sp/initialize storage schema)]
    (println "Created entities:" (get-in changes [:entities :created]))
    (println "Created enums:" (get-in changes [:enums :created])))
  (finally
    (sp/close storage)))
```

## Интроспекция

```clojure
;; Текущие сущности (таблицы)
(sp/current-entities storage)
;; => #{:fn-schema :arg-schema :fn :arg-value}

;; Поля сущности
(sp/current-fields storage :fn-schema)
;; => {:name {:type :text :nullable? false}
;;     :returned-type {:type :enum :nullable? false}}

;; Enum типы
(sp/current-enums storage)
;; => #{:value-kind}

;; Значения enum
(sp/current-enum-values storage :value-kind)
;; => #{:null :uuid :text :int :bool :numeric :timestamptz :jsonb :bytes}
```

## Структура модулей

| Модуль | Назначение |
|--------|------------|
| `core.clj` | Основной Storage record, pool management |
| `util.clj` | Маппинг типов, SQL helpers |
| `metadata.clj` | Операции с `_schema_metadata` |
| `introspection.clj` | Чтение структуры БД |
| `ddl.clj` | DDL операции |
| `migration.clj` | Логика миграции |

## Ограничения именования

### Kebab-case → Snake_case

Все идентификаторы (имена сущностей, полей, enum-ов) преобразуются
из kebab-case (`:my-field`) в snake_case (`my_field`) для SQL.

**Коллизии запрещены:**

```clojure
;; Эти имена дадут одинаковый SQL идентификатор
:my-field  ; → my_field
:my_field  ; → my_field (коллизия!)

;; При попытке использовать оба:
(sp/initialize storage schema) ; => throws "Snake_case naming collision"
```

### Валидные SQL идентификаторы

Имена должны соответствовать паттерну `^[a-z][a-z0-9_]*$`:
- Начинаются с буквы a-z
- Содержат только буквы, цифры и подчёркивания
- Максимальная длина — 63 символа (PostgreSQL ограничение)

**Примеры:**

```clojure
;; Валидные
:user
:user-profile
:user_profile
:item123

;; Невалидные (вызовут ошибку)
:123user      ; начинается с цифры
:User         ; заглавные буквы
:user-name!   ; специальные символы
```

## Требования

- PostgreSQL 12+ (для `gen_random_uuid()`)
- Права на CREATE TABLE, CREATE TYPE, ALTER TABLE

## Тесты

```bash
# Требуется запущенный PostgreSQL
docker run -d -p 5432:5432 -e POSTGRES_PASSWORD=test postgres:15

bb test
```

Тесты покрывают:
- Connection pool
- DDL операции
- Миграции
- Интроспекция
- Метаданные
