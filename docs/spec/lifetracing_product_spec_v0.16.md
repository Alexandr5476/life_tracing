# LifeTracing / приложение дел — продуктовая спецификация v0.16

Дата: 2026-08-14  
Статус: **v1 product spec frozen for implementation; future parallel sessions/manual Sequence backfill explicitly deferred**  
Основание: решения из проектирования Daily / Plan / Activity Editor / Sequence Editor / Sequence Execution, ответы на вопросы v0.1–v0.4 и финальная фиксация runtime/timeline semantics.

## 0. Назначение и границы версии

Этот документ фиксирует уже принятые продуктовые, UX- и доменные решения достаточно подробно, чтобы на их основе позднее проектировать модель данных, state machines и реализацию.

Статусы:

- **РЕШЕНО** — считаем базовым решением, пока явно не пересмотрено.
- **ПРЕДЛОЖЕНИЕ** — рабочее правило, согласованное с остальной моделью, но ещё требующее подтверждения.
- **ОТКРЫТО** — вопрос, который намеренно вынесен в конец документа.

На текущем этапе **не специфицированы полностью**:

- recurrence / повторяющиеся планы;
- детальное редактирование уже созданного Plan Entry;
- Month Plan presentation beyond the agreed precision model;
- подробные экраны Advanced Settings;
- подробный Custom Field editor;
- Auto-insert dialog;
- future checklist/free-order Sequence mode;
- statistical groups;
- графики Statistics.

Базовые flow `Library`, `Start Activity`, `Add to Plan` и текстовая Statistics v1 уже зафиксированы ниже.

Все принятые требования сохраняются как ограничения для domain model и будущей реализации.

---

# 1. Основные принципы продукта

## 1.1. Фактическое выполнение важнее todo-статуса

**РЕШЕНО.** Центральная сущность приложения — фактическое выполнение Activity. Оно может содержать точные timestamps, длительность, паузы, значения пользовательских полей и контекст выполнения.

Приложение должно одинаково естественно поддерживать:

- точное измерение упражнения по секундам;
- длительную работу, где секунды пользователю неинтересны;
- Timer;
- дело, которое просто отмечается выполненным;
- дело, где главным фактом является число (`15 reps`, `20 pages`, `8 km`);
- последовательности из обычных дел.

## 1.2. Простое использование не требует предварительного моделирования

**РЕШЕНО.** Пользователь может создать и выполнить одноразовое дело без Template, Custom Fields, Sequence и других расширенных сущностей.

Расширенная функциональность должна добавляться постепенно и не мешать простому сценарию.

## 1.3. Template и история принципиально разделены

**РЕШЕНО.** Template — источник конфигурации для создания новых snapshot. Он не является живым источником истины для уже выполненной истории.

Изменение или удаление Template само по себе не меняет исторические Activity Execution / Sequence Execution.

## 1.4. Sequence состоит из обычных Activity

**РЕШЕНО.** Sequence Step не является урезанным workout-step. Он использует полноценную конфигурацию обычного Activity.

Sequence добавляет только:

- порядок;
- Repeat;
- автоматическую генерацию структуры при редактировании;
- правила переходов;
- sequence-level settings;
- runtime position/state.

Sequence должна одинаково подходить и для тренировки, и для бытовой цепочки вроде:

1. Иду в магазин;
2. Нахожусь в магазине;
3. Возвращаюсь из магазина.

---

# 2. Базовые сущности и идентичность данных

## 2.1. Activity Template

**РЕШЕНО.** Повторно используемая конфигурация Activity.

Содержит как минимум:

- stable `templateId`;
- название;
- default Short Comment;
- Time Tracking configuration;
- default target/default values Custom Fields;
- определения Custom Fields;
- optional Main Value field;
- Advanced Activity Settings;
- организационные свойства (tags/folder — детально позже).

Удаление Template не удаляет уже созданные Execution и не должно делать их нечитаемыми.

## 2.2. Activity Config Snapshot

**РЕШЕНО концептуально.** Когда конфигурация Template используется в другом месте, по умолчанию создаётся независимый snapshot.

Snapshot может существовать, например:

- как Sequence Step;
- в будущем как часть запланированного/преднастроенного объекта;
- как конфигурация, из которой создаётся Execution.

Snapshot хранит origin metadata, если был создан из Template:

- `sourceTemplateId`;
- версию/ревизию источника на момент копирования;
- признак локального изменения — точная гранулярность пока **ОТКРЫТА**.

## 2.3. Activity Execution

**РЕШЕНО.** Конкретный факт выполнения Activity.

Execution должен оставаться полностью интерпретируемым, даже если исходный Template:

- изменён;
- удалён;
- потерял поле;
- переименован.

Execution хранит snapshot отображаемой/измерительной конфигурации и фактические значения.

Для разных режимов Execution может содержать разные subsets данных. Отсутствие duration является валидным состоянием, а не ошибкой.

## 2.4. Sequence Template

**РЕШЕНО.** Повторно используемая ordered structure.

Содержит:

- `sequenceTemplateId`;
- название;
- short description;
- ordered nodes: Activity Step / Repeat Group;
- Sequence Settings;
- организационные свойства.

## 2.5. Sequence Step

**РЕШЕНО.** Полноценный Activity Config Snapshot + sequence metadata.

Sequence Step по умолчанию не обновляется автоматически при изменении исходного Activity Template.

## 2.6. Repeat Group

**РЕШЕНО.** Логический структурный блок `Repeat ×N`.

Repeat не разворачивается физически в N копий в Template. Во время выполнения создаются отдельные runtime occurrences для каждой итерации.

Вложенный Repeat в Repeat в первой версии **запрещён**.

## 2.7. Sequence inside Sequence

**РЕШЕНО.** В первой версии Sequence нельзя использовать как Step другой Sequence.

Причина: рекурсивная структура резко усложняет редактор, runtime state, navigation и статистику. При необходимости используются Repeat и Auto-insert.

## 2.8. Sequence Execution

**РЕШЕНО.** Snapshot конкретной Sequence на момент старта + runtime state.

После старта изменение Sequence Template не влияет на текущую Sequence Execution.

Sequence Execution хранит как минимум:

- snapshot структуры;
- runtime occurrences Repeat;
- состояние/статус каждого occurrence;
- Activity Execution для фактически выполнявшихся Activity;
- текущую позицию;
- paused intervals;
- `startedAt`;
- `endedAt`;
- wall-clock duration;
- active duration;
- sequence-level result/status.

---

# 3. Short Comment и пользовательские поля

## 3.1. Short Comment

**РЕШЕНО.** Встроенный необязательный короткий комментарий.

Это не Custom Field и он не участвует в статистике.

Примеры:

- `Programming` / `Fixed navigation test`;
- `Doctor Appointment` / `Annual checkup`;
- `Возвращаюсь после бега` / `Захожу во ВкусВилл`.

Default Short Comment из Template копируется в snapshot, после чего редактируется локально и независимо.

То же snapshot-правило применяется ко всем остальным свойствам Template.

## 3.2. Типы Custom Fields

**РЕШЕНО на текущем уровне:**

- Number;
- Category;
- Text / Note.

Boolean, Rating и другие специализированные UI-типы могут появиться позже, если дадут заметное преимущество в вводе/статистике.

## 3.3. Number

Number должен поддерживать:

- unit;
- integer / decimal input;
- ограничения precision/display — конкретные правила позже;
- default/target value;
- actual execution value;
- возможность быть Main Value.

## 3.4. Target/default и actual

**РЕШЕНО.** Значение в конфигурации и фактическое значение Execution — разные данные.

Пример:

- Sequence Step / Template: `Reps default/target = 15`;
- конкретное выполнение: `actual Reps = 13`.

При создании Execution actual-значение инициализируется configured/default значением. Если пользователь ничего не меняет, именно default сохраняется как actual.

Следствия:

- Timer/Stopwatch может завершиться без отдельного подтверждения Custom Field, и default всё равно сохранится;
- пользователь может изменить actual до/во время/после завершения там, где UI это позволяет;
- historical edit меняет только actual конкретного Execution, а не Template/default.

Для Repeat каждое runtime occurrence получает независимую копию actual, изначально инициализированную default.

## 3.5. Main Value

**РЕШЕНО.** Main Value — обычное numeric Custom Field, выбранное для компактного отображения.

Не существует отдельного completion mode `Count`.

Допустимые сочетания:

- Stopwatch + `15 reps`;
- Stopwatch + `8 km`;
- Timer `1:30` + `Weight`;
- No live tracking + `20 pages`;
- No live tracking без Main Value.

Time Tracking и Main Value независимы.

**Рабочее ограничение:** одновременно один Custom Field является Main Value.

## 3.6. Схема Field и история

Custom Field имеет stable `fieldId` внутри Template lineage/snapshots.

Для v1 уже зафиксировано:

- изменить `type` существующего Field нельзя: semantic replacement создаёт новое Field;
- изменить `unit` (`kg`, `km`, `reps`, `pages` и т. п.) в v1 нельзя без создания нового Field;
- display precision можно менять;
- Category можно дополнять новыми вариантами;
- исторический Execution не меняет schema: в history редактируется только фактическое value;
- все Custom Fields v1 optional;
- изменение Template/Sequence Step schema влияет только на будущие Execution соответствующего config/snapshot.

**Открыто только правило display-name rename.** Рекомендуется разрешить исправлять/переименовывать display name с сохранением того же `fieldId` и Statistics identity. Это не затрагивает type/unit.

Execution сохраняет snapshot Field schema, поэтому historical label остаётся читаемым независимо от текущего Template.

# 4. Time Tracking и жизненный цикл Activity

## 4.1. Точность хранения

**РЕШЕНО.** Внутреннее время хранится точно. Настройка отображения секунд влияет на UI, а не на точность данных.

Для реализации timestamps должны быть достаточно точными, чтобы корректно восстанавливать elapsed/remaining time после background/restart. Продуктовая точность отображения минимум секундная там, где она нужна.

## 4.2. Три режима

### Stopwatch

**РЕШЕНО.**

- explicit Start;
- elapsed active time растёт;
- Pause/Resume;
- explicit Finish;
- Execution имеет start/end/paused intervals/active duration.

### Timer

**РЕШЕНО.**

- explicit Start;
- отсчёт к нулю;
- Pause/Resume;
- behavior at zero configurable;
- overtime допустим как настройка;
- фактический Execution хранит runtime timestamps, а не только номинальную duration.

### No live tracking

**РЕШЕНО.** У standalone-дела нет измеряемой продолжительности.

При explicit completion action Activity становится выполненным без создания live timer:

- сохраняется completion timestamp;
- duration отсутствует;
- active slot приложения не занимается после действия;
- пользователь сразу может выполнять следующее Activity.

Если Activity имеет Main Value, его default/actual можно быстро изменить перед completion прямо в compact UI.

Если требуется ввод нескольких Custom Fields, completion может открыть минимальный quick-completion sheet с уже заполненными default values. Это не полноценный Activity Editor.

Все поля optional: пользователь может сохранить Execution, не заполняя их.

Термин `Start` для No-live семантически неидеален. Конкретный label/icon будет окончательно выбран при проектировании Start Activity flow.

## 4.3. Single active session

**РЕШЕНО для первой версии.** Одновременно существует не более одной незавершённой live Activity/Sequence session.

Paused session всё ещё занимает active slot. Нельзя запустить вторую live session, просто оставив первую paused.

**Будущее требование.** Параллельное выполнение нескольких Activity обязательно рассматривается как отдельная крупная feature: оно соответствует реальной жизни, но требует собственной модели и UI.

## 4.4. Background и restart

**РЕШЕНО.** Active Activity/Sequence должна полностью восстанавливаться после:

- сворачивания приложения;
- блокировки экрана;
- выгрузки UI/process при допустимой технической реализации;
- повторного открытия приложения.

Истина задаётся timestamps + persisted state, а не количеством UI ticks.

Timer, который пересёк zero в фоне, должен восстановиться детерминированно согласно настроенному behavior.

В будущем предусмотрены:

- persistent notification;
- widget;
- дополнительные surfaces для текущего дела.

## 4.5. Manual correction

**РЕШЕНО.** Завершённый Execution можно редактировать:

- start time;
- end time;
- values;
- комментарий;
- и другие допустимые исторические данные.

Overlapping time intervals разрешены. Приложение показывает мягкое предупреждение, но не блокирует сохранение.

---

# 5. Activity Advanced Settings

Основной Activity Editor показывает только secondary Settings entry point. Подробный экран пока не проектируется.

Потенциальные настройки:

- display seconds;
- countdown before start;
- Timer: finish at zero / continue overtime;
- sound/vibration at boundaries;
- pause/resume behavior;
- keep screen awake;
- confirmation before Finish/interrupt;
- другие вторичные behavior options.

**РЕШЕНО.** Эти настройки не должны перегружать основной Editor.

Точная иерархия settings при использовании Activity внутри Sequence описана в разделе 10.

---

# 6. Activity Editor — согласованный UX

## 6.1. Роль

**РЕШЕНО.** Один conceptual Activity Editor используется и для самостоятельной Activity-конфигурации, и для Sequence Step.

