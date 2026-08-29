# План: доступность (a11y) + клавиатурное управление редактором

Дата: 2026-08-29. Статус: утверждён пользователем к реализации.
Исполнение: через wtq (`bb wt claim` → worktree → `bb wt merge` → `bb wt drop`),
по одному work-package на агента. После лендинга каждого WP в develop —
`bb release --push` (релиз = часть фичи). Пушить develop ДО `bb wt drop`.

## Контекст (результат аудита 2026-08-29)

Полный аудит кода и живой axe-скан уже проведены. Ключевые факты — не пере-исследовать:

**Уже есть (не переделывать):**

- ARIA-дисциплина в hiccup-шелле: `resources/packages/app/editor/fns.edn:193-460` —
  лендмарки `<aside>`/`<main>`, `role="toolbar"` на nav-controls, `role="search"`,
  `role="group"` на graph-surface (осознанно НЕ `role="img"` — см. комментарий там же),
  `aria-pressed` на чипах линз, декоративные SVG с `aria-hidden`.

- ~134 `aria-label`, 18 `role="dialog"`, 5 live-регионов (`editor-busy.js:30`,
  `editor-ui.js:171` тост, `editor-edit-modes.js:71`, `editor-tooltips.js:379`,
  `editor-path-view.js:190`).

- Дизайн-токены + stylelint-принуждение (сырые цвета вне `:root`/`body.theme-dark` — ошибка);
  a11y-токены `--touch-target: 44px`, `--gd-focus`; 37 правил `:focus-visible`;
  корректный `.visually-hidden` (`editor-styles.css:250`).

- Escape закрывает почти всё; есть протокол «потреблённой клавиши»:
  `editor-tour.js:817-841` (+ `TOUR_ESCAPE_OWNERS` :832) и
  `resources/packages/web/runtime/graphden-popover.js:72-99`
  (`installPopoverDismiss` + `_popoverRegistry` + `dismissAllPopovers`).

- Хорошие образцы клавиатурных div-кнопок: `editor-icons.js:216-233`,
  `editor-overlay-strips.js:333-350`, `editor-overlay-fn-rows.js:504-520`.

- Единственное место с правильным фокус-менеджментом — `editor-row-actions.js:212-220`
  (фокус на первый контрол) и `:283-288` (Escape возвращает фокус на anchor).

**Дыры (что чиним):**

1. 434 `font-size` в `px`, 0 в `rem` — размер шрифта браузера игнорируется.
2. `prefers-reduced-motion` — 0 совпадений; есть бесконечная анимация
   `service-badge-pulse` (`editor-styles.css:4276`) и 28 transitions.

3. `outline: none` без парного `:focus-visible` в editor-styles.css:
   493, 687, 944, 1355, 1476, 1560, 1761, 1844, 2370, 2393, 2425, 2547, 2732, 2754, 3101;
   плюс :4841, :4856, :4876 (ring заменён фоном).

4. Ноды канваса — голые div (`editor-overlay-manager.js:91-108` фабрика,
   `editor-overlay-fn.js:262-271` клик по телу = выбор fn): без tabindex/role/имени.

5. Дерево неймспейсов полностью мышиное: `editor-sidebar.js:519-521` (buildFnItem),
   `:634` (item.onclick), ns-header `:653,716,1101,1127` — div + onclick, нет role="tree".

6. 18 `role="dialog"` без focus-trap и возврата фокуса; два с `aria-modal="true"`
   БЕЗ trap (`editor-branch-diff.js:20-21`, `editor-branches.js:1130-1131`) — врут SR.
   Полный список диалогов: editor-create-type.js:50, editor-fn-versions.js:26,
   editor-mismatch-explainer.js:33, editor-edit-modes.js:57, editor-path-view.js:132,
   editor-org-switcher.js:47, editor-value-form.js:302, editor-effect-explainer.js:23,
   editor-tour.js:66, editor-trace-view.js:119, editor-branch-diff.js:20,
   editor-feedback.js:170, editor-service-popover.js:32, editor-branches.js:378,1130,
   editor-fn-picker.js:179, editor-provenance-popover.js:25.

7. Тур: кнопки — настоящие `<button>`, но фокус в попап не переносится (0 вызовов
   .focus() в editor-tour.js), смена шага не озвучивается, цель шага не помечена для SR.

