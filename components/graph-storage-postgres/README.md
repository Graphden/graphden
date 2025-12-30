# graph-storage-postgres

PostgreSQL storage, предварительно инициализированный схемой графа функций.

## Назначение

Production-ready storage для работы с графом функций. Объединяет:
- `postgres-storage` — PostgreSQL хранилище
- `graph-data-schema` — схема fn-schema, arg-schema, fn, arg-value

Не требует ручного вызова `sp/initialize`.

## Зависимости

- `postgres-storage` — реализация storage
- `graph-data-schema` — схема данных
- `malli-data-schema` — builder для схемы
- `storage-protocol` — протоколы

## API

### create-storage

Создаёт готовый к работе storage:

```clojure
(require '[graphden.graph-storage-postgres.interface :as gsp]
         '[graphden.storage-protocol.interface :as sp])

(let [storage (gsp/create-storage {:jdbc-url "jdbc:postgresql://localhost:5432/graphden"
                                   :username "graphden"
                                   :password "secret"})]
  ;; Сразу готов к использованию
  (sp/current-entities storage)
  ;; => #{:fn-schema :arg-schema :fn :arg-value}

  ;; ... работа с storage ...

  (sp/close storage))
```

### Параметры

| Параметр | Тип | Описание |
|----------|-----|----------|
| `:jdbc-url` | string | JDBC URL (required) |
| `:username` | string | Имя пользователя (required) |
| `:password` | string | Пароль (required) |
| `:pool-size` | int | Размер пула (default: 10) |

## Создаваемые таблицы

```sql
-- Схема функций
CREATE TABLE fn_schema (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name text UNIQUE NOT NULL,
  returned_type value_kind NOT NULL
);

-- Схемы аргументов
CREATE TABLE arg_schema (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  fn_schema_id uuid NOT NULL REFERENCES fn_schema(id),
  name text NOT NULL,
  type value_kind NOT NULL,
  UNIQUE(fn_schema_id, name)
);

-- Экземпляры функций
CREATE TABLE fn (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name text UNIQUE NOT NULL,
  fn_schema_id uuid NOT NULL REFERENCES fn_schema(id)
);

-- Значения аргументов
CREATE TABLE arg_value (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_fn_id uuid NOT NULL REFERENCES fn(id),
  arg_schema_id uuid NOT NULL REFERENCES arg_schema(id),
  value jsonb NOT NULL,
  UNIQUE(owner_fn_id, arg_schema_id)
);

-- Enum для типов значений
CREATE TYPE value_kind AS ENUM (
  'null', 'uuid', 'text', 'int', 'bool',
  'numeric', 'timestamptz', 'jsonb', 'bytes'
);
```

## Обработка ошибок

При ошибке инициализации (например, неверные credentials) storage закрывается:

```clojure
(try
  (sp/initialize storage schema)
  storage
  (catch Exception e
    (sp/close storage)
    (throw e)))
```

## Пример использования

```clojure
(require '[graphden.graph-storage-postgres.interface :as gsp]
         '[graphden.storage-protocol.interface :as sp])

;; Конфигурация из environment
(def config
  {:jdbc-url (System/getenv "DATABASE_URL")
   :username (System/getenv "DB_USER")
   :password (System/getenv "DB_PASS")
   :pool-size 20})

;; Создание storage
(def storage (gsp/create-storage config))

;; Использование...

;; Закрытие (освобождение connection pool)
(sp/close storage)
```

## Требования

- PostgreSQL 12+
- Права на CREATE TABLE, CREATE TYPE

## Тесты

```bash
# Требуется PostgreSQL
bb test
```