## 6.2. Визуальная база

Принятое направление:

- Variant B — layout раскрытого содержимого;
- Variant C — collapsible section header pattern;
- итоговый экран должен быть гибридом B + заголовков C, а не новым стилем.

Focused editor **без основной bottom navigation**.

## 6.3. Header

- Back;
- Activity name/context;
- Done/Save;
- restrained Settings/overflow.

## 6.4. Basic Information

Компактные поля:

- Activity Name;
- Short Comment;
- optional `Part of Workout A` context.

## 6.5. Collapsible sections

Секции не являются strict accordion; одновременно могут быть раскрыты несколько.

Collapsed header показывает summary:

- `Time tracking` / `Timer · 1:30`;
- `Custom fields` / `4 fields · Reps shown as main value`.

## 6.6. Time Tracking expanded

- Stopwatch;
- Timer;
- None.

При Timer показывается Duration value control.

## 6.7. Custom Fields expanded

Компактный grouped list в стиле Variant B:

- Reps / Number · reps / value / Main Value;
- Weight / Number · kg;
- Difficulty / Category;
- Notes / Text;
- `+ Add field`.

Numeric rows могут иметь compact `− / value / +` controls.

---

# 7. Быстрые value controls

## 7.1. Tap

**РЕШЕНО направление.** Tap по compact value chip открывает точный редактор значения.

## 7.2. Vertical drag

**РЕШЕНО направление.** Vertical drag, начатый **непосредственно на value chip**, позволяет быстро менять значение.

Во время drag показывается небольшой wheel-like context с меньшим/текущим/большим значением.

Пример:

- `1:35`
- `1:30` current
- `1:25`

## 7.3. Gesture cancel

**РЕШЕНО направление.** Во время vertical adjustment пользователь может уйти пальцем влево за явный threshold.

UI переходит в Cancel state. Отпускание возвращает исходное значение.

Небольшой случайный горизонтальный drift не должен отменять действие.

## 7.4. Gesture conflict

Value adjustment начинается только с value chip. Long press / drag по телу sequence row используется для manipulation mode / reorder.

Это разделяет hit targets и снижает конфликт жестов.

---

# 8. Sequence Editor — normal state

## 8.1. База

**РЕШЕНО.** Variant A выбран основой.

Характеристики:

- compact grouped list;
- Repeat как лёгкая nested surface;
- compact value controls;
- `Add step`;
- `Auto-insert`;
- без постоянных manipulation handles в normal mode;
- sequence-level settings спрятаны за Settings/overflow.

## 8.2. Step display

Sequence Step остаётся обычным Activity.

Строка показывает Activity name + самый релевантный compact control/value:

- `Warm-up [5:00]`;
- `Bench press [Stopwatch]`;
- `Rest [1:30]`;
- `Push-ups [15 reps]`;
- `Stretch [Complete only]`.

`Complete only` соответствует `No live tracking` без Main Value.

## 8.3. Repeat

**РЕШЕНО.** Repeat ×N:

- компактная nested surface;
- не сильная синяя карточка;
- editable N;
- обычные Activity Step внутри.

## 8.4. Drag между Repeat и списком

**РЕШЕНО.** В manipulation mode можно:

- перетащить Step внутрь Repeat;
- вытащить Step из Repeat;
- переместить Repeat целиком;
- drag-copy Repeat целиком.

Вложенный Repeat всё равно запрещён: drop, который создал бы nested Repeat, должен быть недоступен/отклонён с понятной обратной связью.

## 8.5. Add Step

Compact action внизу списка.

## 8.6. Auto-insert / Generate

**РЕШЕНО концептуально.** Поддерживает как минимум:

1. несколько физических копий одного Activity;
2. вставку Activity между существующими Step;
3. чередование;
4. повтор физического pattern.

После генерации каждый созданный Step обычный и полностью редактируемый.

Это принципиально отличается от Repeat Group.

---

# 9. Sequence Editor — manipulation session

## 9.1. Вход

**РЕШЕНО.** Long press по Step включает manipulation mode для всего Sequence Editor.

## 9.2. Toolbar

Контекстный верхний bar:

- `X` — отменить всю manipulation session;
- Undo;
- Redo;
- `✓` — применить всю session.

Undo/Redo относятся ко всей session и сохраняются при выборе другого Step.

## 9.3. Handles

**РЕШЕНО.** Пока manipulation mode активен, manipulation handles показываются у всех редактируемых элементов, а не только selected.

Две разные ручки:

- Move/Reorder original;
- Duplicate-and-Move copy.

Можно сразу потянуть handle другого Step: он автоматически становится selected и операция начинается без дополнительного tap/long press.

## 9.4. Selected state

Один Step выделен:

- немного более толстая restrained accent outline;
- subtle surface difference;
- без сильной заливки.

## 9.5. Tap behavior

В manipulation mode:

- tap по другому Step → перенести selection, не выходить;
- tap по already selected Activity content → открыть Activity Editor;
- tap по value chip → редактировать это значение;
- drag handles доступны напрямую.

## 9.6. Tap outside

**ПРЕДЛОЖЕНИЕ.** Tap по действительно пустой зоне пытается выйти из manipulation mode.

При unsaved changes:

- Apply;
- Discard;
- optional `Always apply` / `Always discard` preference.

Точный scope preference пока не важен для модели данных.

---

# 10. Settings hierarchy: Activity, Sequence и Step

## 10.1. Уровни настроек

**РЕШЕНО.** Есть три уровня:

1. Activity/Template settings — базовые defaults конкретного дела;
2. Sequence-level settings — правила выполнения Sequence в целом;
3. explicit per-Step override внутри конкретной Sequence — намеренное исключение для одного Step.

Базовый приоритет:

`Activity defaults < Sequence settings < explicit Step override`.

Sequence-level настройка сильнее обычной настройки Activity, скопированной из Template. Но если пользователь явно задал override именно для Step внутри Sequence, этот override сильнее Sequence.

UI не должен показывать пользователю сложную систему наследования без необходимости. Override появляется только как advanced choice.

## 10.2. Overtime vs Auto-advance

**РЕШЕНО.** Если конкретный Step явно настроен продолжать Timer в overtime после zero, это Step override и точной автоматической границы завершения нет.

Следовательно:

- в zero Step не завершается;
- Sequence Auto-advance не срабатывает;
- timer продолжает идти в overtime;
- пользователь вручную завершает Step;
- в Sequence Settings/validation показывается небольшое предупреждение о конфликте.

После ручного завершения обычное правило перехода снова применяется.

## 10.3. Auto-advance semantics

**РЕШЕНО.** Auto-advance означает не «магически завершать любой Step», а автоматически запускать следующий Step **после того, как текущий получил валидное событие завершения**.

### Timer с естественной границей zero

Если:

- Timer настроен завершаться в zero;
- нет Step override overtime;
- Auto-advance включён;

то в zero текущий Execution завершается и следующий Step запускается автоматически с учётом countdown/transition settings.

### Stopwatch

Stopwatch не имеет естественной границы конца.

При Auto-advance ON основное действие может быть семантически `Next` / `Finish & start next`: оно завершает текущий Execution и сразу запускает следующий.

При Auto-advance OFF действие завершает текущий Step, после чего Sequence остаётся активной без current Step и появляется отдельное действие `Start next`.

### No live tracking

Step становится current и ждёт `Complete`.

При Auto-advance ON нажатие `Complete` сразу запускает следующий Step.

При Auto-advance OFF `Complete` завершает текущий Step, после чего пользователь отдельно запускает следующий.

### Timer, завершённый вручную раньше zero

Сохраняется фактически прошедшее время. После manual completion переход подчиняется тем же Auto-advance rules.

## 10.4. Countdown/sound inheritance

**РЕШЕНО направление.**

Чтобы не было двух countdown или двух сигналов подряд:

- standalone Activity использует Activity setting;
- внутри Sequence явно заданная Sequence setting заменяет соответствующий Activity-level default;
- explicit per-Step override сильнее Sequence setting.

Это правило применяется к countdown, sound/vibration и аналогичным boundary behavior.

## 10.5. Show seconds

**РЕШЕНО.** `Show seconds` — настройка presentation, а не точности хранения.

Она главным образом влияет на elapsed Stopwatch/длинные отображения времени.

Timer всегда показывает точность, необходимую для его заданного значения. Timer `1:30` не округляется до минуты только из-за `Show seconds = off`.

## 10.6. Sequence Settings — предполагаемый набор

Основной Editor хранит только secondary Settings entry point. Потенциальные настройки:

- Auto-advance;
- countdown before starting Sequence;
- countdown before each Step;
- timer-end / transition sound;
- vibration;
- timer behavior / overtime policy;
- keep screen awake;
- pause behavior;
- confirmation on runtime jump;
- confirmation on early End Sequence;
- другие переходные rules.

Detailed settings screen пока не проектируется.

# 11. Sequence Execution

## 11.1. Общая концепция

**РЕШЕНО.** Sequence Execution — runtime-представление тех же обычных Activity в определённом порядке.

Внутри full-screen view Step используют знакомый Activity Item visual language:

- title;
- optional Short Comment;
- timing/Main Value metadata;
- state.

Развёрнутый экран по смыслу похож на локальный `Daily`: есть уже выполненные, current и upcoming Step, только порядок заранее задан Sequence.

## 11.2. Collapsed Daily Active representation

Если активна Sequence, Daily Active показывает compact active-sequence card:

- Sequence name;
- total sequence active time — secondary;
- current Step;
- главное значение current Step;
- Pause;
- action текущего Step;
- Next Step preview;
- Expand.

Весь список Sequence на Daily не показывается.

## 11.3. Expanded view

Focused full-screen view без основной bottom navigation.

Структура:

- compact header: Sequence name + total active time;
- scrollable ordered list;
- current Step визуально выделен;
- secondary overflow для jump/add/end etc.;
- управление текущим Step остаётся доступным.

Шаги должны отображать ту же информацию, что обычные Activity Items: Short Comment, фактическое/целевое значение, time metadata. Не создаётся отдельная workout-specific строка.

## 11.4. Runtime occurrence states

`Runtime occurrence` — технический термин документа, не обязательный термин UI.

Он означает **конкретный экземпляр Sequence Step внутри одного конкретного Sequence Execution**.

Пример: Step `Rest` внутри `Repeat ×3` создаёт три разных occurrence — Rest iteration 1, Rest iteration 2, Rest iteration 3. Runtime-added Step также создаёт отдельный occurrence.

У occurrence есть собственные runtime-state и linkage на исходный Step snapshot.

Внутренне различаются как минимум:

- `completed`;
- `current`;
- `upcoming/not_started`;
- `skipped`;
- `deleted_execution`/tombstone для отдельного сценария исторического удаления.

Если Step был **начат**, он уже создаёт Activity Execution и не должен исчезать как будто его не существовало.

Для started Execution можно дополнительно хранить технический `completionReason`, например:

- natural_timer_end;
- manual_finish;
- advanced_to_next;
- jump;
- sequence_ended_early.

Это не обязательно становится главным пользовательским статусом, но сохраняет контекст.

## 11.5. Started Step никогда не теряется автоматически

**РЕШЕНО.** Если Step уже начат, а пользователь переходит дальше до обычного конца:

- фактическое Execution сохраняется;
- Stopwatch сохраняет фактически прошедшее active time;
- Timer сохраняет фактически прошедшее время, даже если target duration не достигнута;
- actual Custom Fields сохраняются в текущем состоянии/default;
- intermediate untouched Step, через которые пользователь перепрыгнул, могут быть `skipped` без Activity Execution.

Принцип: «как фактически сделал — так и сохранили».

## 11.6. Completed

Completed Step остаётся читаемым, но визуально вторичен.

Фактически выполненные/начатые Activity создают Activity Execution и доступны будущей Statistics.

## 11.7. Current

Current Step получает restrained accent highlight.

Если current Step — Timer, допустим и желателен progress fill / running strip внутри row surface, отражающий progress/remaining time.

Row сохраняет:

- title;
- Short Comment;
- relevant time/Main Value metadata.

## 11.8. Upcoming

Обычный нейтральный Activity Item с конфигурационными default/target values.

## 11.9. Main current value

### Timer

Главное значение — remaining time.

### Stopwatch

Главное значение — current elapsed time.

### No live tracking + Main Value

Главное значение — Main Value, инициализированное default и редактируемое как actual.

### No live tracking без Main Value

Live timer отсутствует. Основное действие — `Complete`.

## 11.10. Sequence total time

**РЕШЕНО направление, детали classification ещё уточняются в v0.5.**

Sequence хранит собственную runtime timeline независимо от mode отдельных Activity.

Минимально хранятся:

- `startedAt`;
- `endedAt`;
- explicit pause intervals;
- Step occurrence runtime intervals/state;
- wall-clock span;
- derived active duration;
- derived pause/idle duration.

В UI должны быть доступны как минимум два независимых показателя:

- время Sequence **без пауз**;
- суммарное время пауз/ожиданий.

Их не нужно обязательно складывать в один главный показатель.

Ключевое новое правило: промежуток, в котором Sequence продолжается как session, но **ни один Step не выполняется**, не считается полезным active time. Он классифицируется как pause/idle time.