8. SVG-слой рёбер (`editor-edges-svg.js:44-62,144-162`) не помечен `aria-hidden`.
9. fn-picker: стрелки есть (`:536-556`), но нет `role="listbox"` на контейнере и
   `aria-activedescendant`; строки уже имеют `role="option"`/`aria-selected` (:344,396).

10. Табы инспектора (`editor-shell.js:334-350`): `role="tab"`/`aria-selected` есть,
    нет `aria-controls`/`tabpanel`/roving tabindex/стрелок.

11. Нет skip-link, нет клавиатурного pan/zoom (`editor-viewport.js` — только wheel :147
    и mousedown :158; API `setViewportPan:53`/`setViewportZoom`/`setViewportTransform:59` есть).

12. Нет ни одного a11y/клавиатурного теста в tools/browser-test/, нет a11y-гейта в CI,
    нет docs про a11y/keyboard.

13. Шорткаты: 32 разрозненных keydown в 25 модулях, НИ ОДНОЙ модификаторной комбинации —
    поле для leader-key свободно.

14. auth-pages (`resources/packages/app/auth-pages/fns.edn`): нет `<main>`, `#msg` без
    aria-live, SVG-логотип без aria-hidden, «Forgot password?» = `<a href='#'>` + onclick.

15. Лендинг (apex graphden.dev, репо graphden-cloud): axe = 0 critical;
    link-in-text-block (serious), нет `<main>`, контент вне landmarks,
    пустой table header. `/tutorial` — то же минус таблица.

**Живой axe-скан редактора** делать через MCP `a11y` по своему `bb wt up`-стеку
(редактор за авторизацией на проде; на изолированном стеке доступен).

---

## Правила исполнения (для КАЖДОГО WP)

- Перед правкой JS/CSS редактора — загрузить скилл `graphden-ui`. Он требует
  live-верификацию (Playwright + a11y MCP) до заявления «готово».

- Новый JS-модуль обязан попасть в `:_editor-script-paths`
  (`resources/packages/app/editor/fns.edn`) с учётом порядка загрузки И получить
  строку в `docs/EDITOR_MODULES.md`.

- `bb lint-web` (biome + stylelint) зелёный; stylelint-токены — цвета только через var().
- Изменения фокус-рингов/анимаций сдвинут визуальные базлайны → `bb visual-update`
  в том же WP (базлайны instance-independent — не снимать со своих данных).

- e2e-тесты в `tools/browser-test/` ОБЯЗАНЫ называться `edit-*.test.js`, иначе не
  запускаются (`./run-edit-tests.sh`). Чистая DOM-логика без стека —
  в `tools/runtime-test/` (mini-dom.js), под `bb test-js`.

- Не ломать e2e-контракты селекторов (пример: `.execute-popover.visible`).
- Если правится тело/имя формы, заанкоренной в devtour — `bb devtour` + коммит
  index.html (bb devtour-check в CI красный иначе). Editorial: editor-shortcuts.js
  и editor-a11y.js — кандидаты на новый шаг в блоке editor.

- Клавиатурное управление = новая user-visible возможность → урок туториала
  обязателен В ТОМ ЖЕ лендинге, где фича становится полной (WP4), не раньше
  (частично залендившиеся фичи не документировать). + свип прозовых поверхностей
  (тур, tutorial EN, RU-доки в graphden-internal/i18n).

- После merge в develop: push develop → `bb wt drop` → `bb release --push` →
  проверить GitHub CI после пуша.

---

## WP0 — «дешёвый свип» для слабовидящих (1 агент, независим)

Файлы: `editor-styles.css`, `components.css`, `resources/packages/app/editor/fns.edn`,
`editor-edges-svg.js`, `resources/packages/app/auth-pages/fns.edn`.

1. **px→rem для font-size** (434 объявления). Только `font-size` (и `line-height`,
   где он в px и завязан на текст); border/spacing/размеры НЕ трогать. База 16px:
   `12px → .75rem`, `11px → .6875rem` и т.д. Скриптом (sed/узкий скрипт в
   scratchpad) + ручная проверка diff. `createOverlay` default `11px`
   (editor-overlay-manager.js) — тоже.
   **Риск:** размеры нод меряются из DOM и питают layout-пайплайн
   (docs/LAYOUT.md — measurement stage) — это правильно (растёт шрифт → растёт нода),
   но обязательно проверить руками канвас при browser font-size 24px:
   layout не должен разъезжаться (рёбра к краям нод, оверлеи).

