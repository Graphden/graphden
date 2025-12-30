# Graphden

[![Clojure](https://img.shields.io/badge/Clojure-1.12-blue.svg)](https://clojure.org/)
[![Polylith](https://img.shields.io/badge/architecture-Polylith-purple.svg)](https://polylith.gitbook.io/)
[![Coverage](https://img.shields.io/badge/coverage-91%25-brightgreen.svg)](#testing)
[![License](https://img.shields.io/badge/license-AGPL--3.0-blue.svg)](LICENSE)

**Визуальная среда функционального программирования** — граф функций в базе данных.

## Видение

Graphden — это экспериментальная платформа, где:

1. **Код = граф в БД** — функции и их композиции хранятся как структурированные данные
2. **Визуальное редактирование** — вместо текста используется графический интерфейс
3. **Каррирование через наследование** — частичное применение функций через цепочку родителей
4. **Ленивое исполнение** — вычисляется только то, что нужно

**Цель**: Проверить гипотезу, что визуальное программирование на основе графов может быть проще и читабельнее текстового кода для высокоуровневой логики.

## Ключевые концепции

### Сущности

| Сущность | Описание |
|----------|----------|
| `fn-schema` | Схема функции (имя, типы аргументов, возвращаемый тип) |
| `arg-schema` | Схема аргумента функции |
| `fn` | Экземпляр функции (может наследоваться от родителя) |
| `arg-value` | Значение аргумента (литерал или ссылка на другую fn) |

### Наследование

```
fn-schema: http-request
  args: [url, method, headers, body, timeout]

fn: base-api (parent: null)
  arg-values: {url: "https://api.example.com", timeout: 30}

fn: auth-api (parent: base-api)
  arg-values: {headers: {"Authorization": "..."}}
  → наследует: url, timeout

fn: create-user (parent: auth-api)
  arg-values: {method: "POST", body: {...}}
  → наследует: url, timeout, headers
```

### Модель исполнения

- **Ленивость** — аргументы оборачиваются в thunks, вычисляются по требованию
- **HOF поддержка** — функции типа `map`, `filter` получают fn-id, а не результат
- **Защита** — ограничение глубины рекурсии и таймаут

## Архитектура

```
┌─────────────────────────────────────────────────────────────┐
│                    graph-storage-*                          │
│  (memory, postgres, datomic) — готовые к использованию     │
├─────────────────────────────────────────────────────────────┤
│                   graph-data-schema                         │
│  (fn-schema, arg-schema, fn, arg-value)                    │
├─────────────────┬─────────────────┬─────────────────────────┤
│ storage-protocol│ data-schema-prot│     field-types        │
│ + *-storage     │ + malli-impl    │                        │
└─────────────────┴─────────────────┴─────────────────────────┘
```

## Компоненты

### Протоколы и схемы

| Компонент | Описание | README |
|-----------|----------|--------|
| [storage-protocol](components/storage-protocol/) | Протоколы Storage, StorageCRUD, GraphConstraints | [→](components/storage-protocol/README.md) |
| [data-schema-protocol](components/data-schema-protocol/) | Протокол DataSchema, типы полей | [→](components/data-schema-protocol/README.md) |
| [field-types](components/field-types/) | Поддерживаемые типы данных | [→](components/field-types/README.md) |
| [malli-data-schema](components/malli-data-schema/) | Malli-реализация схемы | [→](components/malli-data-schema/README.md) |
| [graph-data-schema](components/graph-data-schema/) | Схема графа функций | [→](components/graph-data-schema/README.md) |

### Storage реализации

| Компонент | Описание | README |
|-----------|----------|--------|
| [memory-storage](components/memory-storage/) | In-memory storage | [→](components/memory-storage/README.md) |
| [postgres-storage](components/postgres-storage/) | PostgreSQL storage | [→](components/postgres-storage/README.md) |
| [datomic-storage](components/datomic-storage/) | Datomic storage | [→](components/datomic-storage/README.md) |

### Готовые комбинации (storage + graph-data-schema)

| Компонент | Описание | README |
|-----------|----------|--------|
| [graph-storage-memory](components/graph-storage-memory/) | In-memory, готовый к работе | [→](components/graph-storage-memory/README.md) |
| [graph-storage-postgres](components/graph-storage-postgres/) | PostgreSQL, готовый к работе | [→](components/graph-storage-postgres/README.md) |
| [graph-storage-datomic](components/graph-storage-datomic/) | Datomic, готовый к работе | [→](components/graph-storage-datomic/README.md) |

## Документация

- **[Архитектура](docs/ARCHITECTURE.md)** — детальное описание системы, решений и ограничений

## Требования

- Java 21+
- Clojure 1.12+
- [Babashka](https://github.com/babashka/babashka)

### Опционально (для линтеров)

```bash
brew install clj-kondo cljstyle
```

## Быстрый старт

```bash
# Start REPL
bb repl

# Run all checks (linters + tests)
bb check

# Run tests only
bb test

# Run with coverage report
bb coverage
```

## Разработка

### Доступные задачи

```bash
bb tasks  # Показать все задачи
```

| Задача | Описание |
|--------|----------|
| `bb check` | Все линтеры + все тесты (параллельно) |
| `bb lint` | Только линтеры |
| `bb test` | Только тесты |
| `bb coverage` | Тесты с отчётом покрытия |
| `bb repl` | Запустить nREPL |

### Линтеры

```bash
bb kondo [path]     # Статический анализ (clj-kondo)
bb splint [path]    # Стиль и идиомы
bb cljstyle [path]  # Форматирование
bb fix [path]       # Авто-исправление форматирования
```

### Утилиты

```bash
bb outdated   # Проверить устаревшие зависимости
bb security   # Сканировать CVE
bb clean      # Очистить сгенерированные файлы
bb info       # Информация о Polylith workspace
bb deps       # Зависимости компонентов
```

## Тестирование

```bash
bb test
bb coverage
open target/coverage/index.html
```

Текущее покрытие: **91% форм / 99% строк**

## Структура проекта

```
graphden/
├── bb.edn                 # Babashka задачи
├── deps.edn               # Clojure зависимости
├── workspace.edn          # Polylith конфигурация
├── docs/
│   └── ARCHITECTURE.md    # Архитектурная документация
├── components/
│   ├── storage-protocol/
│   ├── data-schema-protocol/
│   ├── field-types/
│   ├── malli-data-schema/
│   ├── graph-data-schema/
│   ├── memory-storage/
│   ├── postgres-storage/
│   ├── datomic-storage/
│   └── graph-storage-*/   # Готовые комбинации
└── development/           # Development project
```

## Статус разработки

### Реализовано

- [x] Протокол Storage (инициализация, интроспекция)
- [x] Протокол DataSchema (сущности, поля, валидация)
- [x] Malli-реализация схемы
- [x] Схема графа функций (fn-schema, fn, arg-value)
- [x] Memory storage
- [x] PostgreSQL storage
- [x] Datomic storage

### В разработке

- [ ] CRUD операции
- [ ] Протокол GraphConstraints
- [ ] Наследование (parent-fn-id)
- [ ] Исполнитель (executor)
- [ ] Базовые функции
- [ ] REST API
- [ ] Веб-интерфейс

### Планы на будущее

- [ ] Система типов (алгебра типов)
- [ ] Git-like версионирование
- [ ] Система пользователей и прав

## Лицензия

GNU Affero General Public License v3.0 (AGPL-3.0).
См. [LICENSE](LICENSE).

Для коммерческого лицензирования: licensing@graphden.dev