Например при Auto-advance OFF:

- Step A завершён в 10:05;
- Step B пользователь запустил в 10:07;
- 10:05–10:07 становится pause/idle interval Sequence.

Wall-clock span при этом не теряется.

Для исторического редактирования Sequence active/pause accounting должен быть согласован с фактической timeline, а не храниться как независимое число, которое можно случайно рассинхронизировать.

## 11.11. Pause

**РЕШЕНО.** Одна explicit Pause относится ко всей Sequence.

Pause:

- замораживает current Timer/Stopwatch;
- прекращает накопление Sequence active duration;
- создаёт explicit pause interval;
- не завершает current Execution;
- после Resume восстановление идёт по persisted timestamps/state.

Кроме explicit Pause, Sequence может иметь **implicit idle/pause intervals**, когда current Step отсутствует, например после manual completion при Auto-advance OFF.

UI может агрегировать explicit pause + implicit idle в общее `Pause`, но внутри причины полезно хранить отдельно для корректного восстановления и будущей аналитики.

## 11.12. Basic runtime controls

Основной happy path должен оставаться минимальным:

- Pause/Resume;
- действие завершения current Step;
- автоматический либо ручной запуск следующего в зависимости от Auto-advance.

Отдельной постоянно видимой `Skip` кнопки не требуется.

Jump/skip/early end находятся в secondary context menu, чтобы нормальное прохождение Sequence не перегружалось.

## 11.13. Jump / Make next

Контекстное меню Step может позволять:

- `Go now` — когда нет другого current Step либо когда сценарий явно допускает немедленный переход;
- `Make next` — назначить Step следующим после current.

При переходе вперёд untouched Step между current и выбранным могут стать `skipped`.

Если current Step уже начат, он сохраняется с фактическими данными перед переходом.

Прямой random tap по row не должен случайно менять runtime order.

## 11.14. Runtime Add Step

**РЕШЕНО минимально.** Полное структурное редактирование активной Sequence запрещено, но допускается one-off runtime addition.

Источником может быть:

- существующий Activity Template;
- быстро созданная one-off Activity Config.

В обоих случаях создаётся snapshot только для текущего Sequence Execution.

Допустимые insertion targets:

- `Add to end`;
- `Add after current`;
- `Start now` только когда сейчас нет current Step.

Runtime-added Step:

- не меняет Sequence Template;
- сохраняется в истории конкретного Sequence Execution;
- позже может получить отдельное explicit действие `Save to sequence template`, но это не часть первой версии.

Для уже completed occurrence не используется повторное открытие старого Activity Execution.

Если пользователь хочет выполнить тот же Step ещё раз, secondary action `Do again` создаёт **новый runtime occurrence + новый Activity Execution**.

## 11.15. Early End Sequence

Early End находится в secondary menu и не должен быть легко нажимаемым по ошибке.

При раннем завершении:

- все уже начатые Activity Execution сохраняются;
- current started Execution также сохраняется с фактически накопленными данными;
- untouched remaining occurrences остаются `not_started` или получают technical skipped/end state;
- Sequence Execution получает технический статус `ended_early`/аналогичный.

Отдельная кнопка «прервать current и не сохранять вообще ничего» в первой версии не нужна. Пользователь при необходимости может потом вручную удалить historical Execution.

## 11.16. Final Step

После фактического завершения последнего Step Sequence считается завершённой естественным образом. Отдельная обязательная ручная кнопка `Finish Sequence` не нужна, если больше нет remaining runtime occurrences.

## 11.17. Repeat execution

Repeat разворачивается в runtime occurrences.

Каждый occurrence хранит связь:

- с исходным Step snapshot;
- с Repeat Group;
- с iteration index.

Каждая итерация получает независимый Activity Execution и независимые actual Custom Field values.

## 11.18. Background execution

**РЕШЕНО.** Active Sequence полностью работает в фоне по persisted timestamps/state.

Timer, countdown и автоматические переходы должны быть детерминированными после background/restart.

Если по настроенным rules Timer завершился и Auto-advance допускает следующий Step, runtime state восстанавливается так, как будто переход произошёл в рассчитанный момент, а не в момент открытия UI.

Это правило действует независимо от типа следующего Step.

Например:

- Timer A заканчивается в 12:00:00;
- следующий Step — Stopwatch B;
- Auto-advance ON;
- приложение в background.

Stopwatch B считается начатым в 12:00:00 и продолжает elapsed time в фоне.

Это сознательно простая и строгая семантика Auto-advance. Пользователь, которому нельзя автоматически начинать следующий Step без физического подтверждения, должен отключить Auto-advance/использовать соответствующий override.

No-live Step не может сам завершиться: если Sequence дошла до него в фоне, он становится current и ожидает explicit completion.

# 12. No live tracking внутри Sequence

**РЕШЕНО.**

Standalone `No live tracking` Activity по explicit completion action сразу создаёт завершённый Execution:

- сохраняется completion timestamp;
- duration отсутствует;
- live active slot не занимается дольше одного действия.

Внутри Sequence переход на такой Step работает иначе:

- Step становится current;
- individual live timer отсутствует;
- Sequence total active duration продолжает идти;
- Step ждёт explicit `Complete`;
- Main Value/default и другие поля доступны для изменения actual.

После `Complete`:

- при Auto-advance ON следующий Step запускается автоматически;
- при Auto-advance OFF Sequence остаётся активной без current Step и предлагает `Start next`.

Само назначение Step current никогда не считается его выполнением.

# 13. Template → Snapshot propagation

## 13.1. Default behavior

**РЕШЕНО.** Всегда snapshot.

Изменение Template не обновляет существующий Sequence Step автоматически.

Редактирование Activity внутри Sequence по умолчанию меняет только этот snapshot.

Historical Activity Execution / Sequence Execution никогда не участвуют в Template propagation.

## 13.2. Локальная модификация snapshot

**РЕШЕНО упрощённо для первой версии.**

Snapshot имеет whole-object признак `locallyModified`.

Пока **не** ведём per-property override tracking.

Следствие:

- если Step никогда локально не редактировали, bulk update может безопасно заменить его новым snapshot Template;
- если Step локально редактировали хотя бы частично, он считается modified целиком;
- bulk update предлагает либо пропустить modified snapshots, либо явно заменить их полностью.

Более тонкое выборочное обновление отдельных полей — возможное будущее улучшение.

## 13.3. Действия Step ↔ Template

Для Step, созданного из Template, нужны advanced actions:

1. `Update from source template` — заменить текущий Step snapshot актуальной конфигурацией источника;
2. `Update source template` — записать текущую Step-конфигурацию обратно в исходный Template;
3. `Save as new template` — создать новый Activity Template из текущей Step-конфигурации.

После `Save as new template` текущий Step начинает считать новый Template своим `sourceTemplate`, а старая source-link заменяется.

Все эти действия explicit и не выполняются автоматически.

`Update source template` сам по себе не обязан немедленно распространять изменение на другие snapshots: bulk propagation запускается отдельно.

## 13.4. Template-level bulk update

При редактировании Activity Template существует advanced действие вроде `Update linked snapshots`.

Scope:

- только редактируемые конфигурационные snapshots, прежде всего Sequence Steps;
- **никогда** historical Executions.

Пользователь выбирает упрощённый режим:

- update only snapshots that were never locally modified;
- update all linked snapshots, включая modified, с полной заменой snapshot.

Можно обновлять Step этого Template в разных Sequence.

## 13.5. Deleted source Template

Если source Template удалён:

- существующий Step snapshot продолжает полноценно работать;
- historical data не меняются;
- `Update from source template` становится недоступен;
- Step всё ещё можно `Save as new template`.

Source linkage является metadata, а не условием существования Step.

# 14. Daily View

## 14.1. Daily как основной temporal/history surface

**РЕШЕНО.** Главный экран — Daily view. Today — специальное состояние выбранного дня.

Даты переключаются header controls / swipe.

## 14.2. Today idle

Основные блоки:

- date header;
- Planned;
- Completed;
- compact primary action снизу.

Planned на Today может иметь чуть более сильную surface, потому что это главное actionable content.

## 14.3. Planned Activity Item

Текущая система:

- title;
- Short Comment;
- metadata;
- trailing action.

Metadata examples:

- exact boundaries: `18:30–19:25 · 55 min`;
- exact start + approximate duration: `18:30 · ~55 min`;
- day only: `Anytime today`;
- week: `This week`.

Если mode `No live tracking`, future trailing action должен семантически означать немедленное completion, даже если prototype icon пока общий.

## 14.4. Completed Activity Item

Adaptive two-column layout:

LEFT:

- title;
- optional Short Comment.

RIGHT:

- duration, если существует;
- start–end range, если существует.

No-live-tracking Execution может иметь только completion timestamp и не имеет duration.

UI схлопывает отсутствующие строки, не вставляя фиктивные нули.

## 14.5. Today active

Active Activity/Sequence получает highest priority, но не уничтожает контекст дня.

Обычная active Activity:

- title;
- main live value;
- Pause;
- Finish.

Sequence использует collapsed active-sequence variant из раздела 11.

## 14.6. Past day

**РЕШЕНО направление.** Completed становится главным контентом и не выглядит disabled/серым.

Что делать с неисполненными прошлым Plan — пока отложено до полноценного planning/history design.

---

# 15. Plan — только уже спроектированная часть

## 15.1. Основной экран

**РЕШЕНО направление.** Основной everyday Plan screen — week context + selected day.

Отдельный Day tab пока не нужен.

Scale:

- Week;
- Month (будет позже).

## 15.2. Week strip

MON–SUN + dates + small dots planned-density.

Selected day выделен accent.

## 15.3. Selected day

Показывает:

- exact-time Plan;
- anytime-today Plan.

## 15.4. This Week

Отдельный блок для Plan с precision=week.

Не дублировать `This week` бессмысленно и в section title, и в каждой строке.

## 15.5. Action semantics

Для live-tracked Activity действие может быть Start.

Для `No live tracking` по смыслу действие фактически является instant completion.

Предложение long-press quick menu (`Mark completed`, etc.) сохраняется, но detailed interaction отложен.

---

# 16. История и редактирование

## 16.1. Исторические Execution независимы от Template

**РЕШЕНО.** Automatic Template propagation никогда не должна незаметно переписывать историю.

## 16.2. User edits history explicitly

**РЕШЕНО.** Сам пользователь может редактировать завершённый Execution.

Для обычного Activity допустимы:

- start/end;
- фактические Custom Field values;
- Short Comment;
- другие явно исторические данные.

Это explicit historical correction и не считается нарушением snapshot principle.

Custom Field schema в history не редактируется.

## 16.3. Overlap

**РЕШЕНО.** Пересечения time intervals разрешаются.

UI показывает warning, но не блокирует сохранение.

Внутри historical Sequence overlap требует особенно осторожного пересчёта derived active/pause timeline; точное правило union/overlap вынесено в вопросы v0.5.

## 16.4. Skipped/not-started sequence metadata

Skipped/not-started occurrence может храниться в Sequence Execution даже если не создаёт Activity Execution.

Так мы не теряем структуру фактического run, не загрязняя Activity history фиктивными выполнениями.

## 16.5. Удаление Activity Execution внутри historical Sequence

**РЕШЕНО: нужны два уровня удаления.**

### Удалить только Activity Execution

По умолчанию можно удалить конкретное выполненное Activity, но сохранить occurrence в historical Sequence.

Тогда:

- Activity Execution/value data удаляются;
- occurrence остаётся как structural tombstone (`Deleted execution`);
- исходная структура run понятна;
- Sequence start/end и ранее сохранённая runtime timeline не должны молча исчезать только из-за удаления child record.

### Полностью убрать Step из historical Sequence

Пользователь также должен иметь advanced возможность удалить сам occurrence/tombstone, чтобы вручную очистить историю Sequence «как будто этого Step там не было».

Это уже structural historical edit.

Модель должна позволять после такого изменения:

- сдвигать время следующих Step;
- менять start/end соседних Step;
- перераспределять возникший gap между active/pause;
- при необходимости корректировать end time Sequence.

Точная политика — оставлять gap по умолчанию или автоматически `close gap` — вынесена в v0.5.

## 16.6. Редактирование child Execution и Sequence timeline

**ПЕРЕСМОТРЕНО.** Sequence total active time не должен быть полностью независимым immutable числом от child timeline.

Пример:

- Step A был 10:00–10:10;
- следующий Step B начинается 10:10;
- пользователь исправляет A на 10:00–10:08.

Если B не сдвинут, 10:08–10:10 становится pause/idle Sequence.

То есть:

- active time уменьшается;
- pause/idle time увеличивается;
- wall-clock start/end Sequence может остаться прежним.

Если пользователь затем вручную сдвигает B и остальные Step раньше, timeline пересчитывается снова.

Таким образом historical editor должен работать с timeline, а active/pause duration являются derived значениями из неё, а не двумя независимыми редактируемыми счётчиками.

## 16.7. Countdown precedence

**РЕШЕНО.** Если одновременно заданы:

- countdown before Sequence = 5 s;
- countdown before each Step = 3 s;