2. **`@media (prefers-reduced-motion: reduce)`**: заглушить 3 `@keyframes`
   (gd-tree-flash :3174, editor-busy-spin :3284 — спиннер можно оставить,
   это индикатор прогресса, но убрать пульсацию service-badge-pulse :4276)
   и обнулить/укоротить transitions. Также JS-скролл: если где-то
   `scrollIntoView({behavior:'smooth'})` — уважать медиа-запрос.

3. **outline:none — 15 сайтов** (список в «Дырах» п.3): каждому либо парный
   `:focus-visible` с `var(--gd-focus)`, либо убрать подавление; 3 сайта
   (:4841,:4856,:4876), где ring заменён фоном — вернуть видимый ring.

4. **Skip-link** в шелл (fns.edn до сайдбара): «Skip to graph» →
   `#graph-container`, класс `.visually-hidden` + `:focus` показывает.

5. **`aria-hidden="true"` на `#gd-edge-layer`** (editor-edges-svg.js:44-62) —
   рёбра для SR шум; информация о связях придёт в WP4 через announce.

6. **auth-pages**: `<main>` вокруг карточки, `role="alert"`/`aria-live="polite"`
   на `#msg`, `aria-hidden` на логотип-SVG, «Forgot password?» → `<button type="button">`
   или `<a>` с реальным href.

Проверка: bb lint-web; bb visual-update; a11y MCP скан своего wt-стека
(редактор + /login) — 0 serious/critical; ручной прогон канваса на font-size 20-24px.

## WP-L — лендинг (репо graphden-cloud, мини-задача, независима)

apex-страница + /tutorial: подчёркивание (или иной не-цветовой признак) ссылок
в тексте; `<main>` + landmarks вокруг всего контента; заголовок таблицы
(пустой `<th>` → текст или `aria-hidden`). После деплоя — повторный
`mcp a11y get_summary https://graphden.dev/` = 0 serious.

## WP1 — a11y-инфраструктура: announce + focus-trap + диалоги (1 агент; блокирует WP2)

1. **Новый модуль `editor-a11y.js`** (грузить рано в `:_editor-script-paths`):
   - `announce(msg, {assertive=false})` — синглтон live-region в body
     (`role="status"` / `role="alert"`), дебаунс повторов;

   - `trapFocus(el, {returnTo})` → handle, `handle.release()` — Tab-цикл по
     фокусируемым внутри el, фокус на первый контрол при входе, возврат фокуса
     на returnTo (по умолчанию — document.activeElement на момент trap) при release.

   - Без сторонних библиотек, vanilla, как весь фронт.
   - Юнит-тесты в `tools/runtime-test/` на mini-dom (список фокусируемых,
     возврат фокуса); полный Tab-цикл — e2e.

2. **`graphden-popover.js`: `installDialogSemantics(opts)`** — обёртка над
   `installPopoverDismiss` + trapFocus + возврат фокуса. ВАЖНО: файл в
   `web/runtime/` бандлится и в standalone `/assets/graphden-runtime.js` —
   не тянуть в него зависимость от editor-a11y.js; trap-логику держать в
   graphden-popover.js или передавать через opts.

3. **Миграция 18 диалогов** (список в «Дырах» п.6) на installDialogSemantics.
   Приоритет: два `aria-modal="true"` (branch-diff, merge-conflict в
   editor-branches.js:1130) — им ещё `inert` на фон (`#app`-контейнер) на время
   показа. row-actions НЕ трогать — он осознанно toolbar (см. :46-50).
   У каждого диалога сохранить его текущую Escape-семантику (протокол
   потреблённой клавиши, не сломать TOUR_ESCAPE_OWNERS).

4. **Пикеры**: fn-picker — `role="listbox"` на контейнер списка,
   `aria-activedescendant` на инпуте, id на строках-option (строки уже с
   role="option"); то же для namespace-picker (+ роли, их там нет вовсе).

5. e2e `edit-a11y-dialogs.test.js`: открыть 3-4 репрезентативных диалога →
   фокус внутри; Tab с последнего → первый; Escape → фокус на триггере.

## WP2 — структурные паттерны: дерево, табы, тур (1 агент, после WP1; параллелен WP3)

