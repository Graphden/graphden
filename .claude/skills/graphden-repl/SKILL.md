---
name: graphden-repl
description: REPL-driven workflow for Graphden via the clojure MCP server. Use when debugging or modifying base-fn impls, fn-defs, executor / storage / schema code — to verify behavior in the running nREPL before editing files and rebuilding. Triggers on phrases like "проверь", "почему не работает", "поправь импл", "посмотри что возвращает", "execute fn", "сходи в REPL", or any task that would otherwise require a `bb rebuild` cycle just to test a hypothesis. SKIP for: pure frontend (.js/.css) changes, package.edn dependency edits, Docker/CI config — those don't run inside the JVM REPL.
---

# graphden-repl — REPL-first для Graphden

Задача этого скилла: **сначала проверить гипотезу в живом nREPL, потом править файлы**. Цикл `bb rebuild` (jar + docker + restart, ~30–60 c) нужен только для финального деплоя — не для отладки.

## 0. Sanity check (один раз в начале сессии)

```clojure
;; mcp__clojure__list_nrepl_ports → должен показать localhost:<port> (clj) для /root/projects/graphden
;; затем:
(System/getProperty "user.dir")  ; => "/root/projects/graphden"
```

Если порта нет — попросить пользователя запустить `bb nrepl-bg` (фоновый headless nREPL, пишет `.nrepl-port`).

## 1. Поднять систему

В REPL загружен `dev`-неймспейс ([development/src/dev.clj](../../development/src/dev.clj)):

```clojure
(require 'dev :reload)         ; всегда :reload — иначе можно работать со stale def
(dev/go)                       ; стартует Integrant-систему с :dev профилем (testcontainers Postgres)
@integrant.repl.state/system   ; runtime map; nil если не запущена
(dev/halt)                     ; останов
(dev/reset)                    ; halt + reload config + go (после правок ig/init-key)
```

Доступ к компонентам без копаний в map:

```clojure
(dev/storage)   ; :db/versioned — VersionedStorage
(dev/context)   ; :exec/context — что executor ждёт первым аргументом
(dev/server)    ; :http/server  — для проверки routes
```

## 2. Базовые проверки

### Выполнить fn по имени

```clojure
(require '[graphden.executor.interface :as exec])
(exec/execute-by-name (dev/context) "add-10" {:b 5})
;; => 15
```

### Найти fn-id и посмотреть его args

```clojure
(require '[graphden.storage.protocol.interface :as sp])
(let [s (dev/storage)
      [f] (sp/query-entities s :fn {:name "add-10"})]
  {:fn f
   :args (sp/query-entities s :arg {:fn-id (:id f)})})
```

### Получить execution-graph (то, что видит компилятор)

```clojure
(sp/resolve-execution-graph (dev/storage) fn-id)
```

## 3. Тестирование base-fn impls

Самое частое: «работает ли мой `defbase`?». **Не надо** делать `bb rebuild` — `:reload` грузит свежий код:

```clojure
;; перезагрузить конкретный impls.clj
(require 'graphden.packages.core.arithmetic.impls :reload)
;; либо весь loader (читает fns.edn заново при сборке packages map)
(require '[graphden.packages.loader :as pkg] :reload)
(pkg/load-packages ["core" "web" "app"])
;; => {:base-fn-defs {...} :fn-defs [...] :packages [...] :startup-fn :web-server}
```

`load-packages` принимает **строки** имён пакетов, не keywords, и сам в БД ничего не пишет — синк делает Integrant init-key `:exec/base-fns`. Самый надёжный способ применить правки `fns.edn` / `defbase` к запущенной системе — `(dev/reset)`: он прогонит init-keys заново, включая синк пакетов в storage.

Если поменялся `impl-hash` (тело `defbase`, args, return-type) — VersionedStorage запишет новую версию автоматически при синке. Проверь через `sp/query-entities` что `impl-hash` обновился.

## 4. Проверка гипотез без правки файлов

```clojure
;; В REPL переопределить impl временно:
(in-ns 'graphden.packages.core.arithmetic.impls)
(graphden.executor.defbase/defbase add-10 [a b] (+ a b 10))
(in-ns 'user)
;; теперь exec/execute-by-name "add-10" вернёт результат с новой логикой
```

Если переопределение **подтверждает** гипотезу — переноси в файл и делай финальный `bb rebuild` для деплоя в Docker.

## 5. Когда REPL не помогает (нужен `bb rebuild`)

- Финальный деплой в Docker (без него прод не увидит изменений).
- Изменения в `.js`/`.css` (фронт берётся из jar — нужен пересбор + `BUILD_TIMESTAMP`).
- Изменения `deps.edn` / `bb.edn` / `package.edn` зависимостей.
- Изменения `system.edn` / Aero / Integrant-ключей, которые не подхватываются `dev/reset`.
- Когда тестируем то, что зависит от пересборки uberjar.

Память по теме: **`bb rebuild` после backend-изменений** — это про деплой, не про отладку. Отладка идёт в REPL, `rebuild` запускается **один раз** в конце.

## 6. Полезные MCP-инструменты, парные к REPL

- `mcp__clojure__clojure_eval` — основной воркхорс.
- `mcp__clojure__code_critique` — натравить на готовый кусок перед коммитом.
- `mcp__clojure__clojure_inspect_project` — быстрый обзор deps/aliases без чтения `deps.edn` руками.
- `mcp__clojure__clojure_edit` / `clojure_edit_replace_sexp` — структурное редактирование форм; меньше шансов сломать парены, чем `Edit` по строкам.
- `mcp__clojure__paren_repair` — если всё-таки сломал.

## 7. Анти-паттерны

- ❌ Запускать `bb test --focus ...` для проверки одной функции — REPL быстрее на порядок.
- ❌ `bb rebuild` после каждой мелкой правки — REPL и `:reload` для этого и сделаны.
- ❌ Забывать `:reload` в `require` — будешь ловить призраки прошлой сессии (см. явное напоминание в описании самого `clojure_eval`).
- ❌ Держать `(dev/go)` запущенным между сменой `:dev` profile / `system.edn` — делай `dev/reset` или `dev/halt` + `dev/go`.