то для первого Step не запускаются два countdown подряд.

Sequence-start countdown заменяет per-Step countdown для первого Step.

Для следующих Step действует per-Step countdown.

## 16.8. Unit/schema edits

**РЕШЕНО.** В history меняется value, а не Field type/name/unit.

Если конфигурации нужно поле с другой единицей/семантикой, создаётся новый Field для будущих Execution.

---

# 17. Visual system

## 17.1. Базовый стиль

**РЕШЕНО.**

- light neutral background;
- restrained blue accent;
- Inter;
- moderate radii;
- subtle grouped surfaces;
- typographic hierarchy важнее декоративных рамок;
- без обязательных Activity icons;
- без чрезмерного Material 3 Expressive характера;
- без декоративных gradients как самоцели.

## 17.2. Theme architecture

Semantic tokens, а не десятки независимых пользовательских цветов:

- background;
- surface;
- secondary surface;
- text primary;
- text secondary;
- border/divider;
- accent;
- accent soft;
- system success/warning/error.

Первичные presets:

- Light Neutral + несколько accent variants;
- Dark Neutral + те же accent variants.

Advanced theme editor позже.

## 17.3. Icons

System/function icons допустимы для:

- Timer/Stopwatch;
- Pause;
- Move;
- Duplicate;
- Settings;
- navigation.

Activity icon не обязателен.

---

# 18. Tags/folders — только уже решённое

**РЕШЕНО концептуально.**

- Folder = где объект находится;
- Tag = свойства/метки объекта.

Folder может содержать:

- Activity Template;
- Sequence Template;
- nested folders.

UI не должен поощрять чрезмерную глубину, но техническая вложенность может существовать.

Tags не являются обязательным источником визуального цвета.

---

# 19. Технические/доменные инварианты для реализации

1. На первой версии максимум одна незавершённая live session на всё приложение.
2. Paused session всё равно занимает active slot.
3. Параллельные Activity — будущая отдельная крупная feature, но модель не должна заранее делать её невозможной.
4. Activity может существовать без Template.
5. Sequence Step — полноценный Activity Config Snapshot.
6. Template → Step всегда snapshot по умолчанию.
7. Template changes не переписывают historical Execution автоматически.
8. Historical Execution меняется только explicit ручным редактированием пользователя.
9. Bulk Template update применяется только к редактируемым config snapshots.
10. В первой версии locally modified snapshot отслеживается целиком, не per-property.
11. Sequence Template changes не меняют active/finished Sequence Execution.
12. No live tracking standalone создаёт завершённый Execution без duration.
13. Standalone No-live Main Value можно скорректировать до completion; при нескольких fields допустим quick-completion sheet.
14. No live tracking внутри Sequence становится current и ждёт explicit Complete.
15. Все Custom Fields первой версии optional.
16. Time tracking и Custom Fields независимы.
17. Main Value — обычный numeric Custom Field.
18. configured/default value и actual Execution value хранятся отдельно.
19. actual изначально инициализируется configured/default значением.
20. Short Comment snapshot-ится как остальные свойства.
21. Field type/unit не мигрируют: semantic replacement создаёт новый Field; display-name rename ожидает финального подтверждения.
22. В history Custom Field schema не редактируется; меняется только value.
23. Time calculations восстанавливаются по persisted timestamps/state.
24. Sequence хранит wall-clock timeline и derived active/pause durations.
25. Период без current Step внутри незавершённой Sequence классифицируется как pause/idle, а не active work.
26. Explicit Pause и implicit idle полезно хранить раздельными reasons, даже если UI суммирует их.
27. Pause Sequence паузит current live timer и sequence active duration.
28. Repeat и Auto-insert physical copies — разные сущности.
29. Nested Sequence запрещена.
30. Nested Repeat запрещён.
31. Drag Step внутрь/наружу Repeat разрешён, если не создаёт вложенность.
32. Move и Duplicate-and-Move — отдельные manipulation operations.
33. Undo/Redo manipulation session не сбрасываются при смене selected Step.
34. Runtime full structure editing Sequence не поддерживается; разрешён minimal one-off Add Step.
35. Runtime-added Step не изменяет Sequence Template.
36. Completed Step не переоткрывается: `Do again` создаёт новый occurrence/Execution.
37. Started Step при переходе/early end сохраняет Activity Execution с фактическими данными.
38. Untouched skipped Step не создаёт успешный Activity Execution.
39. Sequence может хранить technical completionReason/status отдельно от Activity фактических данных.
40. Auto-advance запускает следующий Step только после валидного события завершения текущего.
41. Stopwatch требует explicit manual finish; Auto-advance только определяет, стартует ли следующий сразу после этого.
42. Overtime Step блокирует automatic transition at zero.
43. Sequence-level setting сильнее Activity default; explicit per-Step override сильнее Sequence.
44. Sequence-start countdown заменяет before-each-step countdown для первого Step.
45. Timer сохраняет точность duration независимо от UI-настройки Show seconds.
46. Auto-advance в background работает независимо от типа следующего Step, включая Stopwatch.
47. Background/restart не должен изменять смысл Timer/Stopwatch/countdown/auto-advance behavior.
48. Overlap historical intervals разрешён с warning.
49. Historical Sequence active/pause values должны быть согласованы с timeline и пересчитываться после time corrections.
50. Child Activity Execution можно удалить, оставив structural tombstone в Sequence history.
51. Structural occurrence можно удалить отдельно как advanced historical edit.
52. Save as new template из Sequence Step переводит Step на новый source Template.
53. Execution хранит достаточные field/config snapshots для будущей Statistics независимо от текущей схемы Template.

# 20. Отложено

До следующих итераций сознательно не проектируются подробно:

- Start Activity flow;
- quick create one-off Activity;
- Save one-off as Template;
- Add/Edit Plan flow;
- recurrence;
- Month Plan;
- Statistics overview;
- Activity Statistics;
- Sequence Statistics;
- Custom Field Statistics;
- customizable Statistics dashboard;
- Library;
- tags/folders editor;
- Advanced Activity Settings screen;
- Advanced Sequence Settings screen;
- Auto-insert menu;
- detailed Custom Field creation/editing;
- widget / persistent notification UX;
- multiple parallel active sessions;
- dark-theme visual pass;
- advanced theme editor.

---

# 21. Проверка реальными сценариями

Цель раздела — не придумать новую функциональность, а проверить, что текущая модель удобно описывает реальные последовательности без специальных workout-only исключений.

## 21.1. Тренировка

Template Sequence:

1. Warm-up — Timer 5:00;
2. Repeat ×3:
   - Bench press — Stopwatch + `Reps` default 10 + optional Weight;
   - Rest — Timer 1:30;
   - Pull-ups — Stopwatch + `Reps` default 8;
   - Rest — Timer 1:30;
3. Squats — Stopwatch + `Reps`;
4. Rest — Timer 2:00;
5. Plank — Timer 1:30.

### Создание

Сценарий укладывается в текущий Sequence Editor без специальных типов:

- Exercise — обычная Activity;
- Rest — обычная Activity с Timer;
- reps/weight — обычные Custom Fields;
- Reps может быть Main Value;
- Repeat — структурный блок;
- все Step являются snapshots и локально редактируются.

### Выполнение при Auto-advance ON

- Warm-up сам завершается в zero и запускает Bench press;
- Bench press Stopwatch идёт, пока пользователь явно не завершит;
- пользователь может изменить actual Reps/Weight;
- нажатие `Next` сохраняет Bench press и сразу запускает Rest;
- Rest автоматически переходит дальше в zero;
- Repeat runtime создаёт отдельные occurrences;
- каждая итерация получает собственные actual values.

### Early finish

Если Bench press target 10 reps, а пользователь сделал 7 и завершил Step:

- сохраняется actual 7, если он изменил value;
- сохраняется фактическая Stopwatch duration;
- это полноценный Activity Execution;
- приложение не требует достижения target.

### Вывод

**Сценарий поддерживается естественно.**

## 21.2. Поход в магазин

Sequence:

1. Иду в магазин — Stopwatch;
2. Нахожусь в магазине — Stopwatch;
3. Возвращаюсь из магазина — Stopwatch.

При Auto-advance ON каждое manual `Next` завершает текущий Stopwatch и немедленно запускает следующий.

Если после магазина пользователь решил не возвращаться домой:

- завершает Step `Нахожусь в магазине`;
- через secondary menu завершает Sequence early;
- первые два Activity Execution сохраняются;
- `Возвращаюсь из магазина` остаётся not-started/skipped в Sequence history;
- никаких фиктивных выполнений не создаётся.

**Сценарий поддерживается естественно.**

## 21.3. Завтрак

Sequence:

1. Мыть посуду — Stopwatch;
2. Варить кашу — Timer 5:00;
3. Поесть — Stopwatch;
4. Выпить витамины — No live tracking.

При Auto-advance ON:

- пользователь вручную завершает мытьё посуды;
- каша стартует;
- Timer в zero завершает Step;
- `Поесть` стартует согласно Sequence transition settings;
- пользователь вручную завершает Stopwatch;
- `Выпить витамины` становится current, но не выполняется автоматически;
- пользователь нажимает Complete;
- как последний Step он завершает Sequence.

Если пользователь не хочет, чтобы после Timer каши сразу автоматически стартовал `Поесть`, он отключает Auto-advance или использует соответствующий transition/Step override.

**Сценарий поддерживается без workout-specific логики.**

## 21.4. Простая бытовая Sequence без измерения времени

Sequence:

1. Проверить документы — No live tracking;
2. Взять ключи — No live tracking;
3. Закрыть окна — No live tracking.

Auto-advance ON:

- каждый Step ждёт explicit Complete;
- после Complete сразу current становится следующий;
- individual duration отсутствует;
- Sequence total active duration всё равно измеряется.

Auto-advance OFF:

- Complete завершает Step;
- появляется Start next;
- до запуска следующего current Step отсутствует;
- этот промежуток считается implicit pause/idle Sequence, а не active time.

**Сценарий поддерживается.**

## 21.5. Чтение по количеству

Activity/Step:

- `Reading`;
- No live tracking;
- Custom Field `Pages`, Number, unit `pages`;
- default=20;
- Main Value=Pages.

В Sequence current Step показывает `20 pages`; пользователь может изменить actual, затем Complete.

Standalone instant-completion flow требует дополнительного UX-решения, если пользователь хочет изменить actual **до** мгновенного completion. Это вынесено в вопросы v0.4.

## 21.6. Timer, завершённый раньше

Step:

- Timer 1:30;
- пользователь через 55 секунд выбирает переход дальше.

Сохраняется:

- configured target 1:30;
- фактически прошедшее active time 0:55;
- technical completionReason `advanced_to_next`/аналогичный;
- следующий Step запускается согласно Auto-advance.

**Сценарий однозначен.**

## 21.7. Timer overtime

Step:

- Timer 1:30;
- explicit Step override: allow overtime.

В zero:

- timer не завершает Step;
- Auto-advance не срабатывает;
- UI переходит в overtime presentation;
- пользователь вручную завершает, например на 1:47;
- фактическая duration = 1:47;
- после Finish следующий Step запускается согласно Auto-advance.

**Сценарий однозначен; UI должен предупреждать о конфликте Auto-advance + overtime.**

## 21.8. Background chain

Sequence:

1. Timer 1:00;
2. Timer 2:00;
3. No live tracking.

Auto-advance ON, приложение в background.

Ожидаемое state-machine поведение:

- первый Timer заканчивается через минуту;
- второй автоматически становится current/starts;
- через следующие две минуты второй завершается;
- третий становится current;
- третий не выполняется автоматически;
- при возвращении пользователь видит именно третий Step current, а не задержанное состояние первого Timer.

UI notification/widget позже должны отражать переходы, но data/state semantics уже определены.

## 21.9. Repeat + unexpected extra step

Во время второй итерации Repeat пользователь решает сделать дополнительный Rest.

Runtime menu:

- `Add after current → Rest`;
- создаётся one-off runtime Step;
- Template и Repeat Group не меняются;
- после окончания Sequence этот Step остаётся частью snapshot истории конкретного Sequence Execution.

**Сценарий поддерживается минимальным runtime Add Step.**

## 21.10. Template update после локального изменения Sequence Step

- `Bench press` Step создан из Template;
- Step локально изменён, например default reps;
- Template позже изменён.

По текущей упрощённой модели весь Step считается `locallyModified`.

Bulk update:

- режим `only unmodified` пропускает Step целиком;
- режим `replace all` полностью заменяет Step актуальным Template snapshot.

Historical Activity/Sequence Execution не затрагиваются.

**Сценарий детерминирован, хотя грубый. Более тонкое merge поведение отложено.**

---

# 22. Решения v0.4 после дополнительной проверки

## 22.1. Quick completion полей

Standalone No-live Activity может оставаться мгновенным completion flow без отдельной live session.

Если есть один Main Value, он доступен для быстрой правки до completion.

Если нужно несколько значений, допускается compact quick-completion sheet.

Это сохраняет простой сценарий и не превращает No-live Activity в скрытый Timer/Stopwatch.

## 22.2. Required fields

Required Custom Fields в первой версии отсутствуют.