1. **Дерево сайдбара → APG tree** (`editor-sidebar.js`):
   `role="tree"` на `#entity-list`, `role="treeitem"` + `aria-level` +
   `aria-expanded` (ns) + `aria-selected` (fn) на элементах, roving tabindex
   (один tabindex="0" на дереве), стрелки: ↑↓ — по видимым узлам,
   → раскрыть/внутрь, ← свернуть/наружу, Enter — открыть fn (selectFn),
   Home/End. Учесть: элементы перестраиваются при догрузке ns — сохранять
   позицию фокуса по id. hover-actions строк должны быть достижимы
   (при фокусе строки показывать как при hover — CSS `:focus-within`).

2. **Табы инспектора** (`editor-shell.js:297-350`): `aria-controls` +
   `role="tabpanel"` + `aria-labelledby`, roving tabindex, ←→ между табами.

3. **Тур** (`editor-tour.js`): при показе/смене шага — фокус на попап
   (tabindex="-1" на контейнер) или на Next; тело шага — `aria-live="polite"`
   (или announce()); целевой элемент шага помечать `aria-describedby` на
   попап-текст не обязательно — достаточно фокуса+live. Enter = Next, когда
   фокус в попапе и не на другой кнопке. Не сломать: протокол Escape
   (:817-848), «клики по туру не закрывают модалки», спотлайт-логику.

4. **announce() при выборе fn** (selectFn) — «<имя>, function, selected» —
   и при переключении веток/линз.

Проверка: e2e `edit-a11y-tree.test.js` (полная навигация деревом с клавиатуры
до открытия fn); тур-e2e не красные; bb visual-update при изменении фокус-стилей.

## WP3 — движок шорткатов + leader/which-key (1 агент, после WP1; параллелен WP2)

1. **Новый `editor-shortcuts.js`** — единый диспетчер:
   - реестр `registerShortcut({id, keys, when, run, description, group})`;
     `keys` — простая нотация ('g f' — секвенция после leader, 'ArrowLeft',
     'mod+Enter'); `when` — предикаты контекста ('canvas', 'global', 'tree');

   - один capture-обработчик keydown на window; обобщение протокола
     «потреблённой клавиши»: диспетчер работает ПОСЛЕ существующих
     обработчиков (не capture) либо уважает event.defaultPrevented —
     согласовать с editor-tour.js:817-841 и graphden-popover.js;

   - guard: одиночные буквы/Space неактивны, когда фокус в
     input/textarea/select/[contenteditable].

2. **Leader = Space + which-key попап**: когда фокус НЕ в поле ввода и не на
   кнопке (иначе Space = активация), Space открывает попап `role="menu"` со
   списком групп/команд из реестра (мнемоники подсвечены). Дальнейшие клавиши —
   цепочка. Escape закрывает. Попап сам SR-читаем — это и есть «командная
   палитра» для незрячих.

3. **`?` — шпаргалка**: полный список биндингов, генерится из реестра
   (диалог через installDialogSemantics из WP1).

4. **Стартовый набор биндингов** (не переусердствовать, расширим в WP4):
   `Space /` — фокус в поиск fn сайдбара; `Space g f` — fit graph;
   `+`/`-`/`0` — zoom in/out/reset (когда фокус на канвасе);
   `Space b` — панель веток; `Space r` — Runs-таб инспектора;
   `Space t` — диагностическая полоса; `Space ?` = `?`.

5. Юнит-тесты диспетчера (парсинг keys, when-предикаты, секвенции) в
   tools/runtime-test/; e2e `edit-shortcuts.test.js` (Space-меню открывается,
   Space / фокусирует поиск, ? показывает шпаргалку, в инпуте Space печатает пробел).

Урок туториала на этом WP ещё НЕ писать (фича не полная) — в WP4.

## WP4 — клавиатурный канвас: навигация по графу, модальность (1 агент-эпик, после WP2+WP3)

Субстрат: `editor-graph-view.js` (`gv.incomingEdges/outgoingEdges/position/width/height`),
`editor-viewport.js` (setViewportPan/Zoom/Transform), фабрика
`editor-overlay-manager.js:91-108`.

1. **Ноды фокусируемы**: в фабрике createOverlay — roving tabindex
   (`tabindex="0"` только на «текущей» ноде, `-1` на остальных),
   `role` (подобрать: `button` не подходит — контейнер с интерактивом внутри;
   вероятно `group` + aria-label, активация через keydown), `aria-label` =
   имя fn + ns + краткий тип. `.node-overlay:focus-visible` — стиль в CSS
   (сейчас правила нет).

