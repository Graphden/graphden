# memory-storage

In-memory реализация протоколов Storage и StorageIntrospection.

## Назначение

Хранилище в памяти для:
- Разработки и отладки
- Юнит-тестов
- Прототипирования

Данные теряются при завершении процесса.

## Зависимости

- `storage-protocol` — протоколы Storage и StorageIntrospection
- `data-schema-protocol` — протокол DataSchema

## API

### create-storage

Создаёт новый экземпляр in-memory storage:

```clojure
(require '[graphden.memory-storage.interface :as mem]
         '[graphden.storage-protocol.interface :as sp])

(def storage (mem/create-storage))

;; Инициализация схемы
(sp/initialize storage my-schema)

;; Использование...

;; Закрытие (очистка данных)
(sp/close storage)
```

## Внутреннее устройство

### Структура состояния

```clojure
{:entities {:user {:fields {:name {:type :text :nullable? false}
                            :email {:type :text :nullable? true}}}}
 :enums {:status {:values #{:active :inactive}}}
 :metadata {:entities {uuid :entity-name}
            :fields {uuid {:entity :e :field :f}}
            :enums {uuid :enum-name}
            :enum-values {uuid {:enum :e :value :v}}}
 :data {:user {id-1 {:id id-1 :name "Alice" :email nil}
               id-2 {:id id-2 :name "Bob" :email "bob@example.com"}}}}
```

### Потокобезопасность

Использует атом для хранения состояния. Все операции атомарны.

## Миграции

### Поддерживаемые изменения

| Операция | Поддержка |
|----------|-----------|
| Добавление сущности | Да |
| Добавление поля | Да |
| Переименование сущности | Да (по UUID) |
| Переименование поля | Да (по UUID) |
| Расширение типа | Да (int→numeric) |
| Nullable: false→true | Да |

### Миграция данных при переименовании

При переименовании сущности или поля данные автоматически мигрируются:

```clojure
;; До: {:user {:name "Alice"}}
;; После переименования :name → :full-name
;; Данные: {:user {:full-name "Alice"}}
```

### Запрещённые изменения

| Операция | Причина |
|----------|---------|
| Удаление сущности | Потеря данных |
| Удаление поля | Потеря данных |
| Сужение типа | Невозможная конверсия |
| Nullable: true→false | Существующие NULL |

## Проверки типов

Использует утилиты из `storage-protocol`:

```clojure
;; Безопасные изменения
(sp/safe-type-change? :int :numeric)  ; => true
(sp/safe-type-change? :text :jsonb)   ; => true

;; Небезопасные изменения
(sp/safe-type-change? :text :int)     ; => false
```

## Пример полного использования

```clojure
(require '[graphden.memory-storage.interface :as mem]
         '[graphden.storage-protocol.interface :as sp]
         '[graphden.malli-data-schema.interface :as mds]
         '[graphden.data-schema-protocol.interface :as ds])

;; Создаём схему
(def schema
  (-> (mds/create-builder)
      (ds/add-entity :user #uuid "..."
                     {:name {:uuid #uuid "..." :type :text}})
      ds/build))

;; Создаём storage и инициализируем
(def storage (mem/create-storage))
(def changes (sp/initialize storage schema))

;; Проверяем результат
(:created (:entities changes))  ; => [:user]

;; Интроспекция
(sp/current-entities storage)   ; => #{:user}
(sp/current-fields storage :user) ; => {:name {:type :text :nullable? false}}

;; Закрытие
(sp/close storage)
```

## Ограничения

- Нет персистентности (данные в памяти)
- Нет транзакций (атомарные операции только на уровне atom)
- Нет индексов (линейный поиск)

Для продакшена используйте `postgres-storage` или `datomic-storage`.

## Тесты

```bash
bb test
```

Тесты покрывают:
- Инициализацию схемы
- Интроспекцию
- Миграции (переименование, добавление полей)
- Проверку деструктивных изменений