Это принципиально упрощает:

- Auto-advance;
- background Timer;
- No-live quick completion;
- восстановление после restart.

Позже required semantics можно добавить как отдельный workflow с осознанными остановками.

## 22.3. Повтор completed Step

Старый Execution immutable в смысле runtime identity.

`Do again` создаёт новый occurrence/Execution.

Это соответствует общей модели «как фактически происходило, так и сохраняем».

## 22.4. Runtime Add

Runtime-added Activity всегда snapshot конкретного run.

Sequence Template не меняется автоматически.

## 22.5. Save as new template

Новый Template становится source текущего Step.

## 22.6. Historical deletion

Поддерживается tombstone-подход плюс отдельное advanced structural removal.

## 22.7. Historical time correction

Активное время Sequence и pause/idle должны пересчитываться согласованно с timeline.

Это заменяет прежнее правило, где total active duration считался полностью независимым от child corrections.

## 22.8. Countdown precedence

На первом Step применяется один start countdown, а не сумма Sequence-start + per-step countdown.

## 22.9. Background Auto-advance

Auto-advance остаётся строгим: следующий Stopwatch может начаться в background автоматически.

## 22.10. Unit

Unit является частью schema Number Field и в первой версии не меняется на месте.

---

# 23. Дополнительная проверка сценариев v0.4

## 23.1. Sequence с Auto-advance OFF

1. Stopwatch A идёт 3:00.
2. Пользователь завершает A.
3. Следующий Step пока не стартует.
4. Пользователь разговаривает 2:00.
5. Нажимает Start next.
6. Timer B идёт 1:30.

Timeline:

- A = active;
- 2:00 между A и B = implicit pause/idle;
- B = active;
- wall-clock сохраняет все 6:30;
- active/pause показываются отдельно.

Модель поддерживает сценарий без фиктивной Activity «Pause».

## 23.2. Historical shortening

Исходно:

- A: 10:00–10:10;
- B: 10:10–10:20.

Пользователь исправляет A → 10:00–10:08.

Если B не трогать:

- 10:08–10:10 становится pause/idle;
- Sequence wall span остаётся 20 min;
- active уменьшается на 2 min;
- pause увеличивается на 2 min.

Если пользователь затем сдвигает B → 10:08–10:18 и Sequence end → 10:18, gap исчезает.

## 23.3. Удаление child Execution

Sequence history:

1. Walk to store;
2. In store;
3. Walk home.

Пользователь удаляет Activity Execution `In store`.

Default historical representation может сохранить:

1. Walk to store;
2. Deleted execution;
3. Walk home.

Sequence runtime structure остаётся понятной.

Advanced structural cleanup может затем убрать сам occurrence и отдельно скорректировать timeline.

## 23.4. Timer → Stopwatch в background

- Timer заканчивается в 15:00:00;
- Auto-advance ON;
- следующий Stopwatch;
- приложение закрыто до 15:03.

При восстановлении Stopwatch показывает ~3:00 elapsed, потому что start timestamp = 15:00:00.

Модель предсказуема и не зависит от foreground.

## 23.5. No-live + Pages standalone

Activity:

- No live tracking;
- Pages default 20;
- Pages = Main Value.

Перед Complete UI показывает `20 pages`.

Пользователь меняет на `17 pages`, затем Complete.

Execution:

- completion timestamp;
- duration = null;
- actual Pages = 17.

## 23.6. No-live + несколько полей

Activity:

- No live tracking;
- Main Value Reps = 15;
- Weight = 70 kg;
- Difficulty = optional.

При completion compact sheet может показать prefilled values.

Пользователь может:

- изменить часть;
- оставить часть пустой;
- завершить.

Required validation отсутствует.

---

# 24. Финальные решения Activity/Sequence core — v0.5

## 24.1. No-live Step внутри Sequence и Sequence active time

**РЕШЕНО.** По умолчанию время, пока No-live Step является `current`, считается **active time Sequence**.

Пример:

- `Выпить витамины` становится current в 09:00:00;
- пользователь нажимает Complete в 09:00:20;
- Activity Execution остаётся без собственной duration;
- Sequence occurrence хранит runtime interval 09:00:00–09:00:20;
- эти 20 секунд входят в Sequence active time.

Это не превращает No-live Activity в timed Activity: duration отсутствует именно у дочернего Activity Execution.

### Advanced Sequence setting

У Sequence должна быть вторичная настройка вида:

`Time during No-live steps`
- Count as active — default;
- Count as pause.

Если выбран второй вариант, current No-live occurrence по-прежнему хранит entered/completed timestamps, но его interval классифицируется как pause/idle для Sequence totals.

Настройка относится к Sequence runtime accounting, а не к Activity.

## 24.2. Inter-step idle

**РЕШЕНО.** Если current Step отсутствует и Sequence ждёт `Start next`, весь промежуток автоматически классифицируется как implicit pause/idle.

Это особенно важно при Auto-advance OFF.

## 24.3. Transition countdown

**РЕШЕНО.**

- countdown **перед стартом всей Sequence** происходит до `Sequence.startedAt` и не входит ни в active, ни в pause;
- countdown **между Step** относится к transition/pause time, а не к active time следующего Activity.

## 24.4. Historical overlap

**РЕШЕНО.** Historical overlap разрешён с warning.

Для Sequence active duration используется **union активных intervals**, а не арифметическая сумма дочерних duration.

Пример:

- A active: 10:00–10:10;
- B active: 10:08–10:20.

Sequence active union = 20 min, а не 22 min.

Таким образом Sequence active duration не может превысить wall-clock span только из-за overlap.

## 24.5. Полное удаление occurrence из historical Sequence

**РЕШЕНО.** Advanced structural removal должен спрашивать:

- `Leave gap` — удалить occurrence, но сохранить его бывший временной промежуток как pause/idle;
- `Close gap` — удалить occurrence и сдвинуть последующие timeline intervals раньше, закрыв образовавшийся gap.

Автоматически выбирать один из вариантов нельзя.

## 24.6. Редактирование пауз

**РЕШЕНО.** Aggregate `Pause = N min` не является независимо редактируемым числом.

Active/pause totals вычисляются из timeline.

UI может давать действие `Edit pause`, но оно должно редактировать конкретный pause/idle interval или соседние timestamps, после чего totals пересчитываются.

## 24.7. Runtime timestamps No-live occurrence

**РЕШЕНО.** Sequence occurrence хранит собственные runtime timestamps (`enteredAt`, `completedAt` или эквивалент), даже если соответствующий Activity Execution имеет `duration = null`.

Это необходимо для:

- Sequence timeline;
- active/pause accounting;
- background recovery;
- historical editing.

---

# 25. Архитектурное решение: что такое Sequence относительно Activity

После дополнительного обсуждения обнаружилось естественное желание считать Sequence «обычным делом», потому что:

- она сама имеет название, комментарий, теги;
- у неё есть собственное выполнение и история;
- по ней нужна собственная Statistics;
- ей могут понадобиться собственные Custom Fields;
- она может планироваться и запускаться так же, как Activity.

При этом буквальное объединение runtime state-machine Activity и Sequence сейчас создаёт лишнюю сложность.

Поэтому принимается промежуточная архитектура.

## 25.1. Sequence — Composite Trackable Entity

**РЕШЕНО.** На продуктовом уровне Activity и Sequence — два вида trackable entity.

Они разделяют общие возможности:

- name;
- Short Comment;
- tags;
- folder/library placement;
- Template/snapshot semantics;
- planning;
- history;
- Custom Fields;
- Main Value;
- Statistics entry;
- completion/execution records.

Но Sequence дополнительно содержит:

- ordered Step snapshots;
- Repeat;
- execution transitions;
- Sequence Settings;
- runtime occurrence list;
- active/pause timeline.

Это позволяет в UI относиться к Sequence почти как к обычному делу, не заставляя реализацию делать их одной и той же runtime сущностью.

## 25.2. Sequence-level Custom Fields

**РЕШЕНО.** Sequence может иметь собственные Custom Fields независимо от полей дочерних Activity.

Пример для `Workout A`:

- Energy — Number / Rating-like field;
- Overall difficulty — Category;
- Comment — Text;
- Main Value при необходимости.

Sequence Execution сохраняет actual values этих полей отдельно от child Activity Execution.

Это понадобится Statistics и не требует менять порядок выполнения Step.

## 25.3. Sequence timing mode — первая версия

**РЕШЕНО УПРОСТИТЬ.**

В первой версии Sequence execution имеет один основной runtime mode:

`Ordered session`

У него всегда есть:

- Sequence runtime timeline;
- active/pause accounting;
- current Step;
- ordered execution semantics.

Это соответствует уже спроектированному интерфейсу и большинству текущих сценариев.

Sequence может отображаться как stopwatch-like session, но это не означает, что каждый Step обязан измерять время.

## 25.4. Outer Timer для Sequence

**ОТЛОЖЕНО.**

Идея «вся Sequence имеет Timer» потенциально полезна, но пока не вводится.

Причина: даже если все child Step — Timer, остаются неоднозначности:

- сумма target duration vs outer target;
- pause/countdown;
- early completion;
- overtime;
- Repeat;
- runtime-added Step;
- что делать, если outer Timer заканчивается посреди Step.

Модель должна позволить в будущем добавить `SequenceExecutionMode`, но первая версия фиксирует `Ordered session`.

## 25.5. No-live Sequence / checklist sequence

**ОТЛОЖЕНО К ОТДЕЛЬНОМУ ПРОЕКТИРОВАНИЮ PLAN/CHECKLIST.**

No-live Sequence в смысле:

- нет активной session;
- Step можно отмечать в течение дня/недели/месяца;
- порядок может быть нестрогим;
- объект частично выполнен длительное время;

по поведению уже существенно отличается от текущей ordered Sequence.

Это ближе к persistent checklist / grouped plan, чем к обычной live Sequence Execution.

Не нужно сейчас перегружать текущую Sequence state-machine этим режимом.

Архитектурно стоит оставить возможность будущих параметров:

- `sequenceExecutionMode = ordered_session | checklist`;
- `orderingMode = strict | free_order`;

но первая версия реализует только:

- `ordered_session`;
- `strict`.

## 25.6. Где показывать будущую checklist Sequence

**НЕ РЕШЕНО, И ЭТО НОРМАЛЬНО.**

Такая сущность может зависеть от planning precision:

- day;
- week;
- month.

Не следует сейчас принудительно показывать месячный checklist каждый день в Daily.

Этот вопрос должен решаться вместе с дальнейшей архитектурой Plan, а не внутри Activity/Sequence core.

---

# 26. No-live Step вне очереди

Идея разрешить No-live Step внутри ordered Sequence отмечать в любое время полезна, но меняет смысл строгого порядка.

Для первой версии:

**НЕ РАЗРЕШАЕМ free-order completion внутри ordered Sequence.**

No-live Step:

- становится current только по обычному runtime order;
- может быть достигнут через explicit `Go now` / jump;
- после этого Complete фиксирует выполнение.

Если пользователь хочет сделать Step раньше, используется существующий explicit jump-flow.

Так сохраняется единая понятная state-machine.

Будущий checklist/free-order mode сможет разрешить completion в любом порядке без искусственных skip semantics.

---

# 27. Runtime reorder активной Sequence

Идея drag-and-drop / drag-copy уже во время выполнения признана потенциально полезной, но **откладывается**.

Первая версия runtime editing остаётся минимальной:

- `Go now`;
- `Make next`;
- `Do again`;
- one-off `Add step`;
- Early End.

Не поддерживаются во время active run:

- произвольный drag/reorder upcoming Step;
- drag-copy;
- перенос completed Step;
- структурное изменение Repeat.

Причина: это требует отдельного согласования runtime history, skip/current semantics и UI manipulation mode.

Важно: data model не должна делать такую возможность принципиально невозможной в будущем.

---

# 28. Проверка сценариев после финальных решений

## 28.1. Завтрак

1. Мыть посуду — Stopwatch.
2. Варить кашу — Timer.
3. Поесть — Stopwatch.
4. Выпить витамины — No-live.

Поддерживается текущей ordered session без дополнительных типов.

No-live витамины:

- становятся current по порядку;
- ждут Complete;
- время ожидания по default входит в Sequence active;
- advanced setting может считать его pause.

## 28.2. Поход в магазин

1. Идти в магазин — Stopwatch.
2. Находиться в магазине — Stopwatch.
3. Возвращаться — Stopwatch.

Strict order естественен.

Если пользователь не возвращается:

- завершает current;
- Early End;
- оставшееся не создаёт фиктивный Activity Execution.

## 28.3. Тренировка

Timer + Stopwatch + Custom Fields + Repeat полностью поддерживаются.

Runtime reorder не нужен для normal happy path.

Неожиданное дополнительное упражнение создаётся через one-off Add Step.

## 28.4. «Посмотреть фильмы за месяц»

Это **не нужно насильно моделировать текущей ordered live Sequence**.

Лучше дождаться проектирования:

- Plan month;
- persistent checklist/grouped plan;
- будущего `checklist` Sequence mode.

Так мы не создаём плохой Daily UX только ради преждевременного универсального типа.

---