2. **Фокус двигает вьюпорт**: свой ensureNodeVisible через setViewportPan
   (слой под CSS-трансформом — нативный scrollIntoView не работает);
   подавить браузерный auto-scroll (`preventScroll` на focus()).

3. **Навигация между нодами** (режим «канвас», фокус на ноде):
   стрелки/hjkl — по рёбрам: ←/h — к аргументам (incoming), →/l — к
   потребителю (outgoing), ↑↓/jk — между сиблингами (сортировать по
   position().y); при нескольких кандидатах — ближайший геометрически.
   Enter — выбрать fn (эквивалент клика editor-overlay-fn.js:262) И войти
   внутрь ноды (уровень строк); Escape — из строк на ноду, с ноды — снять фокус
   на канвас-уровень.

4. **Внутри ноды**: ↑↓ по строкам (fn-rows/args), Enter — действие строки
   (существующие div-кнопки уже с образцом Enter/Space), `.` или `m` —
   открыть row-actions (⋯-триггер уже настоящий button). Использовать
   существующие паттерны overlay-fn-rows.js:504-520.

5. **Перемещение ноды**: Shift+стрелки — сдвиг на шаг сетки (эквивалент
   editor-drag), с announce позиции. (Если drag-позиции персистятся — тем же
   путём, что drag.)

6. **Pan/zoom**: стрелки на канвас-уровне (без фокуса на ноде) — pan;
   +/-/0 — zoom; `f` — fit. Регистрация через editor-shortcuts.js.

7. **SR-озвучка**: при фокусе ноды — announce (имя, тип, число входящих/
   исходящих рёбер); при переходе по ребру — имя слота. Это компенсирует
   aria-hidden на слое рёбер из WP0.

8. **Биндинги в реестр WP3** (появляются в which-key и `?` автоматически).
9. **Урок туториала** «Управление с клавиатуры» — в этом же лендинге
   (paste-correct, проверить по живому редактору); прозовый свип
   (тур/tutorial EN; RU — graphden-internal/i18n); шаг devtour для
   editor-shortcuts.js + editor-a11y.js (блок editor), `bb devtour` + коммит.

10. e2e `edit-keyboard-canvas.test.js`: клавиатурой-только: найти fn поиском →
    открыть → пройти к аргументу → открыть row-actions → закрыть → вернуться.
    Помнить про медленный стек (fn create ~3s, поллинг ≥30s).

## WP5 — гейт + доки (1 агент, после WP1; можно раньше WP4)

1. **Автоматический axe-гейт**: `edit-a11y-axe.test.js` в tools/browser-test/
   (axe-core npm в devDeps браузер-тестов) — прогон по главным поверхностям
   редактора (шелл, открытый fn, открытый диалог, тур) с порогом
   0 serious/critical; попадает в run-edit-tests.sh → в `bb test-e2e` гейта.

2. **`docs/ACCESSIBILITY.md`**: модель (announce/trapFocus/shortcuts-реестр),
   APG-паттерны по поверхностям, правила для новых модулей (диалог → только
   через installDialogSemantics; новая клавиша → только через реестр),
   как гонять axe и ручную SR-проверку. Строка в doc-map CLAUDE.md +
   docs/README.md.

3. **CLAUDE.md**: короткое правило в Code Conventions — новые
   поповеры/диалоги и шорткаты только через инфраструктуру WP1/WP3.

## Ручная верификация (пользователь, после WP4)

Автоматика не заменяет живой SR-прогон: NVDA (Windows) или VoiceOver (macOS) —
сценарий «найти fn → понять его аргументы → запустить». Orca на Linux — опция.
Это единственный настоящий критерий для незрячих; agент делает всё остальное.

## Порядок и параллелизм

```
WP0 ─┐                    WP-L (graphden-cloud, в любой момент)
     ├─ WP1 ─┬─ WP2 ─┐
             ├─ WP3 ─┼─ WP4 (эпик, один агент)
             └─ WP5 ─┘ (axe-гейт можно после WP1)
```

WP0 и WP-L — сразу и параллельно. WP2 ∥ WP3 после WP1. WP4 — последним, один
агент. Каждый WP: полный цикл гейта + релиз.
