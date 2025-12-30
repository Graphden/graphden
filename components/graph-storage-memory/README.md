# graph-storage-memory

In-memory storage, предварительно инициализированный схемой графа функций.

## Назначение

Готовый к использованию storage для работы с графом функций. Объединяет:
- `memory-storage` — in-memory хранилище
- `graph-data-schema` — схема fn-schema, arg-schema, fn, arg-value

Не требует ручного вызова `sp/initialize`.

## Зависимости

- `memory-storage` — реализация storage
- `graph-data-schema` — схема данных
- `malli-data-schema` — builder для схемы
- `storage-protocol` — протоколы

## API

### create-storage

Создаёт готовый к работе storage:

```clojure
(require '[graphden.graph-storage-memory.interface :as gsm]
         '[graphden.storage-protocol.interface :as sp])

(let [storage (gsm/create-storage)]
  ;; Сразу готов к использованию
  (sp/current-entities storage)
  ;; => #{:fn-schema :arg-schema :fn :arg-value}

  ;; ... работа с storage ...

  (sp/close storage))
```

## Сущности

После создания storage содержит все сущности графа:

| Сущность | Описание |
|----------|----------|
| `:fn-schema` | Схема функции (сигнатура) |
| `:arg-schema` | Схема аргумента |
| `:fn` | Экземпляр функции |
| `:arg-value` | Значение аргумента |

## Enum типы

| Enum | Значения |
|------|----------|
| `:value-kind` | `:null`, `:uuid`, `:text`, `:int`, `:bool`, `:numeric`, `:timestamptz`, `:jsonb`, `:bytes` |

## Обработка ошибок

При ошибке инициализации storage автоматически закрывается:

```clojure
;; Внутри create-storage:
(try
  (sp/initialize storage schema)
  storage
  (catch Exception e
    (sp/close storage)  ; Очистка при ошибке
    (throw e)))
```

## Использование

### Для разработки

```clojure
(require '[graphden.graph-storage-memory.interface :as gsm])

(def storage (gsm/create-storage))
;; Готово к работе
```

### Для тестов

```clojure
(deftest my-test
  (let [storage (gsm/create-storage)]
    (try
      ;; Тесты...
      (finally
        (sp/close storage)))))
```

## Тесты

```bash
bb test
```

Тесты проверяют:
- Наличие всех сущностей графа
- Наличие enum `:value-kind`
- Корректность полей каждой сущности
- Очистку при ошибке инициализации