# 29. Статус Activity/Sequence core после v0.5

**БЛОК МОЖНО СЧИТАТЬ ДОСТАТОЧНО ЗАКРЫТЫМ.**

Дальше не нужно продолжать бесконечно искать edge cases до появления реальной причины.

Зафиксированы:

- Activity lifecycle;
- No-live semantics;
- Custom Fields / Main Value;
- Template/snapshot behavior;
- Sequence structure;
- Repeat;
- Auto-insert principles;
- manipulation editor;
- runtime execution;
- Auto-advance;
- background recovery;
- active/pause timeline;
- historical editing;
- runtime Add/Do again/Jump;
- Sequence как composite trackable entity;
- сознательно отложенные checklist/free-order/runtime-reorder extensions.

Новые противоречия могут появиться при Statistics или Plan. Тогда спецификация дополняется точечно, без пересмотра core по умолчанию.

---

# 30. Следующий проектируемый блок: Statistics

Переход к Statistics теперь полезнее, чем дальнейшее расширение Activity/Sequence.

Statistics должна отдельно ответить как минимум на вопросы:

- агрегаты Activity и Sequence;
- child Activity внутри Sequence и standalone Activity — одна или разные статистические серии;
- total/average/frequency;
- active time vs pause time;
- Stopwatch/Timer/No-live;
- numeric Main Value и остальные Number Fields;
- Category Fields;
- deleted/tombstoned historical data;
- skipped/not-started Step;
- Repeat occurrences;
- Sequence-level Custom Fields;
- временные диапазоны Day / Week / Month / Year / Custom;
- configurable dashboard;
- переход из общей Statistics в Statistics конкретной Activity/Sequence.

Именно Statistics теперь естественно определит оставшиеся правила statistical identity Custom Fields и то, как должны агрегироваться snapshot-данные.

# 31. Statistics — базовая модель v1

## 31.1. Общий принцип

Statistics считается из фактической истории Execution. History — source of truth.

Если пользователь вручную изменил historical Execution — timestamp, duration, actual Custom Field или удалил запись — статистика должна выглядеть так, как если бы она была пересчитана заново из актуальной истории. Реализация может использовать кэш, но продуктовая семантика остаётся такой.

## 31.2. Statistics Series

Template и статистическая identity не являются одним и тем же понятием.

Вводится внутренняя сущность `Statistics Series`: группа Execution, которые пользователь считает одной и той же деятельностью для статистики.

Обычно один Activity Template при создании получает одну Statistics Series.

Изменение Template по умолчанию продолжает ту же Series. Переименование, изменение default Timer, комментария, добавление Custom Field не разрывают статистику автоматически.

Advanced действие `Start new statistics from here` создаёт новую Series для будущих Execution того же Template.

## 31.3. Удаление Template

Удаление Template не удаляет его Statistics Series и не удаляет историю. Series остаётся доступной как archived statistics entry.

## 31.4. One-off Activity без Template

One-off Activity участвуют в общей Statistics.

В первой версии их можно объединить во внутренний служебный bucket `One-off activities`. Это не пользовательский Template и он не показывается в Library.

Для bucket считаются только универсальные агрегаты:
- execution count;
- total tracked duration;
- average duration только по timed one-off Execution;
- active days;
- first/last occurrence при необходимости.

Подробная per-activity Statistics для каждого one-off Execution не создаётся.

## 31.5. Sequence Statistics Series

Sequence Template имеет собственную Statistics Series.

По Sequence считаются:
- execution count;
- total active duration;
- average active duration;
- median active duration;
- shortest/longest active duration;
- total pause/idle duration;
- average pause/idle duration;
- active days;
- frequency;
- Sequence-level Custom Fields.

## 31.6. Child Activity внутри Sequence

Если Sequence Step происходит из Activity Template / Statistics Series, его Activity Execution участвует в статистике этой Activity Series.

Пример: Bench press может выполняться standalone, внутри Workout A и внутри Workout B; все эти Execution могут входить в одну per-Activity Statistics Series.

В будущем допускается context filter: All contexts / Standalone / конкретная Sequence.

## 31.7. Запрет двойного учёта времени

Global tracked-time Statistics считает только top-level Execution:
- standalone Activity Execution;
- Sequence Execution целиком.

Child Activity Execution внутри Sequence не добавляются второй раз в global tracked time.

Это критический инвариант.

## 31.8. Global Statistics — базовые метрики

Для выбранного периода первая версия должна уметь считать:
- Total tracked time;
- Total top-level executions;
- Active days;
- Average tracked time per calendar day;
- Average tracked time per active day;
- Average top-level executions per active day;
- Total pause/idle time внутри Sequence;
- One-off activity count/time.

UI может показывать не все показатели одновременно.

## 31.9. Per-Activity Statistics — базовые метрики

Для обычной Activity Series:
- Executions count;
- Total duration;
- Average duration;
- Median duration;
- Shortest duration;
- Longest duration;
- Active days;
- Average executions per selected period unit;
- Average executions per active day;
- Last performed;
- first performed — optional.

Duration-метрики считаются только по Execution, у которых duration существует.

No-live Execution участвуют в count, active days, frequency и Custom Field Statistics, но не считаются как duration=0.

## 31.10. Per-Sequence Statistics — базовые метрики

Для Sequence Series:
- Executions count;
- Total active duration;
- Average active duration;
- Median active duration;
- Shortest active duration;
- Longest active duration;
- Total pause/idle duration;
- Average pause/idle duration;
- Active days;
- Average executions per period;
- Sequence-level Custom Field Statistics.

## 31.11. Number Field Statistics

Для Number Field:
- recorded count;
- missing count;
- coverage `recorded / relevant executions`;
- total;
- average;
- median;
- minimum;
- maximum.

Missing никогда не считается `0`.

Average/median/min/max считаются только по recorded values.

## 31.12. Category Field Statistics

Для Category:
- recorded count;
- missing count;
- count по каждому category value;
- percentage по recorded values.

Missing показывается отдельно.

Удалённая из Template категория не удаляет historical values.

## 31.13. Text Field / Short Comment

Text Field и Short Comment по умолчанию не дают числовых агрегатов. Они остаются доступны в history/search/filter, но первая версия Statistics не строит по ним KPI.

## 31.14. Field identity и Statistics

Statistics конкретного Custom Field привязана к stable identity Field в snapshots.

Type/unit replacement создаёт новую Field Statistics identity. Display-name rename, если будет окончательно подтверждён, сохраняет ту же identity.

Если Field удалено из Template, оно исчезает из будущей конфигурации, но его stable identity сохраняется внутренне как archived field definition, чтобы historical snapshots/Statistics не потеряли связь.

Автоматическое объединение `Distance km` и `Distance m` не выполняется.

## 31.15. Пропуски данных

Если Field появился только после части истории, предыдущие relevant Execution считаются missing, а не zero.

Например: 50 Workout Execution, Weight записан в 30 -> coverage 30/50, missing 20.

## 31.16. History edits и Statistics

После historical correction:
- изменение timestamp может переместить Execution между периодами;
- изменение duration пересчитывает duration metrics;
- изменение actual Field value пересчитывает Field Statistics;
- удаление Execution удаляет его вклад.

Statistics не хранит отдельную «историческую истину», отличную от текущей истории.

## 31.17. Периоды

Минимально поддерживаем:
- Day;
- Week;
- Month;
- Year;
- All time;
- Custom range.

Первый UI-прототип может показывать сокращённый selector, например `Week | Month | Year | All`.

## 31.18. Statistical groups — future

Позже могут понадобиться независимые аналитические группы, не совпадающие с Folder/Tag организацией.

Примеры:
- Sport: Running, Cycling, Workout A;
- Transport: Cycling, Bus, Walking to work.

Одна Series может входить в несколько Statistical Groups.

Tags тоже many-to-many и могут участвовать в фильтрах, но в первой версии не нужно заставлять Tags полностью заменять analytics configuration.

Future вариант: `Stat Group` / saved statistics collection с явным набором Series и/или saved filter по Tags.

В первой версии отдельные Statistical Groups не реализуются.

## 31.19. Initial Statistics UI — без графиков

Первый прототип text-first.

Не нужны:
- charts;
- sparklines;
- heatmaps;
- сложные visualizations.

### Global Statistics screen

Структура:

Statistics

Period:
Week | Month | Year | All

Selected:
August 2026

Overview:
- Tracked time — 42 h 18 min
- Executions — 87
- Active days — 21
- Avg / active day — 2 h 01 min
- Pause / idle — 3 h 12 min

Activities:
- Programming — 18 h 24 min · 21× · avg 52 min
- Running — 7 h 12 min · 9× · avg 48 min
- Workout A — 6 h 40 min · 7× · avg 57 min
- One-off activities — 3 h 14 min · 12×

Нажатие по Series открывает detail screen.

### Activity Statistics detail

Running

Period selector.

Основные text metrics:
- Executions;
- Total duration;
- Average duration;
- Median duration;
- Shortest;
- Longest;
- Active days;
- Avg / week;
- Avg / active day;
- Last performed.

Custom Fields:

Distance
- 26 / 30 recorded;
- total;
- average;
- median;
- min;
- max.

Type
- Easy 62%;
- Tempo 25%;
- Intervals 13%;
- Missing 2.

### Sequence Statistics detail

Строится из того же detail pattern, но добавляет:
- Active duration;
- Pause/idle duration;
- Average pause;
- Sequence-level fields.

Child Step breakdown для первой версии не обязателен.

# 32. Следующий дизайн-шаг

Для Stitch достаточно сделать два экрана:
1. Global Statistics overview;
2. Statistics detail конкретной Activity (`Running`).

Третий Sequence detail пока необязателен.

Цель:
- проверить плотность текстовых KPI;
- проверить список Statistics Series;
- проверить drill-down;
- не отвлекаться на графики.

# 33. Library — базовый UX v1

## 33.1. Назначение

Library — каталог **переиспользуемых** сущностей:

- Activity Templates;
- Sequence Templates;
- Folder.

One-off Activity, созданные только для конкретного выполнения, в Library не появляются автоматически.

Library — не Settings screen. Из неё можно:

- открыть Template;
- быстро начать;
- запланировать;
- посмотреть Statistics;
- редактировать;
- дублировать;
- перемещать по Folder;
- управлять Tags;
- удалить.

## 33.2. Root structure

Базовый экран:

- Header `Library`;
- Create action;
- Search;
- filter `All | Activities | Sequences`;
- `Pinned`;
- `Folders`;
- `All`.

Folders не выделяются в отдельный основной tab.

## 33.3. Item interaction

Обычный tap по Activity/Sequence Template открывает editor этой сущности.

Trailing quick action запускает её немедленно.

Secondary context menu концептуально поддерживает:

- Start;
- Plan;
- Statistics;
- Edit;
- Duplicate;
- Move;
- Tags;
- Delete.

Точное меню может корректироваться при реализации.

## 33.4. Folder

Folder может содержать:

- Activity Template;
- Sequence Template;
- nested Folder.

При переходе глубже UI должен поддерживать breadcrumb/path.

UX не должен поощрять чрезмерную вложенность, но domain model пока не вводит искусственный лимит глубины.

## 33.5. Tags

Tags существуют независимо от Folder и являются many-to-many организационным механизмом.

Library должна оставаться полноценной без Tags.

Главный экран не обязан показывать отдельный большой Tags section.

## 33.6. Pinned

Pinned — быстрый пользовательский набор внутри Library и Start Activity.

Pinned не создаёт копию Template и не меняет Statistics identity.

---

# 34. Start Activity — launcher v1

## 34.1. Общая идея

`Start Activity` использует тот же underlying catalog, что Library, но оптимизирован для **немедленного действия**.

Он не должен заставлять пользователя каждый раз проходить Folder hierarchy.

## 34.2. Основные разделы

Приоритет:

1. Recent;
2. Pinned;
3. Search;
4. Browse Library;
5. New one-off activity.

Выбор Activity/Sequence из быстрого списка запускает её сразу, без дополнительного confirmation screen в normal happy path.

## 34.3. Semantics

- Stopwatch Activity → создаётся live Activity Execution;
- Timer Activity → запускается согласно Activity settings;
- No-live Activity → immediate/quick completion flow;
- Sequence → ordered Sequence Execution;
- New one-off activity → создаётся конфигурация только для текущего/немедленного Execution, если пользователь отдельно не сохраняет её как Template.

## 34.4. Single active live session

Если уже существует unfinished live Activity/Sequence, новый live start должен пройти отдельный conflict flow.

Этот dialog пока не проектируется.

No-live instant completion не обязана занимать live slot.

---

# 35. Add to Plan — базовый flow v1

## 35.1. Общий catalog

Выбор Activity/Sequence для Plan использует тот же picker/catalog, что Library/Start Activity.

После выбора открывается компактный scheduling step.

## 35.2. Context-aware default

Если Add нажата из конкретного Day:

- default = этот Day;
- exact time отсутствует по умолчанию.

Из Week context:

- default = эта Week.

Из Month context:

- default = этот Month.

Пользователь всегда может изменить precision вручную.

## 35.3. Planning precision

Поддерживаются:

