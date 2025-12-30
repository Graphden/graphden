# graph-storage-datomic

Datomic storage, предварительно инициализированный схемой графа функций.

## Назначение

Datomic-based storage для работы с графом функций. Объединяет:
- `datomic-storage` — Datomic хранилище
- `graph-data-schema` — схема fn-schema, arg-schema, fn, arg-value

Не требует ручного вызова `sp/initialize`.

## Зависимости

- `datomic-storage` — реализация storage
- `graph-data-schema` — схема данных
- `malli-data-schema` — builder для схемы
- `storage-protocol` — протоколы

## API

### create-storage

Создаёт готовый к работе storage:

```clojure
(require '[graphden.graph-storage-datomic.interface :as gsd]
         '[graphden.storage-protocol.interface :as sp])

;; Без параметров — auto-generated db-name
(let [storage (gsd/create-storage)]
  (sp/current-entities storage)
  ;; => #{:fn-schema :arg-schema :fn :arg-value}
  (sp/close storage))

;; С указанием db-name
(let [storage (gsd/create-storage {:db-name "my-graph"})]
  ;; ...
  (sp/close storage))
```

### Параметры

| Параметр | Тип | По умолчанию | Описание |
|----------|-----|--------------|----------|
| `:db-name` | string | auto-generated | Имя базы данных |

## Автогенерация имени БД

При вызове без параметров создаётся уникальное имя:

```clojure
(str "graph-" (System/currentTimeMillis) "-" (rand-int 10000))
;; => "graph-1703936400000-4521"
```

Это удобно для:
- Изолированных тестов
- Параллельного запуска
- Ephemeral окружений

## Создаваемые атрибуты

```clojure
;; fn-schema
:fn-schema/name         ; :db.type/string, :db.unique/value
:fn-schema/returned-type ; :db.type/ref (enum)

;; arg-schema
:arg-schema/fn-schema-id ; :db.type/ref
:arg-schema/name         ; :db.type/string
:arg-schema/type         ; :db.type/ref (enum)

;; fn
:fn/name                 ; :db.type/string, :db.unique/value
:fn/fn-schema-id         ; :db.type/ref

;; arg-value
:arg-value/owner-fn-id   ; :db.type/ref
:arg-value/arg-schema-id ; :db.type/ref
:arg-value/value         ; :db.type/string (EDN)

;; enum values
:value-kind.value/null
:value-kind.value/uuid
:value-kind.value/text
;; ... и т.д.
```

## Обработка ошибок

При ошибке инициализации storage закрывается:

```clojure
(try
  (sp/initialize storage schema)
  storage
  (catch Exception e
    (sp/close storage)
    (throw e)))
```

## Использование для тестов

```clojure
(deftest graph-operations-test
  (let [storage (gsd/create-storage)]  ; Уникальная БД для теста
    (try
      ;; Тесты...
      (finally
        (sp/close storage)))))  ; Удаляет БД
```

## Преимущества Datomic

- **Иммутабельная история** — все изменения сохраняются
- **Временные запросы** — можно смотреть состояние на любой момент
- **ACID** — полные транзакции
- **Datalog** — мощный язык запросов

## Тесты

```bash
bb test
```