- specific Day + optional exact time;
- specific Week without Day;
- specific Month without Week/Day.

Более конкретный Plan концептуально входит в более общий view, но UI не обязан физически дублировать все строки во всех уровнях.

## 35.4. Exact time

Time control показывается только если выбран specific Day.

Default:

`No exact time`.

Пользователь может задать exact clock time.

## 35.5. Selected item

Scheduling screen показывает compact summary выбранной Activity/Sequence и `Change`, возвращающий в общий picker.

## 35.6. Recurrence terminology

На текущем Stitch-прототипе появилась кнопка `Repeat`, но этот термин конфликтует с `Repeat` block внутри Sequence Editor.

Поэтому одиночная кнопка `Repeat` на Add to Plan **не считается утверждённой**.

Будущая функция повторяющегося Plan должна называться/моделироваться отдельно, например:

- product concept: `Recurrence`;
- UI: `Repeat schedule` / локализованное понятное действие.

До отдельного проектирования recurrence этот control можно вообще не показывать в первой кодовой версии.

---

# 36. Зафиксированное состояние UI-прототипирования

На текущем этапе достаточно прототипированы:

- Daily Idle;
- Daily Active;
- Plan Week;
- Statistics overview;
- Activity Statistics detail;
- Library;
- Start Activity launcher;
- Add to Plan;
- Sequence Editor normal/manipulation;
- Activity Editor;
- Sequence Execution concept.

Точные пиксельные различия Stitch не являются частью продуктовой спецификации.

Реализация должна наследовать уже выбранный visual language:

- light neutral base;
- Calibrated Precision blue accent family;
- Inter-like typography;
- moderate radii;
- subtle grouped surfaces;
- compact information density;
- Activity/Trackable rows визуально согласованы между разделами.

Дальнейшая работа переходит от UI exploration к domain model и persistence design.

# 37. Domain decisions synchronized from v0.2

## 37.1. Plan snapshot

Plan сохраняет snapshot выбранной Activity/Sequence на момент планирования. Template changes не обновляют Plan автоматически. Доступно explicit `Update from template`.

## 37.2. Plan fulfillment

Plan считается выполненным только через explicit action самого Planned item / связанный `planEntryId`. Запуск того же Template через обычный Start Activity — отдельное выполнение.

## 37.3. Overdue

Просроченные Day/Week/Month Plan не переносятся автоматически. Future UI может дать Overdue list.

## 37.4. Folder semantics

Reusable Template находится максимум в одной Folder. Folder = location, Tags = many-to-many classification. Удаление непустой Folder требует Move contents / Delete contents.

## 37.5. Tags

Tags в первой версии — только текущая Library organization metadata. Historical Execution не хранит snapshot Tag membership.

## 37.6. One-off child Sequence Step

One-off Activity Step без source Template не получает отдельную per-Activity Statistics Series и не попадает в standalone One-off bucket.

## 37.7. Recurrence

Recurrence Plan не входит в первую coded version. UI `Repeat` на Add to Plan prototype не утверждён.

## 37.8. Timezone

Historical Execution сохраняет original local time context и не пересчитывается задним числом. Exact-time Plan первой версии привязан к absolute Instant: 15:00 UTC+3 -> 14:00 UTC+2 после перелёта. Broad Day/Week/Month semantics ещё уточняются.

## 37.9. No-live planned completion

No-live Activity, выполненная из Plan, создаёт обычный No-live ActivityExecution без duration, заполняет optional fields через quick-completion, linked Plan Entry становится fulfilled.

# 38. Дополнительные решения перед persistence design

## 38.1. Overdue terminology

`Overdue` означает **просроченный план**, а не слишком долго выполняемое дело.

То есть:

- Plan Entry всё ещё `PLANNED`;
- его запланированный Day/Week/Month/exact time уже прошёл;
- пользователь его не выполнил и не отменил.

`Overdue` — derived UI state, а не отдельный основной stored status.

Слишком долго идущее Activity/Timer — другая runtime-ситуация и словом `Overdue` не обозначается.

## 38.2. Где хранится «период уже прошёл»

Отдельный флаг `periodPassed` не хранится.

Plan Entry хранит сам planned target:

- exact scheduled Instant;
- либо Day;
- либо Week;
- либо Month.

Приложение сравнивает его с текущим временем/календарём и вычисляет `overdue` динамически.

Поэтому состояние не может устареть в БД.

## 38.3. Timezone broad plans

Для Plan без exact time календарный период является floating calendar value:

- `Aug 20` остаётся `Aug 20`;
- `This week` остаётся той же календарной Week;
- `August` остаётся `August`;

при смене timezone телефона.

Exact-time Plan ведёт себя иначе: он привязан к absolute Instant и после перелёта отображается в новой local timezone.

## 38.4. Archived Templates

Обычное удаление Activity/Sequence Template — soft delete/archive.

Template:

- исчезает из основной Library;
- продолжает существовать для source links;
- может быть восстановлен;
- не удаляет history;
- не удаляет Statistics Series.

В Library/settings должен существовать secondary entry point:

`Archived templates`

где показываются soft-deleted Activity/Sequence Templates и доступны как минимум:

- Restore;
- permanent purge — future/advanced, не первая версия.

После Restore Template возвращается в обычную Library и продолжает использовать прежнюю Statistics Series.

## 38.5. Statistics Series после удаления Template

Statistics Series — не список записей и не копия Template.

Это стабильная identity статистической деятельности.

После soft-delete Template:

- Series не удаляется;
- historical Execution продолжают ссылаться на неё;
- Plan snapshot, созданный раньше, при выполнении продолжает записывать Execution в ту же Series;
- Sequence Step snapshot, происходящий из удалённого Template, тоже продолжает записывать Execution в ту же Series.

Поэтому Series может продолжать получать новые Execution даже если исходный Library Template сейчас archived/deleted.

UI может показывать metadata `Template archived`, но саму Series нельзя считать «закрытой навсегда» только из-за удаления Template.

## 38.6. Plan Entry после удаления source Template

Plan snapshot остаётся полностью выполнимым.

После удаления source Template:

- Plan остаётся в своём периоде;
- `Update from template` недоступен;
- выполнение создаётся из stored snapshot;
- Execution сохраняет прежнюю Statistics Series identity;
- доступно future/secondary `Save as new template`.

## 38.7. Template revision

Каждый mutable Activity/Sequence Template имеет monotonic `revision`.

Revision увеличивается только при изменениях, которые влияют на исполняемую/snapshot-конфигурацию.

Например:

- name/Short Comment;
- TimeTracking;
- Timer target;
- Custom Field schema/defaults;
- Sequence structure;
- Repeat;
- execution settings.

Library-only metadata не обязана увеличивать semantic revision:

- Folder;
- pin state;
- Tags.

Snapshot хранит `sourceRevision`.

Это позволяет дешёво определить:

`Template changed`

для Plan/Sequence Step без сравнения всей структуры.

## 38.8. Plan source changed marker

Если source Template существует и:

`currentTemplate.revision != planSnapshot.sourceRevision`

Plan UI может показывать ненавязчивую пометку:

`Template changed`

и действие:

`Update from template`.

Обновление всегда explicit.

## 38.9. Folder deletion

При `Move contents` nested Folder переносится **целиком как subtree** в выбранный destination.

Flattening hierarchy автоматически не выполняется.

При необходимости пользователь может вручную перемещать отдельные элементы/multi-selection.

## 38.10. Recurrence

Повторяющиеся Plan Entries не входят в первую кодовую версию.

Не показывать неоднозначную кнопку `Repeat` в первой реализации Add to Plan.

# 39. Финальные уточнения перед БД

## 39.1. Выполненные Planned items

Plan Entry, выполненный из самого Planned item, остаётся связанным с исходным planned period.

То есть выполненный Plan на прошлую дату продолжает отображаться в контексте этой даты/недели/месяца как выполненный план.

Удаление/архивирование source Template не меняет historical/planned placement.

## 39.2. Statistics после удаления Template

Execution, созданный из старого Plan snapshot или Sequence Step snapshot после archive source Template, продолжает попадать в ту же Statistics Series, которая была сохранена в snapshot.

## 39.3. Pinned order

Pinned reusable items имеют явный пользовательский порядок.

Library/Start Activity должны позволять менять порядок Pinned, например long-press + drag.

## 39.4. Recent

Recent отражает фактическое использование Template, а не только успешно завершённые Execution.

Early-ended/частично выполненный запуск тоже делает Template recent.

## 39.5. Numeric Custom Field storage — направление

Для Number Field в первой persistence-версии предпочтительно точное числовое хранение, а не binary floating point и не TEXT comparison.

При максимуме около 3 знаков после запятой хорошая базовая стратегия:

- хранить значение как signed 64-bit scaled integer;
- фиксированный storage scale = 1000;
- `12.345` хранится как `12345`;
- `12` хранится как `12000`;
- display precision хранится отдельно.

Это позволяет SQL корректно делать numeric:
- sort;
- compare;
- min/max;
- sum;
- range filters.

Average/median можно считать из integer source values в application/domain layer без строковой семантики.

Окончательный SQL/Room mapping фиксируется в database schema.

## 39.6. Открыто: Template name vs конкретное Activity name

Нужно окончательно определить, имеет ли конкретный Activity snapshot собственное редактируемое имя независимо от source Template.

Предлагаемая модель:

Template:
`Walking`

Execution / Plan / Sequence Step snapshot:
`Going to the store`

Short Comment:
`For bread and milk`

То есть Template name — default/source name, а каждый snapshot копирует его и может локально изменить.

Это лучше соответствует уже принятому snapshot principle и реальным сценариям.

Требует подтверждения пользователя.

## 39.7. Открыто: Cancelled Plan

`CANCELLED` означает:

пользователь явно решил, что этот Plan больше не нужно выполнять, но хочет сохранить факт, что он когда-то был запланирован.

Это отличается от:

- `FULFILLED` — выполнен;
- `PLANNED + period passed` = overdue;
- delete/purge — запись вообще удалена.

Cancelled полезен для будущей статистики планирования (`planned / fulfilled / cancelled / missed`), но его можно убрать из первой версии, если такая история отмен не нужна.

Требует подтверждения пользователя.

# 40. Финальные решения перед persistence design

## 40.1. Template name / Activity snapshot name / Short Comment

Подтверждена трёхуровневая модель:

1. **Template name** — reusable default name.
2. **Activity snapshot name** — локально редактируемое имя конкретного Plan / Sequence Step / Execution.
3. **Short Comment** — дополнительная короткая заметка.

Пример:

- Template: `Walking`
- конкретное дело: `Going to the store`
- Short Comment: `For bread and milk`

При создании snapshot имя по умолчанию копируется из Template, но дальше может редактироваться независимо.

Изменение snapshot name:

- не меняет source Template;
- не меняет Statistics Series;
- не переименовывает другие snapshot;
- считается локальным изменением snapshot.

`Update from template` может заменить snapshot name текущим Template name.

## 40.2. Cancelled Plan

Stored Plan Entry status первой версии:

- `PLANNED`
- `FULFILLED`
- `CANCELLED`

`CANCELLED` означает: пользователь явно решил, что этот Plan больше не нужно выполнять, но хочет сохранить сам факт прежнего планирования.

Cancelled Plan:

- не отображается в обычных текущих Plan views;
- не считается overdue;
- не переносится автоматически;
- хранится отдельно;
- может быть восстановлен в `PLANNED`.

## 40.3. Archived / Cancelled Plans UI

Должно существовать secondary management place, аналогичное `Archived templates`.

Рабочее название:

`Cancelled plans`

или общий архив Plan.

Минимальные действия:

- View;
- Restore;
- Delete permanently — future/advanced.

После Restore:

- status снова `PLANNED`;
- исходный planned period сохраняется;
- если период уже прошёл, restored Plan становится `overdue` как derived state.

## 40.4. Overdue

`Overdue` остаётся derived state только для `PLANNED` Plan Entry.

Cancelled/Fulfilled Plan никогда не показываются как overdue.

# 41. Уточнения после внешнего review

Этот раздел устраняет двусмысленности, обнаруженные при внешнем review. Если более ранняя формулировка документа может читаться иначе, правила этого раздела имеют приоритет.

## 41.1. Одна live session — ограничение первой версии, не фундамент продукта

Первая кодовая версия поддерживает максимум одну незавершённую live session.

Это **сознательное UX/implementation-ограничение v1**, а не долгосрочное правило LifeTracing.

Будущая версия должна уметь поддерживать параллельные live Activity/Sequence, потому что реальные дела могут выполняться одновременно.

Поэтому:

- domain IDs/Execution model не должны предполагать, что live Execution физически может существовать только один;
- singleton active-session pointer допустим только как v1 policy;
- future migration к нескольким active sessions должна быть возможна без изменения исторических Execution.

## 41.2. Required Custom Fields

Все Custom Fields в v1 остаются optional — это подтверждённое продуктовое упрощение.

Причина:
- Timer/Auto-advance/background flow не должны неожиданно блокироваться обязательным вводом;
- No-live quick completion должен оставаться быстрым.

Это не означает, что Required Fields отвергаются навсегда.

Future extension может добавить:
- Optional;
- Recommended;
- Required.

Архитектура значений должна поддерживать missing как отдельное состояние и не считать missing = 0.

## 41.3. No-live Activity внутри Sequence

Здесь нет требования, чтобы duration родителя была суммой duration дочерних Activity.

Уровни различаются:

- No-live Activity Execution: индивидуальной duration **нет**;
- Sequence Execution: имеет собственную timeline и классифицирует время session.

Если No-live Step является current 20 секунд, Sequence по default может считать эти 20 секунд своим active time, хотя дочерняя Activity duration остаётся NULL.

Advanced Sequence setting может вместо этого классифицировать этот interval как pause.

То есть Sequence active time — характеристика parent session, а не сумма child durations.

## 41.4. Repeat terminology

В доменной/кодовой терминологии:

`SequenceRepeatBlock`

означает повторение группы Step внутри Sequence.

UI Sequence Editor может кратко показывать:

`Repeat ×3`.

Будущее повторяющееся планирование — отдельная сущность:

`RecurrenceRule`.

В Add to Plan v1 control `Repeat` не показывается.

Таким образом одно слово не используется для двух разных доменных механизмов.

## 41.5. Broad Plan и timezone

Day/Week/Month Plan без exact time являются **floating calendar values**.

При смене timezone:
- `Aug 20` остаётся `Aug 20`;
- week остаётся той же календарной week;
- `August` остаётся `August`.

Они показываются, когда текущий календарь устройства находится в соответствующем literal period.

Это значит, что при перелётах календарный день устройства может повториться или быть короче/длиннее — v1 принимает это обычное следствие floating calendar semantics.

Exact-time Plan отличается:
- хранит absolute Instant;
- отображается в текущей timezone устройства;
- поэтому local date/time может сдвинуться после перелёта.

## 41.6. Historical overlap

Union пересекающихся active intervals **не должен вычисляться сложным Room/SQL query**.

Domain layer получает intervals одного Sequence Execution, сортирует/объединяет их и рассчитывает:
- active duration;
- pause duration;
- wall duration.

Persistence может хранить derived per-execution duration cache для быстрых Statistics, но interval timeline остаётся source of truth.

## 41.7. Snapshot lifecycle

Persistence snapshots считаются immutable rows.

«Редактирование snapshot» на продукт/domain уровне означает:
1. создать replacement snapshot;
2. атомарно переключить owner/reference;
3. удалить старый snapshot, если на него больше никто не ссылается.

Поэтому обычное редактирование не должно бесконтрольно копить orphan snapshots.

DB specification определяет transactional pruning и safety GC отдельно.

## 41.8. Recent / Room invalidation

`lastUsedAt` относится к launcher/user-state metadata, а не к semantic Template.

Он не должен храниться в основной Template row в окончательной persistence-схеме.

Pinned/Recent state выносится в отдельные user-state tables, чтобы частые обновления Recent не инвалидировали обычные observable queries основной таблицы Template без необходимости.

## 41.9. Custom Field rename — ОТКРЫТ ОДИН ВОПРОС

Внешний review справедливо указал, что запрет любого rename создаёт статистические «мертвые» поля даже при исправлении опечатки.

Предлагаемое уточнение:

- `fieldId` = statistical identity;
- **display name можно переименовывать**, сохраняя fieldId и непрерывную Statistics;
- `type` менять нельзя;
- `unit` в v1 менять нельзя;
- если изменился смысл Field, пользователь explicit удаляет старый и создаёт новый.

Пример:
`Distnace` -> `Distance` — то же Field и та же Statistics.

`Distance · km` -> `Calories · kcal` — новое Field.

Нужно подтверждение пользователя перед окончательной фиксацией.

Category option label рекомендуется трактовать аналогично:
- optionId сохраняется при простом rename label;
- historical snapshots всё равно хранят старый label своего момента времени.

## 41.10. Statistics Series label

Template rename не создаёт новую Statistics Series.

Текущая Series по default получает новый display label Template, но:
- historical snapshot names не меняются;
- локально переименованный Activity snapshot не переименовывает Series;
- после удаления Template Series сохраняет последний display label.

# 42. Custom Field display-name semantics — финальное решение

## 42.1. Stable identity

Custom Field имеет stable `fieldId`.

Display name не является identity.

Поэтому простое переименование:

`Distnace` -> `Distance`

или:

`Количество кружек воды` -> `Стаканы`

может сохранять тот же `fieldId` и непрерывную Statistics, если пользователь считает смысл Field тем же.

## 42.2. Global rename by default

Если Field происходит из reusable Template и конкретный snapshot **не имеет локального override имени этого Field**, то текущий display name source Field используется:

- в Template editor;
- в Statistics;
- в Plan;
- в Sequence Step;
- в historical Execution.

То есть исправление опечатки действительно исправляется «везде».

Historical numeric/category/text values при этом не меняются.

Меняется только отображаемая подпись Field.

## 42.3. Local Field-name override

Concrete snapshot может иметь локальное имя Field.

Например source Template:

`Walking`
- Field: `Distance`

конкретный Sequence Step / one-off config:
- local Field label: `Distance to store`

В этом случае последующее переименование source Field не перезаписывает local override.

## 42.4. Изменение другого свойства не замораживает Field name

Очень важно:

локальное изменение имени Activity, Short Comment, Timer target или другого Field **не отсоединяет автоматически все Field labels от Template**.

Наследование имени решается отдельно для каждого Field.

Поэтому:

Template:
`Walking`

Execution snapshot:
`Going to the store`
Short Comment:
`For bread and milk`

Field:
`Distnace` -> позже Template исправлен на `Distance`

Если конкретно Field name локально не переименовывали, history этого Execution тоже покажет `Distance`.

## 42.5. True one-off Field

Field без `sourceFieldId` не связан с reusable Field identity.

Его display name полностью хранится в snapshot.

Будущие rename Template Field на него не влияют.

## 42.6. Fallback historical label

Snapshot всё равно хранит `nameAtCreation`.

Он нужен:
- для полного self-contained history;
- для export;
- как fallback после future permanent purge source metadata.

Но пока source Field существует/archived и local override отсутствует, UI использует current source display name.

## 42.7. Category option rename

Та же модель применяется к Category option:

- stable `optionId`;
- current source label используется глобально;
- optional local override/fallback snapshot label;
- rename label не разбивает Statistics category identity.

## 42.8. Что создаёт новый Field

Новый `fieldId` нужен при semantic replacement, например:
- другой `type`;
- другой `unit` в v1;
- пользователь explicit создаёт новое Field вместо старого.

Простая смена display name новый Field не создаёт.


# 43. Field rename precedence — контрольный пример

Подтверждена per-Field inheritance semantics.

Исходно Template содержит:

- Field A = `Amount`
- Field B = `Difficulty`

Далее:

1. из Template создаётся конкретное дело/Plan/Sequence Step;
2. **Field A локально переименовывается** в `Glasses`;
3. Field B локально не меняется;
4. дело выполняется или остаётся в Plan;
5. позже в source Template:
   - Field B переименовывается в `Effort`;
   - Field A переименовывается в `Cups`.

Результат в созданном ранее snapshot/history:

- Field A остаётся `Glasses`, потому что у него есть `localNameOverride`;
- Field B становится `Effort`, потому что он продолжает наследовать source display name.

Это работает одинаково для:
- уже выполненного Execution;
- Planned item;
- Sequence Step;
- другого source-linked snapshot.

Локальный override одного Field не отсоединяет другие Fields и не превращает весь Activity snapshot в one-off.

True one-off Field — только Field без `sourceFieldId`.

# 44. Midnight ownership — v1 final rule

Для выполнения, пересекающего 00:00, в v1 используется один primary day.

## Timed Activity

Primary day = original local date `startedAt`.

Пример:

`23:50 Aug 20 -> 00:30 Aug 21`

Execution показывается в Daily/History за **Aug 20**.

Все 40 минут duration в Statistics v1 также относятся к Aug 20.

## Sequence

Primary day = original local date `Sequence.startedAt`.

Весь root Sequence Execution в v1 относится к этому primary day.

## No-live Activity

Primary day = original local date `completedAt`.

## Future

Source timestamps/intervals сохраняются полностью, поэтому позже Statistics может распределять duration по обе стороны полуночи без изменения historical source data.

# 45. Manual / backdated Activity creation

Ручное создание historical Execution является обязательной возможностью v1.

Это отличается от редактирования уже существующей history: пользователь может создать Execution, которого раньше вообще не было.

## 45.1. Three common start modes

Для обычной Activity должны поддерживаться три семантики:

### Start now

Default happy path.

```text
start = now
end = empty
```

Создаётся live Execution.

### Started earlier, continue tracking

Пользователь уже выполняет дело, но открыл приложение позже.

```text
start = user-selected past date/time
end = empty
```

Создаётся live Execution с фактическим `startedAt` из прошлого.

Stopwatch elapsed считается от этого `startedAt`, а не от момента нажатия кнопки в приложении.

### Add completed activity to history

Пользователь забыл трекать дело и позже вносит его вручную.

```text
start = user-selected past date/time
end = user-selected past date/time
```

Создаётся сразу `COMPLETED` Execution.

Live slot не занимает.

## 45.2. Start Activity UX

Обычный quick Start должен оставаться однотаповым и использовать `start = now`.

Рядом должен существовать secondary `Start options` / time-edit path, где можно изменить:

- start date/time;
- optional end date/time.

Точный visual interaction можно выбрать во время реализации.

Правило:

- end empty -> live Execution;
- end filled -> completed historical Execution.

Future time запрещено для historical completion/start.

## 45.3. Cross-midnight manual entry

Start/end являются полными date-time values, а не двумя независимыми clock-time strings.

Пользователь может сохранить, например:

```text
Aug 20 23:50
Aug 21 00:30
```

UI не должен отвергать `end clock < start clock`, если end date следующий день.

Если compact picker автоматически предполагает `next day`, он обязан явно показать результирующую дату до Save.

Primary day остаётся Aug 20 по правилу выше.

## 45.4. Overlap

Manual historical Activity может пересекаться с другой history.

Существующее правило сохраняется:

- warning;
- сохранение не блокируется.

## 45.5. Timer manual historical entry

При создании **уже завершённого** historical Timer Activity приложение не симулирует задним числом runtime auto-finish.

Template target сохраняется в snapshot как target, но actual execution interval = введённые пользователем start/end.

Например target 10 min, historical interval 14 min -> actual duration 14 min.

Это historical fact correction, а не replay runtime state machine.

## 45.6. No-live historical entry

Для No-live Activity можно выбрать past completion timestamp.

У неё по-прежнему:

- `startedAt = null`;
- duration = null;
- primary day = local date completion timestamp.

## 45.7. Plan linkage

Если historical Activity создаётся через обычный `Start Activity`, совпадающий Planned item автоматически не fulfilled.

Если тот же manual time-entry flow запускается **из конкретного Planned item**, Execution получает `planEntryId`, а после completed save Plan становится `FULFILLED`.

## 45.8. Manual-entry timezone

Вводимый local date/time по default интерпретируется в текущем ZoneId устройства.

Persistence хранит event ZoneId вместе с Instant.

Data model допускает future/advanced ручной выбор ZoneId для случая, когда пользователь вносит событие после перелёта.

## 45.9. Open Sequence question

Manual/backdated semantics для Sequence сложнее, потому что root start/end недостаточно для восстановления child occurrences.

Перед окончательным v1 freeze нужно отдельно решить:
- поддерживаем ли manual historical Sequence вообще;
- поддерживаем ли `Sequence started earlier, continue now`.

Рекомендация: v1 гарантированно поддерживает этот flow для standalone Activity; Sequence backfill не делать без отдельной явной модели.

# 46. Implementation freeze notes

Три замечания финального review не меняют продуктовую модель, но фиксируются как обязательные implementation guardrails.

## 46.1. Editor changes are drafts until semantic commit

Редактирование Template / Sequence Step / Plan configuration не должно создавать новый persistence snapshot на каждое изменение TextField, debounce или focus change.

UI может держать draft state:
- в ViewModel/SavedStateHandle;
- либо в отдельном draft-механизме, если позднее понадобится autosave.

Replacement snapshot создаётся только при **semantic commit**:
- Done / Save / Apply;
- explicit `Update from template`;
- другой завершённый domain command.

Если UX позже получит autosave, autosave не должен означать replacement production snapshot после каждого символа.

## 46.2. Field display inheritance is repository/domain responsibility

Current inherited Field/Category labels не требуют одного большого SQL JOIN для каждого экрана.

UI получает effective display metadata через repository/domain mapping:
- snapshot-local override;
- current source metadata;
- creation fallback.

Statistics identity по-прежнему определяется IDs, а не текстовыми labels.

## 46.3. Calendar indicator semantics

Day/calendar dots показывают **day-scoped Plan**:
- floating Day Plan;
- exact-time Plan, чья текущая local date попадает в этот день.

Week-level и Month-level Plan не размножаются искусственно в dots каждого дочернего дня.

Они отображаются в своих `This week` / month-level sections.

При смене device ZoneId local day exact-time Plan может измениться, и calendar indicators должны пересчитаться.
