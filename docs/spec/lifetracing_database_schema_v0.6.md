# LifeTracing — Database Schema v0.6

Дата: 2026-08-15  
Статус: **v1 persistence schema frozen for first implementation; implementation guardrails added after final review**

---

# 0. Цели

Схема должна:

- сохранять историю независимо от дальнейших изменений Template;
- поддерживать snapshot semantics;
- восстанавливать live execution после process death;
- корректно хранить Sequence / Repeat / RuntimeOccurrence;
- не задваивать global statistics;
- поддерживать Day / Week / Month Plan;
- переживать soft-delete Template;
- позволять архив/restore;
- не блокировать будущие миграции к parallel execution, recurrence, checklist sequence;
- оставаться удобной для Room и индексов SQLite.

Это near-final v1 schema: дальнейшие изменения должны быть локальными и не менять уже закрытую domain semantics.

---

# 1. Общая стратегия

## 1.1. IDs

Рекомендуется использовать UUID-подобные string IDs (`TEXT`) либо бинарные UUID, если позже будет удобный adapter.

Для первой Android/Room реализации проще:

```text
id TEXT PRIMARY KEY
```

Причины:

- identity не зависит от локального autoincrement;
- удобно создавать objects до insert;
- проще миграции/импорт/экспорт;
- future sync не требует смены identity.

## 1.2. Timestamps

Абсолютное время хранить как epoch milliseconds:

```text
INTEGER  // Long
```

Дополнительно для historical execution хранить:

- original ZoneId `TEXT`;
- при необходимости original UTC offset minutes `INTEGER`.

## 1.3. Boolean

SQLite:

```text
INTEGER 0/1
```

Room map -> Boolean.

## 1.4. Enums

Хранить как stable `TEXT` code, не ordinal integer.

Пример:

```text
STOPWATCH
TIMER
NO_LIVE_TRACKING
```

Так enum reorder в Kotlin не ломает данные.

## 1.5. Numeric Custom Fields

Первая версия: **fixed scale = 1000**.

Пользовательское число:

```text
12.345 -> 12345
12.3   -> 12300
12     -> 12000
```

Storage:

```text
INTEGER  // Long
```

Плюсы:

- exact до 3 знаков после запятой;
- SQL numeric sort/filter/min/max/sum;
- нет lexical TEXT comparison;
- нет binary Double artifacts.

`displayPrecision` хранится в schema Field отдельно.

Среднее:

```text
sumScaled / count
```

с округлением в domain/application layer.

---

# 2. Template tables

## 2.1. activity_templates

```text
activity_templates
------------------
id TEXT PRIMARY KEY
name TEXT NOT NULL
short_comment TEXT NULL

time_tracking_mode TEXT NOT NULL
timer_target_ms INTEGER NULL

statistics_series_id TEXT NOT NULL

revision INTEGER NOT NULL DEFAULT 1

created_at_ms INTEGER NOT NULL
updated_at_ms INTEGER NOT NULL
deleted_at_ms INTEGER NULL

folder_id TEXT NULL
```

### Notes

`time_tracking_mode`:

```text
STOPWATCH
TIMER
NO_LIVE_TRACKING
```

`timer_target_ms`:

- required when mode TIMER;
- null otherwise.

`deleted_at_ms != null` = archived Template.

Pinned/Recent metadata хранится отдельно в user-state table, а не в semantic Template row.

### Indexes

```text
INDEX activity_templates_deleted ON deleted_at_ms
INDEX activity_templates_folder ON folder_id
INDEX activity_templates_series ON statistics_series_id
```

---

## 2.2. sequence_templates

```text
sequence_templates
------------------
id TEXT PRIMARY KEY
name TEXT NOT NULL
short_comment TEXT NULL

statistics_series_id TEXT NOT NULL
revision INTEGER NOT NULL DEFAULT 1

created_at_ms INTEGER NOT NULL
updated_at_ms INTEGER NOT NULL
deleted_at_ms INTEGER NULL

folder_id TEXT NULL

no_live_time_accounting TEXT NOT NULL DEFAULT 'ACTIVE'
```

`no_live_time_accounting`:

```text
ACTIVE
PAUSE
```

Advanced execution settings лучше вынести в отдельную таблицу/JSON? Для v0.1 предлагается отдельная typed table ниже.

---

# 2A. Template user-state / launcher metadata

Часто меняющиеся UI metadata не хранятся в semantic Template rows.

Это важно для observable Room queries: изменение Recent не должно заставлять обычный observer основной Template table повторно выполнять запрос без необходимости.

## 2A.1. activity_template_user_state

```text
activity_template_user_state
----------------------------
activity_template_id TEXT PRIMARY KEY
pinned_rank INTEGER NULL
last_used_at_ms INTEGER NULL
```

Indexes:

```text
INDEX activity_user_state_pinned ON pinned_rank
INDEX activity_user_state_recent ON last_used_at_ms
```

## 2A.2. sequence_template_user_state

```text
sequence_template_user_state
----------------------------
sequence_template_id TEXT PRIMARY KEY
pinned_rank INTEGER NULL
last_used_at_ms INTEGER NULL
```

Indexes analogous.

### Query policy

- обычный `All activities` Flow может наблюдать semantic template table без join с Recent metadata;
- `Pinned` section наблюдает Template + user-state;
- `Recent` launcher query наблюдает user-state.

`last_used_at_ms` остаётся denormalized convenience metadata, не history source of truth.

# 3. Advanced settings tables

Чтобы не раздувать Template rows и не сериализовать critical behavior в opaque JSON, хранить settings отдельно.

## 3.1. activity_template_settings

```text
activity_template_settings
--------------------------
activity_template_id TEXT PRIMARY KEY

show_seconds INTEGER NOT NULL DEFAULT 1
start_countdown_ms INTEGER NOT NULL DEFAULT 0

timer_zero_behavior TEXT NOT NULL DEFAULT 'FINISH'
timer_end_sound INTEGER NOT NULL DEFAULT 1
timer_end_vibration INTEGER NOT NULL DEFAULT 1

keep_screen_awake INTEGER NOT NULL DEFAULT 0
confirm_manual_finish INTEGER NOT NULL DEFAULT 0
```

`timer_zero_behavior`:

```text
FINISH
OVERTIME
```

Параметры можно расширять миграциями.

## 3.2. sequence_template_settings

```text
sequence_template_settings
--------------------------
sequence_template_id TEXT PRIMARY KEY

auto_advance INTEGER NOT NULL DEFAULT 1

sequence_start_countdown_ms INTEGER NOT NULL DEFAULT 0
before_each_step_countdown_ms INTEGER NOT NULL DEFAULT 0

transition_sound INTEGER NOT NULL DEFAULT 1
transition_vibration INTEGER NOT NULL DEFAULT 1

keep_screen_awake INTEGER NOT NULL DEFAULT 0

confirm_jump INTEGER NOT NULL DEFAULT 1
confirm_early_end INTEGER NOT NULL DEFAULT 1
```

---

# 4. Custom Field definitions for templates

v0.2 **отказывается от generic `owner_type/owner_id` table**.

Причина:
- такой подход ослабляет реальные FK;
- в v0.1 он конфликтовал с отдельными snapshot field tables;
- для near-final Room schema лучше явные typed ownership tables.

Общую Kotlin-модель Field можно переиспользовать на domain layer; SQL-таблицы остаются typed.

## 4.1. activity_template_fields

```text
activity_template_fields
------------------------
id TEXT PRIMARY KEY
activity_template_id TEXT NOT NULL

position INTEGER NOT NULL

name TEXT NOT NULL
field_type TEXT NOT NULL

unit TEXT NULL
display_precision INTEGER NULL

default_number_scaled INTEGER NULL
default_category_option_id TEXT NULL
default_text TEXT NULL

is_main_value INTEGER NOT NULL DEFAULT 0

created_at_ms INTEGER NOT NULL
updated_at_ms INTEGER NOT NULL
deleted_at_ms INTEGER NULL
```

## 4.2. activity_template_category_options

```text
activity_template_category_options
----------------------------------
id TEXT PRIMARY KEY
activity_template_field_id TEXT NOT NULL

position INTEGER NOT NULL
label TEXT NOT NULL
is_archived INTEGER NOT NULL DEFAULT 0
```

`is_archived` позволяет перестать предлагать option в новых Execution, не ломая его identity.

## 4.3. sequence_template_fields

Структура аналогична `activity_template_fields`, включая `deleted_at_ms`, но owner FK:

```text
sequence_template_id TEXT NOT NULL
```

UI delete Field = soft archive (`deleted_at_ms = now`), а не hard-delete identity row.

## 4.4. sequence_template_category_options

Аналогична Activity table.

## 4.5. Main Value invariant

У Template максимум один Main Value.

Partial unique indexes:

```sql
CREATE UNIQUE INDEX idx_activity_template_one_main_field
ON activity_template_fields(activity_template_id)
WHERE is_main_value = 1 AND deleted_at_ms IS NULL;

CREATE UNIQUE INDEX idx_sequence_template_one_main_field
ON sequence_template_fields(sequence_template_id)
WHERE is_main_value = 1 AND deleted_at_ms IS NULL;
```

## 4.6. Field removal

Template Field is soft-archived:

```text
deleted_at_ms = now
```

New Template snapshots exclude archived Fields.

Historical snapshots keep `source_field_id`, so old per-field Statistics remain groupable.

Hard-delete Field identity only during future full purge after proving there are no historical/source references.

## 4.7. Field rename policy

Database identity — `field.id`, а не `name`.

Схема технически позволяет менять display name без смены id.

Финальное правило:
- display-name rename сохраняет id/statistics;
- local snapshot override имеет приоритет над source rename;
- type/unit semantic replacement создаёт новый Field id.

Statistics никогда не группируется по display text.

# 5. Folder / Tag

## 5.1. folders

```text
folders
-------
id TEXT PRIMARY KEY
name TEXT NOT NULL
parent_folder_id TEXT NULL

created_at_ms INTEGER NOT NULL
updated_at_ms INTEGER NOT NULL
```

### Constraint

Cycle нельзя надежно выразить обычным FK — проверять domain/service layer.

### Index

```text
INDEX folders_parent ON parent_folder_id
```

---

## 5.2. tags

```text
tags
----
id TEXT PRIMARY KEY
name TEXT NOT NULL
created_at_ms INTEGER NOT NULL
updated_at_ms INTEGER NOT NULL
```

Имя можно сделать UNIQUE case-insensitive позже; пока лучше не цементировать policy.

---

## 5.3. activity_template_tags

```text
activity_template_tags
----------------------
activity_template_id TEXT NOT NULL
tag_id TEXT NOT NULL

PRIMARY KEY(activity_template_id, tag_id)
```

## 5.4. sequence_template_tags

```text
sequence_template_tags
----------------------
sequence_template_id TEXT NOT NULL
tag_id TEXT NOT NULL

PRIMARY KEY(sequence_template_id, tag_id)
```

Tags не snapshot-ятся в history.

---

# 6. Statistics Series

## 6.1. statistics_series

```text
statistics_series
-----------------
id TEXT PRIMARY KEY

kind TEXT NOT NULL
display_name TEXT NOT NULL

created_at_ms INTEGER NOT NULL

archived_at_ms INTEGER NULL
```

`kind`:

```text
ACTIVITY
SEQUENCE
ONE_OFF_BUCKET
```

### Important

`archived_at_ms` не должен автоматически следовать Template deleted_at.

Series может продолжать получать Execution из старых Plan/Sequence snapshots.

Возможно, в v0.1 `archived_at_ms` вообще не нужен и UI выводит состояние по наличию active sources. Можно оставить nullable для future.

---

# 7. Snapshot strategy

Есть два основных пути:

1. сериализовать весь config snapshot как JSON;
2. нормализовать snapshot в таблицы.

Для LifeTracing v0.1 рекомендуется **гибридная нормализация**:

- core snapshot fields — typed columns;
- Custom Field schema/value — отдельные rows;
- advanced settings — snapshot settings table;
- не хранить critical domain state только в JSON.

Причина: snapshots активно участвуют в:
- Plan;
- Sequence Step;
- Execution;
- Statistics;
- migration/update-from-template.

Opaque JSON затруднит query/migration.

---

# 8. Activity config snapshots

## 8.1. activity_snapshots

```text
activity_snapshots
------------------
id TEXT PRIMARY KEY

name TEXT NOT NULL
short_comment TEXT NULL

time_tracking_mode TEXT NOT NULL
timer_target_ms INTEGER NULL

source_template_id TEXT NULL
source_revision INTEGER NULL

statistics_series_id TEXT NULL

locally_modified INTEGER NOT NULL DEFAULT 0

created_at_ms INTEGER NOT NULL
```

Snapshot самодостаточен.

`source_template_id` soft reference:
- FK может быть `ON DELETE SET NULL`, но при soft delete row вообще остаётся;
- hard purge later должен уметь оставить snapshot.

`statistics_series_id` сохраняется даже после source deletion.

---

## 8.2. activity_snapshot_settings

```text
activity_snapshot_settings
--------------------------
snapshot_id TEXT PRIMARY KEY

show_seconds INTEGER NOT NULL
start_countdown_ms INTEGER NOT NULL
timer_zero_behavior TEXT NOT NULL
timer_end_sound INTEGER NOT NULL
timer_end_vibration INTEGER NOT NULL
keep_screen_awake INTEGER NOT NULL
confirm_manual_finish INTEGER NOT NULL
```

---

## 8.3. activity_snapshot_fields

```text
activity_snapshot_fields
------------------------
id TEXT PRIMARY KEY

snapshot_id TEXT NOT NULL
source_field_id TEXT NULL

position INTEGER NOT NULL

name_at_creation TEXT NOT NULL
local_name_override TEXT NULL
field_type TEXT NOT NULL
unit TEXT NULL
display_precision INTEGER NULL

default_number_scaled INTEGER NULL
default_category_option_id TEXT NULL
default_text TEXT NULL

is_main_value INTEGER NOT NULL DEFAULT 0
```

Категории snapshot лучше хранить labels в отдельной таблице:

## 8.4. activity_snapshot_category_options

```text
activity_snapshot_category_options
----------------------------------
id TEXT PRIMARY KEY
snapshot_field_id TEXT NOT NULL
source_option_id TEXT NULL
position INTEGER NOT NULL
label_at_creation TEXT NOT NULL
local_label_override TEXT NULL
```

Почему label snapshot, а не только source option id:
история должна читаться после изменения/удаления Template option.

**Snapshot rows immutable.** Любое semantic edit создаёт replacement snapshot и repoint owner/reference.

---

# 9. Sequence structure snapshots

Sequence Template structure itself mutable, поэтому для actual SequenceTemplate можно иметь node tables.

## 9.1. sequence_nodes

```text
sequence_nodes
--------------
id TEXT PRIMARY KEY
sequence_template_id TEXT NOT NULL

node_type TEXT NOT NULL

parent_repeat_node_id TEXT NULL
position INTEGER NOT NULL

activity_snapshot_id TEXT NULL
repeat_count INTEGER NULL
```

`node_type`:

```text
STEP
REPEAT
```

Rules:

STEP:
- activity_snapshot_id NOT NULL
- repeat_count NULL

REPEAT:
- activity_snapshot_id NULL
- repeat_count > 0
- parent_repeat_node_id must be NULL (nested Repeat prohibited)

Child STEP inside Repeat:
- parent_repeat_node_id = repeat node id

Top-level STEP:
- parent_repeat_node_id = null

`position` interpreted within sibling container.

### Indexes

```text
INDEX sequence_nodes_sequence ON sequence_template_id
INDEX sequence_nodes_parent_pos ON sequence_template_id, parent_repeat_node_id, position
```

---

# 10. Sequence-level Custom Fields

Sequence Template fields уже определены в:

- `sequence_template_fields`;
- `sequence_template_category_options`.

Sequence snapshot fields определяются отдельно в разделе frozen snapshots.

SequenceExecution actual values хранятся в `sequence_execution_field_values`.

Generic owner-typed Field table больше не используется.

# 11. Plan Entry

## 11.1. plan_entries

```text
plan_entries
------------
id TEXT PRIMARY KEY

trackable_kind TEXT NOT NULL

source_activity_template_id TEXT NULL
source_sequence_template_id TEXT NULL

source_revision INTEGER NULL

activity_snapshot_id TEXT NULL
sequence_plan_snapshot_id TEXT NULL

precision TEXT NOT NULL

planned_day TEXT NULL
planned_week_start TEXT NULL
planned_month TEXT NULL

scheduled_instant_ms INTEGER NULL
creation_zone_id TEXT NULL

status TEXT NOT NULL

fulfilled_activity_execution_id TEXT NULL
fulfilled_sequence_execution_id TEXT NULL

created_at_ms INTEGER NOT NULL
updated_at_ms INTEGER NOT NULL
cancelled_at_ms INTEGER NULL
fulfilled_at_ms INTEGER NULL
```

`trackable_kind`:

```text
ACTIVITY
SEQUENCE
```

`precision`:

```text
DAY
WEEK
MONTH
```

Exact-time day:

- precision DAY;
- `scheduled_instant_ms` NOT NULL;
- `planned_day` не является authoritative source и рекомендуется NULL;
- calendar placement выводится из `scheduled_instant_ms` в **текущей timezone устройства**;
- original creation zone сохраняется только как audit/context.

Day without exact time:

- precision DAY;
- `planned_day` NOT NULL;
- scheduled_instant_ms NULL;
- planned_day является floating literal LocalDate и не конвертируется через timezone.

Week:
- `planned_week_start` NOT NULL;
- v1: week = Monday..Sunday;
- value floating и не конвертируется через timezone.

Month:
- `planned_month` as `YYYY-MM`;
- value floating и не конвертируется через timezone.

### Status

```text
PLANNED
FULFILLED
CANCELLED
```

Overdue derived query, не stored.

### State/timestamp consistency

Allowed transitions:

```text
PLANNED -> FULFILLED
PLANNED -> CANCELLED
CANCELLED -> PLANNED   // restore
```

v1 не делает `FULFILLED -> PLANNED` автоматически.

Transaction rules:

#### cancel
- status = CANCELLED
- cancelled_at_ms = now
- fulfilled_at_ms = NULL

#### restore
- status = PLANNED
- cancelled_at_ms = NULL
- fulfilled_at_ms = NULL

#### fulfill
- status = FULFILLED
- fulfilled_at_ms = execution completion timestamp
- cancelled_at_ms = NULL

Если linked historical Execution затем soft-deleted, Plan остаётся FULFILLED.

Recommended CHECK constraints:

```text
PLANNED   => cancelled_at_ms IS NULL AND fulfilled_at_ms IS NULL
CANCELLED => cancelled_at_ms IS NOT NULL AND fulfilled_at_ms IS NULL
FULFILLED => fulfilled_at_ms IS NOT NULL AND cancelled_at_ms IS NULL
```

### Cancelled query

```text
WHERE status = 'CANCELLED'
```

для Cancelled Plans screen.

---

# 12. Sequence Plan snapshot

Plan Sequence должен быть самодостаточным, даже если Template изменён/удалён.

Самый безопасный v0.1 путь — создать frozen sequence snapshot container.

## 12.1. sequence_snapshots

```text
sequence_snapshots
------------------
id TEXT PRIMARY KEY

name TEXT NOT NULL
short_comment TEXT NULL

source_template_id TEXT NULL
source_revision INTEGER NULL

statistics_series_id TEXT NULL

created_at_ms INTEGER NOT NULL
```

## 12.2. sequence_snapshot_settings

```text
sequence_snapshot_settings
--------------------------
sequence_snapshot_id TEXT PRIMARY KEY

auto_advance INTEGER NOT NULL
sequence_start_countdown_ms INTEGER NOT NULL
before_each_step_countdown_ms INTEGER NOT NULL
transition_sound INTEGER NOT NULL
transition_vibration INTEGER NOT NULL
keep_screen_awake INTEGER NOT NULL
confirm_jump INTEGER NOT NULL
confirm_early_end INTEGER NOT NULL
no_live_time_accounting TEXT NOT NULL
```

Это frozen copy relevant Sequence behavior.

## 12.3. sequence_snapshot_nodes

```text
sequence_snapshot_nodes
-----------------------
id TEXT PRIMARY KEY
sequence_snapshot_id TEXT NOT NULL

node_type TEXT NOT NULL
parent_repeat_node_id TEXT NULL
position INTEGER NOT NULL

activity_snapshot_id TEXT NULL
repeat_count INTEGER NULL
```

Каждый STEP содержит собственный Activity snapshot.

## 12.4. sequence_snapshot_fields

```text
sequence_snapshot_fields
------------------------
id TEXT PRIMARY KEY
sequence_snapshot_id TEXT NOT NULL

source_field_id TEXT NULL
position INTEGER NOT NULL

name_at_creation TEXT NOT NULL
local_name_override TEXT NULL
field_type TEXT NOT NULL
unit TEXT NULL
display_precision INTEGER NULL

default_number_scaled INTEGER NULL
default_category_option_id TEXT NULL
default_text TEXT NULL

is_main_value INTEGER NOT NULL DEFAULT 0
```

## 12.5. sequence_snapshot_category_options

```text
sequence_snapshot_category_options
----------------------------------
id TEXT PRIMARY KEY
sequence_snapshot_field_id TEXT NOT NULL

source_option_id TEXT NULL
position INTEGER NOT NULL
label_at_creation TEXT NOT NULL
local_label_override TEXT NULL
```

Snapshot Field/option rows immutable.

Sequence snapshot owns these rows; hard deletion of an unreferenced snapshot may cascade to them.

# 13. ActivityExecution

## 13.1. activity_executions

```text
activity_executions
-------------------
id TEXT PRIMARY KEY

snapshot_id TEXT NOT NULL

context_type TEXT NOT NULL
sequence_execution_id TEXT NULL
sequence_occurrence_id TEXT NULL
plan_entry_id TEXT NULL

statistics_series_id TEXT NULL

status TEXT NOT NULL

started_at_ms INTEGER NULL
completed_at_ms INTEGER NULL

active_duration_ms INTEGER NULL

original_zone_id TEXT NOT NULL
original_utc_offset_minutes INTEGER NULL

completion_reason TEXT NULL

deleted_at_ms INTEGER NULL

created_at_ms INTEGER NOT NULL
updated_at_ms INTEGER NOT NULL
```

`context_type`:

```text
STANDALONE
SEQUENCE_CHILD
```

`status`:

```text
RUNNING
PAUSED
COMPLETED
```

No-live:
- row создаётся сразу `status = COMPLETED`;
- `started_at_ms = NULL`;
- `completed_at_ms` exists;
- `active_duration_ms = NULL`.

Live Stopwatch/Timer:
- during execution `status = RUNNING/PAUSED`;
- `started_at_ms` exists;
- `completed_at_ms = NULL` until completion;
- `completion_reason = NULL` until completion;
- after completion `active_duration_ms` is a derived cache calculated from timestamps minus pauses.

Timestamps + pause rows remain source of truth; cache is recomputed on historical time edits.

`deleted_at_ms` soft-delete historical execution, чтобы Sequence tombstone linkage мог сохраниться.

Hard purge later possible.

### Important

`statistics_series_id` denormalized from snapshot intentionally:
- faster statistics query;
- stable even if snapshot/source later changes.

---

## 13.2. activity_execution_pauses

```text
activity_execution_pauses
-------------------------
id TEXT PRIMARY KEY
activity_execution_id TEXT NOT NULL

started_at_ms INTEGER NOT NULL
ended_at_ms INTEGER NULL
```

Open pause allowed only for currently paused live execution.

---

## 13.3. activity_execution_field_values

```text
activity_execution_field_values
-------------------------------
activity_execution_id TEXT NOT NULL
snapshot_field_id TEXT NOT NULL

number_scaled INTEGER NULL
category_option_id TEXT NULL
text_value TEXT NULL

PRIMARY KEY(activity_execution_id, snapshot_field_id)
```

Exactly one value column expected according to field_type.

`category_option_id` references the **snapshot option id**; effective/current label and source option identity are resolved through the snapshot option row.

Missing field = **row absent**.

Все Custom Fields v1 optional.

Future Required/Recommended policy может добавиться как schema metadata, но v1 не блокирует completion из-за missing values.

Default actual:
при создании Execution можно materialize row сразу, если default != null.

---

# 14. SequenceExecution

## 14.1. sequence_executions

```text
sequence_executions
-------------------
id TEXT PRIMARY KEY

snapshot_id TEXT NOT NULL

plan_entry_id TEXT NULL
statistics_series_id TEXT NULL

status TEXT NOT NULL

started_at_ms INTEGER NOT NULL
ended_at_ms INTEGER NULL

active_duration_ms INTEGER NULL
pause_duration_ms INTEGER NULL
wall_duration_ms INTEGER NULL

original_zone_id TEXT NOT NULL
original_utc_offset_minutes INTEGER NULL

current_occurrence_id TEXT NULL

created_at_ms INTEGER NOT NULL
updated_at_ms INTEGER NOT NULL
```

status:

```text
RUNNING
PAUSED
COMPLETED
ENDED_EARLY
```

Sequence interval timeline remains source of truth.

For completed/historical Sequence, three scalar values are stored as **derived per-execution caches**:

- `active_duration_ms`;
- `pause_duration_ms`;
- `wall_duration_ms`.

They are recalculated transactionally whenever historical timeline data changes.

This is not a global StatsCache; it avoids recomputing interval union across thousands of executions for ordinary Statistics queries.

---

## 14.2. Sequence pause storage

Отдельная `sequence_execution_pauses` table **не используется**.

Все parent Sequence timing intervals, включая explicit pause, хранятся только в `sequence_intervals`.
Это устраняет два конкурирующих source of truth.

# 15. RuntimeOccurrence

## 15.1. sequence_occurrences

```text
sequence_occurrences
--------------------
id TEXT PRIMARY KEY
sequence_execution_id TEXT NOT NULL

source_sequence_snapshot_node_id TEXT NULL
activity_snapshot_id TEXT NOT NULL

runtime_position INTEGER NOT NULL

repeat_source_snapshot_node_id TEXT NULL
repeat_iteration INTEGER NULL

status TEXT NOT NULL

entered_at_ms INTEGER NULL
completed_at_ms INTEGER NULL

completion_reason TEXT NULL

is_runtime_added INTEGER NOT NULL DEFAULT 0
is_deleted_from_history INTEGER NOT NULL DEFAULT 0
```

status:

```text
NOT_STARTED
CURRENT
COMPLETED
SKIPPED
DELETED_EXECUTION
```

`entered_at_ms/completed_at_ms` нужны даже для No-live Step.

Runtime occurrence ссылается на **frozen sequence snapshot node**, а не на mutable SequenceTemplate node.

Для runtime-added Step `source_sequence_snapshot_node_id = NULL`, но `activity_snapshot_id` обязателен.

Child ActivityExecution связывается через:

```text
activity_executions.sequence_occurrence_id
```

с UNIQUE partial/index constraint.

Это убирает bidirectional FK cycle. У untouched skipped occurrence child Execution отсутствует.

### Indexes

```text
INDEX occurrences_execution_pos
ON sequence_occurrences(sequence_execution_id, runtime_position)

INDEX occurrences_status
ON sequence_occurrences(sequence_execution_id, status)
```

---

# 16. Unified Sequence timeline events/intervals

Есть два варианта.

## Option A — derive всё из occurrences + explicit pauses + countdown state

Плюс:
- меньше таблиц.

Минус:
- сложнее historical edits;
- implicit idle/transition countdown становится труднее восстанавливать однозначно.

## Option B — отдельная interval table

Рекомендуется для LifeTracing.

## 16.1. sequence_intervals

```text
sequence_intervals
------------------
id TEXT PRIMARY KEY
sequence_execution_id TEXT NOT NULL

kind TEXT NOT NULL

started_at_ms INTEGER NOT NULL
ended_at_ms INTEGER NULL

occurrence_id TEXT NULL
```

kind:

```text
ACTIVE_STEP
STEP_PAUSE
EXPLICIT_PAUSE
IMPLICIT_IDLE
TRANSITION_COUNTDOWN
```

### Why useful

- Sequence active duration = union intervals kind ACTIVE_STEP;
- pause/idle = union остальных runtime intervals;
- historical edit может явно split/merge intervals;
- No-live Step может создавать ACTIVE_STEP interval, хотя child Activity duration=null;
- setting `No-live time -> PAUSE` создаёт `STEP_PAUSE` interval;
- Leave gap / Close gap проще реализовать.

### Source of truth

Sequence interval table становится source of truth для Sequence runtime accounting.

Occurrence timestamps и intervals должны оставаться согласованы одной domain transaction.

### Where union is calculated

Не пытаться вычислять arbitrary interval union сложным Room DAO.

`SequenceTimelineCalculator` в Kotlin:

1. DAO загружает intervals **одного SequenceExecution** ordered by `started_at_ms`;
2. calculator фильтрует ACTIVE_STEP;
3. merge overlapping/adjacent ranges;
4. active = sum merged ranges;
5. wall = endedAt - startedAt;
6. pause = max(0, wall - active), с учётом правил pre-sequence countdown вне runtime.

После completion/history edit результат записывается в cached columns `sequence_executions`.

Таким образом Statistics query читает numeric duration cache, а не грузит всю историю intervals в память.

---

# 17. Sequence-level Execution Field Values

## 17.1. sequence_execution_field_values

```text
sequence_execution_field_values
-------------------------------
sequence_execution_id TEXT NOT NULL
snapshot_field_id TEXT NOT NULL

number_scaled INTEGER NULL
category_option_id TEXT NULL
text_value TEXT NULL

PRIMARY KEY(sequence_execution_id, snapshot_field_id)
```

Missing = row absent.

Numeric/category/text semantics identical to Activity values.

---

# 18. Live runtime persistence

Отдельная singleton-таблица active_session упрощает восстановление **v1 policy: одна live session**.

Это не фундаментальное ограничение execution tables.

Future parallel-session migration:
- заменить singleton pointer на multi-row active_sessions;
- исторические ActivityExecution/SequenceExecution IDs и data model менять не требуется.

## 18.1. active_session

Таблица содержит максимум одну row.

```text
active_session
--------------
singleton_id INTEGER PRIMARY KEY CHECK(singleton_id = 1)

session_kind TEXT NOT NULL
activity_execution_id TEXT NULL
sequence_execution_id TEXT NULL

state TEXT NOT NULL

updated_at_ms INTEGER NOT NULL
```

session_kind:

```text
ACTIVITY
SEQUENCE
```

state:

```text
RUNNING
PAUSED
WAITING_NEXT
```

### Why separate

Не нужно при startup сканировать всю history в поисках незавершённых rows.

Но execution tables остаются source data; active_session — runtime pointer.

Single-row constraint enforce single live session.

---

# 19. Plan fulfillment

Когда Execution стартует из Plan:

transaction:

1. создать Execution из stored Plan snapshot;
2. сохранить `plan_entry_id`;
3. Plan пока может остаться PLANNED до actual completion, либо считаться engaged.

Первая product model хранит только PLANNED/FULFILLED/CANCELLED.

Рекомендуется:

- live running execution не меняет Plan status;
- после завершения execution -> FULFILLED;
- early-ended Sequence тоже считается FULFILLED, если она была реально выполнена/started from Plan? Это product detail можно позже уточнить, но database linkage уже поддерживает оба.

No-live completion -> сразу FULFILLED.

Если fulfilled linked Execution потом soft-deleted:
- Plan остаётся FULFILLED;
- FK/link остаётся на deleted row;
- UI может показать deleted execution.

---

# 20. Cancelled Plans

Cancelled Plan не удаляется.

```text
status = CANCELLED
cancelled_at_ms != null
```

Restore:

```text
status = PLANNED
cancelled_at_ms = null
```

Если period past -> overdue derived.

Ordinary Plan queries exclude CANCELLED unless explicitly requested.

---

# 21. Archived Templates

Archive:

```text
deleted_at_ms = now
```

Restore:

```text
deleted_at_ms = null
```

Main Library query:

```text
WHERE deleted_at_ms IS NULL
```

Archived screen:

```text
WHERE deleted_at_ms IS NOT NULL
```

Do not cascade history.

---

# 22. Template revision

Revision increment transactionally on semantic edit.

Do **not** increment for:

- folder move;
- pin reorder;
- tags.

Do increment for:

- Template name;
- Short Comment;
- timing config;
- Field type/unit/default/schema changes;
- add/remove Field;
- Sequence structure/settings.

Do **not** increment semantic revision for Field/Category display-label rename:
linked snapshots inherit those presentation labels automatically unless they have local override.

Snapshot stores source_revision.

`Template changed`:

```text
source_template_id != null
AND source_revision != currentTemplate.revision
```

if template not archived.

---

# 23. Library Pinned ordering

Pinned хранится в `*_template_user_state.pinned_rank`, не в semantic Template.

Long-press reorder:
- обновляет только user-state table;
- Template `revision` не меняется.

Можно использовать sparse rank values.

---

# 24. Recent

Recent хранится в `*_template_user_state.last_used_at_ms`.

Update on explicit use:
- start execution;
- quick complete No-live;
- start Sequence;
- use source-derived Plan snapshot, если source Template существует.

Это launcher metadata, не source of truth для history/Statistics.

Разделение таблиц уменьшает ненужные invalidation/re-query для observer, который следит только за semantic Template table.

# 25. Delete / FK policy

## 25.1. General rule

Prefer soft delete for user-domain data with historical references.

Avoid broad `ON DELETE CASCADE` from Templates to history.

## 25.2. Safe cascades

Cascade is appropriate for pure owned configuration children when parent itself is **hard-purged**:

- activity_template_settings;
- template custom fields;
- category options;
- tag join rows;
- sequence_nodes.

But normal archive does not delete parent row, so cascade does nothing.

## 25.3. Snapshot lifecycle / garbage collection

Snapshot rows immutable.

Snapshots referenced by Plan/Execution/Sequence must not disappear.

Use FK RESTRICT for externally referenced snapshots and CASCADE only for snapshot-owned children (settings/fields/options).

### Immediate transactional pruning

Любая операция, которая заменяет snapshot reference, выполняется **в одной DB transaction**:

1. create replacement snapshot;
2. update owner/reference;
3. проверить old snapshot references внутри той же transaction;
4. если references = 0 — hard-delete old snapshot + owned child rows;
5. commit.

При crash до commit вся операция откатывается. Safety GC нужен только как защита от старых migrations/bugs, а не как normal lifecycle.

Примеры:
- Sequence Step edit;
- Plan `Update from template`;
- replacing runtime-added config before start;
- hard purge Plan/Sequence config.

### Reference check for activity_snapshot

Проверять как минимум ссылки из:
- `sequence_nodes.activity_snapshot_id`;
- `sequence_snapshot_nodes.activity_snapshot_id`;
- `plan_entries.activity_snapshot_id`;
- `activity_executions.snapshot_id`;
- `sequence_occurrences.activity_snapshot_id`.

### Reference check for sequence_snapshot

Проверять:
- `plan_entries.sequence_plan_snapshot_id`;
- `sequence_executions.snapshot_id`.

### Safety maintenance GC

Периодический GC **не является основным механизмом**.

Можно иметь idempotent maintenance command `pruneAllOrphanSnapshots()`:
- после schema migrations;
- в debug/maintenance;
- либо редко в idle background.

Он удаляет только rows, для которых `NOT EXISTS` ни одной owner/reference.

Нормальная работа приложения должна предотвращать bloat immediate pruning-ом, а не ждать глобальной уборки.

---

# 26. Statistics queries

## 26.1. Global top-level time

Standalone Activity:

```text
context_type = STANDALONE
deleted_at_ms IS NULL
```

plus SequenceExecution.

Do not include child ActivityExecution duration again.

## 26.2. Per-Activity Series

```text
activity_executions.statistics_series_id = ?
deleted_at_ms IS NULL
```

includes standalone and child contexts.

## 26.3. Duration

Activity duration:
- Stopwatch/Timer only;
- derived from execution timestamps minus pauses;
- No-live -> NULL.

Sequence active:
- union `ACTIVE_STEP` intervals.

Sequence pause:
- runtime span minus active union, or union pause/idle intervals according to finalized interval model.

## 26.4. Number Field

Because number_scaled INTEGER:

```sql
MIN(number_scaled)
MAX(number_scaled)
SUM(number_scaled)
COUNT(*)
```

numeric behavior correct.

Median in application layer from ordered integer values, unless later SQLite window function strategy is chosen.

---

# 27. Recommended Room aggregate boundaries

Entities should not become one giant Room object graph.

Recommended repositories/use-cases:

- TemplateRepository
- SequenceRepository
- PlanRepository
- ExecutionRepository
- ActiveSessionRepository
- LibraryRepository
- StatisticsRepository

Transactions in domain service/use-case layer for multi-table operations.

Example:

`startSequenceFromPlan(planId)` transaction:
- read Plan snapshot;
- create SequenceExecution;
- materialize RuntimeOccurrences;
- create initial intervals/current;
- create active_session row;
- update lastUsedAt if applicable.

---

# 28. Migration strategy

Before public release schema version can iterate aggressively.

Once user data exists:

- never repurpose enum meanings;
- add columns with defaults;
- new field semantics -> migration;
- preserve snapshots/history.

Keep migration tests from first version shipped to users.

---

# 29. Database integrity constraints

Important CHECK/domain validations:

Activity Template:
- TIMER => target duration > 0.
- non-TIMER => timer_target_ms null.

Plan:
- precision DAY => planned_day not null.
- WEEK => planned_week_start not null.
- MONTH => planned_month not null.
- scheduled_instant_ms only valid for DAY.

Occurrence:
- max one CURRENT per SequenceExecution — enforce with partial unique index if practical.

Active session:
- max one row by singleton primary key.

Folder:
- no self-parent; cycle check application layer.

Custom Field:
- field-type-specific value columns.

---

# 30. Partial unique indexes worth adding manually

SQLite supports partial indexes.

Examples:

## One current occurrence

```sql
CREATE UNIQUE INDEX idx_one_current_occurrence
ON sequence_occurrences(sequence_execution_id)
WHERE status = 'CURRENT';
```

## One main field per snapshot

```sql
CREATE UNIQUE INDEX idx_one_main_snapshot_field
ON activity_snapshot_fields(snapshot_id)
WHERE is_main_value = 1;
```

## One main field per template

Similar for template fields.

---

# 31. What not to store as TEXT JSON in v0.1

Avoid opaque JSON for:

- Custom Field values;
- numeric values;
- execution timeline;
- Plan date/time;
- occurrence status/order;
- source revision;
- statistics identity.

Possible JSON usage later for:
- non-queryable optional UI preferences;
- experimental settings.

Core domain data stays typed.

---

# 32. Open persistence questions for v0.2

Решения v0.2:

## 32.1. UUID

v1: UUID as TEXT for Room simplicity.

Future BLOB optimization не оправдывает усложнение первой версии.

## 32.2. Week

v1 stores `planned_week_start` as ISO-like Monday LocalDate (`YYYY-MM-DD`).

UI week first day = Monday in v1.

Locale-configurable week policy — future extension.

## 32.3. Sequence intervals

`sequence_intervals` accepted as source of truth for Sequence timing.

Union/recalculation выполняет Kotlin domain calculator.

Derived duration caches хранятся на `sequence_executions`.

## 32.4. Snapshot lifecycle

Snapshots immutable.

No content-hash deduplication.

Replacement + reference swap + immediate prune.

Safety GC only secondary.

## 32.5. Activity duration cache

Accepted.

Completed timed `activity_executions.active_duration_ms` хранит derived cache.

No-live -> NULL.

History edit recalculates it.

## 32.6. Global Statistics cache

Не добавлять отдельные aggregate StatsCache tables до profiling.

Per-execution duration caches already remove наиболее неудобные timeline calculations.

## 32.7. Room observable-query isolation

High-frequency launcher metadata (`last_used_at_ms`) moved out of Template tables into separate user-state tables.

## 32.8. Field rename

Final:
- stable Field/Option ids;
- source display labels mutable;
- snapshot local override optional;
- Statistics group by ids, never labels.

# 33. Proposed first implementation order

1. Folder / Tag / StatisticsSeries.
2. ActivityTemplate + fields + settings.
3. ActivitySnapshot.
4. ActivityExecution + pauses + field values.
5. SequenceTemplate + nodes.
6. SequenceSnapshot.
7. SequenceExecution + occurrences + intervals.
8. ActiveSession.
9. PlanEntry.
10. Library queries.
11. Statistics queries.
12. Archive/restore.
13. Migration tests.

---

# 34. Main schema invariants

1. Template archive never deletes history.
2. Snapshot is self-contained.
3. StatisticsSeriesId survives source Template deletion.
4. Plan execution uses Plan snapshot, not current Template.
5. Child Activity time is not added to global time twice.
6. No-live Activity duration remains NULL.
7. Sequence occurrence can have timeline interval even when child duration=NULL.
8. Numeric values are INTEGER-scaled, not lexical text.
9. Cancelled Plan is stored and excluded from ordinary Plan queries.
10. Overdue is derived.
11. History original timezone is preserved.
12. Exact-time Plan uses absolute Instant.
13. Broad Day/Week/Month Plan uses floating calendar value.
14. Single active live session is enforced only by v1 `active_session` policy; execution history schema itself is parallel-ready.
15. Stats cache, if added, is disposable/derived.

# 35. Review resolution summary

## Custom Fields

v0.1 ambiguity removed:
- no generic owner_type table;
- explicit typed template/snapshot field tables.

## Cancelled Plan

State transitions and cancelled/fulfilled timestamps defined.

## Repeat naming

Database `REPEAT` node belongs only to Sequence structure.
Product/domain full term: `SequenceRepeatBlock`.
Plan recurrence absent v1.

## Timezone

- exact-time Plan = Instant;
- Day/Week/Month = floating calendar;
- exact-time calendar placement derived in current device timezone.

## Snapshot GC

Immediate reference-aware pruning specified; global GC only safety maintenance.

## Interval union

Calculated per SequenceExecution in Kotlin, not complex SQL.
Derived scalar duration cache stored for Statistics.

## Single active session

Singleton pointer explicitly v1-only and migration path documented.

## Required fields

Still optional v1 by product decision; schema preserves missing.

## `last_used_at_ms`

Moved to user-state table to isolate high-frequency invalidation.

## No-live inside Sequence

Child duration remains NULL; parent Sequence interval classification is separate accounting.

## Field rename

Display-name rename semantics is final:
stable identity + global inheritance + per-snapshot local override.

# 36. Field label persistence — final v0.3 design

## 36.1. Template Field

Template Field row keeps:

```text
id TEXT PRIMARY KEY
name TEXT NOT NULL
...
updated_at_ms INTEGER NOT NULL
deleted_at_ms INTEGER NULL
```

`id` is identity.

Updating `name` is ordinary metadata update and does not create a new row.

## 36.2. Snapshot Field columns

`activity_snapshot_fields` and `sequence_snapshot_fields` should use:

```text
source_field_id TEXT NULL

name_at_creation TEXT NOT NULL
local_name_override TEXT NULL
```

instead of treating a single copied `name` as always authoritative.

Effective UI name:

```text
COALESCE(
    local_name_override,
    current source_field.name,
    name_at_creation
)
```

Do not implement that exact expression as a complex cross-table Room observable everywhere; repository/domain mapper may resolve it.

Historical export can optionally expose both:
- effective current display name;
- name_at_creation.

## 36.3. Snapshot self-containment

`name_at_creation` guarantees that snapshot remains readable if source Field metadata is permanently purged in the future.

Soft-archived source Field normally remains available, so typo corrections keep propagating.

## 36.4. Local override

Only explicit local Field rename writes `local_name_override`.

Changing:
- Activity snapshot name;
- Short Comment;
- Timer;
- another Field;
- another value

does not fill this column.

## 36.5. Category options

Snapshot option tables should analogously use:

```text
source_option_id TEXT NULL
label_at_creation TEXT NOT NULL
local_label_override TEXT NULL
```

Template option label rename keeps stable option id.

## 36.6. Statistics

Statistics group by:
- Field id / source Field identity;
- Category option id.

Never group by display text.

Therefore rename cannot split historical Statistics.

For true one-off Field (`source_field_id = NULL`), snapshot Field id/local identity is used only inside that snapshot/execution context and does not join reusable per-Field Statistics.

## 36.7. Template revision

Field display-name rename is metadata-only for v1 and does not increment Template semantic `revision`.

Field type/unit/schema changes follow existing semantic revision/replacement rules.


# 37. Foreign-key policy — v0.4

Обычное пользовательское удаление Template/Execution — soft delete, поэтому FK policies в основном защищают от случайного hard-delete.

Ниже `RESTRICT` означает: hard-delete parent запрещён, пока явный purge flow не обработал dependents.

## 37.1. Library/config

```text
activity_templates.statistics_series_id
    -> statistics_series.id RESTRICT

sequence_templates.statistics_series_id
    -> statistics_series.id RESTRICT

activity_templates.folder_id
    -> folders.id RESTRICT

sequence_templates.folder_id
    -> folders.id RESTRICT

folders.parent_folder_id
    -> folders.id RESTRICT

activity_template_user_state.activity_template_id
    -> activity_templates.id CASCADE

sequence_template_user_state.sequence_template_id
    -> sequence_templates.id CASCADE

activity_template_settings.activity_template_id
    -> activity_templates.id CASCADE

sequence_template_settings.sequence_template_id
    -> sequence_templates.id CASCADE

activity_template_fields.activity_template_id
    -> activity_templates.id CASCADE

sequence_template_fields.sequence_template_id
    -> sequence_templates.id CASCADE

*_template_category_options.*_template_field_id
    -> corresponding template field CASCADE

*_template_tags.template_id
    -> corresponding template CASCADE

*_template_tags.tag_id
    -> tags.id CASCADE
```

Folder delete flow сначала переносит/удаляет children, после чего `RESTRICT` позволяет hard-delete пустую Folder.

## 37.2. Snapshot source links

Source links являются слабее ownership links:

```text
activity_snapshots.source_template_id
    -> activity_templates.id SET NULL on future hard purge

activity_snapshot_fields.source_field_id
    -> activity_template_fields.id SET NULL on future hard purge

activity_snapshot_category_options.source_option_id
    -> activity_template_category_options.id SET NULL

sequence_snapshots.source_template_id
    -> sequence_templates.id SET NULL

sequence_snapshot_fields.source_field_id
    -> sequence_template_fields.id SET NULL

sequence_snapshot_category_options.source_option_id
    -> sequence_template_category_options.id SET NULL
```

Soft archive ничего не обнуляет.

Fallback `name_at_creation` / `label_at_creation` сохраняет snapshot readable после future purge source metadata.

## 37.3. Snapshot ownership

```text
activity_snapshot_settings.snapshot_id
    -> activity_snapshots.id CASCADE

activity_snapshot_fields.snapshot_id
    -> activity_snapshots.id CASCADE

activity_snapshot_category_options.snapshot_field_id
    -> activity_snapshot_fields.id CASCADE

sequence_snapshot_settings.sequence_snapshot_id
    -> sequence_snapshots.id CASCADE

sequence_snapshot_fields.sequence_snapshot_id
    -> sequence_snapshots.id CASCADE

sequence_snapshot_category_options.sequence_snapshot_field_id
    -> sequence_snapshot_fields.id CASCADE

sequence_snapshot_nodes.sequence_snapshot_id
    -> sequence_snapshots.id CASCADE
```

External owners reference snapshot with `RESTRICT`, so snapshot cannot disappear while used.

## 37.4. Sequence Template structure

```text
sequence_nodes.sequence_template_id
    -> sequence_templates.id CASCADE

sequence_nodes.parent_repeat_node_id
    -> sequence_nodes.id CASCADE

sequence_nodes.activity_snapshot_id
    -> activity_snapshots.id RESTRICT
```

Nested Repeat prohibition remains CHECK/domain validation.

## 37.5. Plan

```text
plan_entries.source_activity_template_id
    -> activity_templates.id SET NULL on hard purge

plan_entries.source_sequence_template_id
    -> sequence_templates.id SET NULL

plan_entries.activity_snapshot_id
    -> activity_snapshots.id RESTRICT

plan_entries.sequence_plan_snapshot_id
    -> sequence_snapshots.id RESTRICT

plan_entries.fulfilled_activity_execution_id
    -> activity_executions.id SET NULL on hard purge

plan_entries.fulfilled_sequence_execution_id
    -> sequence_executions.id SET NULL on hard purge
```

Soft-deleted linked Execution остаётся row, поэтому обычная history deletion ссылку не обнуляет.

## 37.6. Activity Execution

```text
activity_executions.snapshot_id
    -> activity_snapshots.id RESTRICT

activity_executions.plan_entry_id
    -> plan_entries.id SET NULL on Plan hard purge

activity_executions.sequence_execution_id
    -> sequence_executions.id RESTRICT

activity_executions.sequence_occurrence_id
    -> sequence_occurrences.id RESTRICT

activity_executions.statistics_series_id
    -> statistics_series.id RESTRICT

activity_execution_pauses.activity_execution_id
    -> activity_executions.id CASCADE

activity_execution_field_values.activity_execution_id
    -> activity_executions.id CASCADE

activity_execution_field_values.snapshot_field_id
    -> activity_snapshot_fields.id RESTRICT
```

For `STANDALONE`:
- sequence_execution_id = NULL;
- sequence_occurrence_id = NULL.

For `SEQUENCE_CHILD`:
- both are NOT NULL.

## 37.7. Sequence Execution

```text
sequence_executions.snapshot_id
    -> sequence_snapshots.id RESTRICT

sequence_executions.plan_entry_id
    -> plan_entries.id SET NULL

sequence_executions.statistics_series_id
    -> statistics_series.id RESTRICT

sequence_executions.current_occurrence_id
    -> sequence_occurrences.id SET NULL

sequence_occurrences.sequence_execution_id
    -> sequence_executions.id CASCADE

sequence_occurrences.source_sequence_snapshot_node_id
    -> sequence_snapshot_nodes.id SET NULL

sequence_occurrences.activity_snapshot_id
    -> activity_snapshots.id RESTRICT

sequence_intervals.sequence_execution_id
    -> sequence_executions.id CASCADE

sequence_intervals.occurrence_id
    -> sequence_occurrences.id SET NULL

sequence_execution_field_values.sequence_execution_id
    -> sequence_executions.id CASCADE

sequence_execution_field_values.snapshot_field_id
    -> sequence_snapshot_fields.id RESTRICT
```

## 37.8. Active session

```text
active_session.activity_execution_id
    -> activity_executions.id RESTRICT

active_session.sequence_execution_id
    -> sequence_executions.id RESTRICT
```

Domain transaction deletes/changes active pointer before any destructive operation.

# 38. CHECK constraints and cross-row invariants

SQLite CHECK should cover row-local invariants; domain layer covers cross-table semantics.

## 38.1. Activity Template/Snapshot

```text
TIMER => timer_target_ms > 0
non-TIMER => timer_target_ms IS NULL
```

## 38.2. ActivityExecution

```text
status IN (RUNNING, PAUSED, COMPLETED)

COMPLETED => completed_at_ms IS NOT NULL
RUNNING/PAUSED => completed_at_ms IS NULL

NO_LIVE snapshot =>
    status = COMPLETED
    AND started_at_ms IS NULL
    AND active_duration_ms IS NULL
```

Mode-dependent validation may require domain check because mode lives in snapshot table.

## 38.3. PlanEntry

Exactly one Trackable snapshot/source kind.

```text
trackable_kind = ACTIVITY =>
    activity_snapshot_id IS NOT NULL
    AND sequence_plan_snapshot_id IS NULL

trackable_kind = SEQUENCE =>
    sequence_plan_snapshot_id IS NOT NULL
    AND activity_snapshot_id IS NULL
```

Temporal shape:

```text
DAY + exact:
    scheduled_instant_ms IS NOT NULL
    planned_day IS NULL
    planned_week_start IS NULL
    planned_month IS NULL

DAY floating:
    scheduled_instant_ms IS NULL
    planned_day IS NOT NULL
    planned_week_start IS NULL
    planned_month IS NULL

WEEK:
    planned_week_start IS NOT NULL
    all other temporal target columns NULL

MONTH:
    planned_month IS NOT NULL
    all other temporal target columns NULL
```

State timestamps use the constraints from section 11.

## 38.4. SequenceNode

Domain validates:
- STEP xor REPEAT payload;
- REPEAT cannot have parent_repeat_node_id;
- STEP under REPEAT allowed;
- no nested Sequence.

## 38.5. Custom Field value

Domain validates exactly one typed value according to snapshot field type.

Missing = no row.

# 39. Final indexes for v1

## Templates / Library

```text
activity_templates(deleted_at_ms, name)
sequence_templates(deleted_at_ms, name)

activity_templates(folder_id, deleted_at_ms)
sequence_templates(folder_id, deleted_at_ms)

activity_template_user_state(pinned_rank)
activity_template_user_state(last_used_at_ms DESC)

sequence_template_user_state(pinned_rank)
sequence_template_user_state(last_used_at_ms DESC)
```

## Fields

```text
activity_template_fields(activity_template_id, deleted_at_ms, position)
sequence_template_fields(sequence_template_id, deleted_at_ms, position)

activity_snapshot_fields(snapshot_id, position)
activity_snapshot_fields(source_field_id)

sequence_snapshot_fields(sequence_snapshot_id, position)
sequence_snapshot_fields(source_field_id)

activity_snapshot_category_options(snapshot_field_id, position)
activity_snapshot_category_options(source_option_id)

sequence_snapshot_category_options(snapshot_field_id, position)
sequence_snapshot_category_options(source_option_id)
```

## Plan

```text
plan_entries(status, planned_day)
plan_entries(status, planned_week_start)
plan_entries(status, planned_month)
plan_entries(status, scheduled_instant_ms)

plan_entries(source_activity_template_id)
plan_entries(source_sequence_template_id)
```

For selected Day:
- floating Day query uses `planned_day`;
- exact-time query uses `[dayStartInstant, nextDayStartInstant)` calculated in current device ZoneId.

## Activity history/statistics

```text
activity_executions(statistics_series_id, completed_at_ms)
activity_executions(context_type, completed_at_ms)
activity_executions(plan_entry_id)
activity_executions(sequence_execution_id)
activity_executions(sequence_occurrence_id)
activity_executions(deleted_at_ms)

activity_execution_pauses(activity_execution_id, started_at_ms)

activity_execution_field_values(snapshot_field_id)
activity_execution_field_values(number_scaled)
activity_execution_field_values(category_option_id)
```

## Sequence

```text
sequence_snapshot_nodes(sequence_snapshot_id, parent_repeat_node_id, position)

sequence_occurrences(sequence_execution_id, runtime_position)
sequence_occurrences(sequence_execution_id, status)

sequence_intervals(sequence_execution_id, started_at_ms)
sequence_intervals(sequence_execution_id, kind, started_at_ms)

sequence_executions(statistics_series_id, ended_at_ms)
sequence_executions(plan_entry_id)
```

# 40. Partial unique indexes

Create manually in Room migration/callback where needed.

```sql
CREATE UNIQUE INDEX idx_one_current_occurrence
ON sequence_occurrences(sequence_execution_id)
WHERE status = 'CURRENT';

CREATE UNIQUE INDEX idx_one_activity_main_snapshot_field
ON activity_snapshot_fields(snapshot_id)
WHERE is_main_value = 1;

CREATE UNIQUE INDEX idx_one_sequence_main_snapshot_field
ON sequence_snapshot_fields(sequence_snapshot_id)
WHERE is_main_value = 1;

CREATE UNIQUE INDEX idx_one_activity_template_main_field
ON activity_template_fields(activity_template_id)
WHERE is_main_value = 1 AND deleted_at_ms IS NULL;

CREATE UNIQUE INDEX idx_one_sequence_template_main_field
ON sequence_template_fields(sequence_template_id)
WHERE is_main_value = 1 AND deleted_at_ms IS NULL;

CREATE UNIQUE INDEX idx_one_child_execution_per_occurrence
ON activity_executions(sequence_occurrence_id)
WHERE sequence_occurrence_id IS NOT NULL;
```

`active_session.singleton_id = 1` separately enforces one v1 live pointer.

# 41. Room entity boundaries

Recommended Room `@Entity` types map nearly 1:1 to tables.

Do **not** expose Room entities directly to Compose/UI.

Layers:

```text
Room Entity
   ↓ mapper
Domain model / snapshot aggregate
   ↓ use case
UI model
```

Main entity groups:

### Library
- ActivityTemplateEntity
- SequenceTemplateEntity
- ActivityTemplateUserStateEntity
- SequenceTemplateUserStateEntity
- FolderEntity
- TagEntity
- join entities

### Field schema
- ActivityTemplateFieldEntity
- ActivityTemplateCategoryOptionEntity
- SequenceTemplateFieldEntity
- SequenceTemplateCategoryOptionEntity

### Frozen config
- ActivitySnapshotEntity
- ActivitySnapshotSettingsEntity
- ActivitySnapshotFieldEntity
- ActivitySnapshotCategoryOptionEntity
- SequenceSnapshotEntity
- SequenceSnapshotSettingsEntity
- SequenceSnapshotNodeEntity
- SequenceSnapshotFieldEntity
- SequenceSnapshotCategoryOptionEntity

### Runtime/history
- ActivityExecutionEntity
- ActivityExecutionPauseEntity
- ActivityExecutionFieldValueEntity
- SequenceExecutionEntity
- SequenceOccurrenceEntity
- SequenceIntervalEntity
- SequenceExecutionFieldValueEntity
- ActiveSessionEntity

### Plan / Statistics
- PlanEntryEntity
- StatisticsSeriesEntity

# 42. DAO boundaries

Prefer focused DAOs rather than one DAO per screen.

## TemplateDao

- observe active Activity Templates
- observe archived Activity Templates
- get Activity Template aggregate source rows
- insert/update/archive/restore Template
- Field/schema mutations

## SequenceTemplateDao

- same for Sequence;
- nodes/Repeat structure.

## SnapshotDao

Internal persistence utility:
- insert Activity/Sequence snapshot aggregate;
- reference-count queries;
- delete confirmed orphan snapshot aggregate.

UI should not mutate snapshots directly through this DAO.

## PlanDao

- observe Day/Week/Month plan;
- observe cancelled plans;
- get Plan with snapshot id;
- status/reschedule updates.

## ActivityExecutionDao

- current execution source rows;
- history range;
- per-Series history;
- pauses;
- values.

## SequenceExecutionDao

- execution;
- occurrences;
- intervals;
- timeline ordered load.

## LibraryStateDao

- Pinned;
- Recent;
- reorder.

## StatisticsDao

Only aggregate-friendly SQL:
- counts;
- SUM cached durations;
- MIN/MAX;
- numeric field aggregates.

Median/interval-union and complex derived semantics stay in Kotlin calculators.

# 43. Transaction use-cases

Use `RoomDatabase.withTransaction` / repository transaction boundary.

Critical transactions:

## 43.1. `createOrEditSequenceStep`

1. build immutable replacement ActivitySnapshot;
2. insert snapshot + settings + field schema;
3. repoint SequenceNode;
4. set `locallyModified` semantics;
5. prune old snapshot if unreferenced.

## 43.2. `updatePlanFromTemplate`

1. load current source Template revision;
2. build new Activity/Sequence snapshot;
3. repoint PlanEntry;
4. update sourceRevision;
5. prune old snapshot if unreferenced.

## 43.3. `startStandaloneActivity`

1. check v1 active-session policy;
2. snapshot current Template or one-off config;
3. create ActivityExecution RUNNING;
4. create `active_session`;
5. update launcher `lastUsedAt`.

No-live uses separate quick-complete transaction and never creates active_session.

## 43.4. `pause/resumeActivity`

Transactionally:
- update ActivityExecution.status;
- open/close pause row;
- update active_session.state.

## 43.5. `completeActivity`

1. close open pause if needed;
2. set COMPLETED/completedAt/reason;
3. recalculate active_duration_ms;
4. clear active_session if standalone current;
5. if linked Plan -> fulfill Plan transactionally.

## 43.6. `startSequence`

1. check v1 active-session policy;
2. create/use frozen SequenceSnapshot;
3. create SequenceExecution;
4. materialize runtime occurrences from frozen nodes/Repeat count;
5. create first interval/current occurrence according to settings;
6. create active_session;
7. update Recent.

## 43.7. `advanceSequence`

Atomic:
- finish current occurrence/child Execution;
- close current interval;
- create transition/next interval;
- update occurrence statuses;
- update `current_occurrence_id`;
- preserve Auto-advance/WAITING_NEXT semantics.

## 43.8. `complete/endSequence`

1. finalize current Step if applicable;
2. close open intervals;
3. set endedAt/status;
4. `SequenceTimelineCalculator` recomputes active/pause/wall caches;
5. clear active_session;
6. fulfill linked Plan if applicable.

## 43.9. `editHistoricalExecution`

Activity:
- update source timestamps/pauses/values/snapshot reference;
- recompute active duration cache.

Sequence:
- update occurrences/intervals;
- run timeline calculator;
- persist active/pause/wall caches.

All in one transaction.

## 43.10. `cancel/restorePlan`

Use status/timestamp state machine exactly as section 11.

# 44. Statistics implementation boundary

SQL does the cheap numeric aggregation over **per-execution cached/source values**.

Kotlin does:
- median;
- interval union;
- display formatting;
- coverage logic spanning changing schemas when necessary.

Do not load all application intervals for global Statistics.

Sequence intervals are loaded only:
- for one execution while running/editing;
- when repairing/recalculating that execution.

Global Sequence totals use cached `sequence_executions.active_duration_ms/pause_duration_ms`.

# 45. One-off Statistics persistence

Create one reserved `statistics_series` row:

```text
kind = ONE_OFF_BUCKET
```

Standalone one-off ActivityExecution:
- `statistics_series_id = ONE_OFF_BUCKET`.

One-off child Activity inside Sequence:
- `statistics_series_id = NULL`;
- contributes only to parent Sequence/global accounting through SequenceExecution;
- therefore no double-counting.

Custom Field values of standalone one-off Activity may remain stored in history, but v1 does not aggregate them into reusable per-Field Statistics.

# 46. Remaining day-boundary decision

Before declaring the schema fully frozen, one product rule should be confirmed:

For a timed Execution that crosses midnight, which day owns the execution in Daily/History and period Statistics?

Recommended v1:

```text
Stopwatch/Timer Activity:
    primary day = original local date of startedAt

Sequence:
    primary day = original local date of startedAt

No-live:
    primary day = original local date of completedAt
```

Then persist a denormalized/indexable:

```text
primary_local_date TEXT  // YYYY-MM-DD
```

on `activity_executions` and `sequence_executions`.

This avoids expensive ZoneId conversion in SQLite queries and makes Daily/history lookup deterministic.

For v1, the **whole execution duration** is attributed to this primary day in day/week/month totals.

Future advanced reporting may split duration at midnight without changing source timestamps/intervals.

# 47. Primary local date — persisted/indexable

Add to `activity_executions`:

```text
primary_local_date TEXT NOT NULL  // YYYY-MM-DD
```

Add to `sequence_executions`:

```text
primary_local_date TEXT NOT NULL  // YYYY-MM-DD
```

Calculation:

Timed Activity:
```text
localDate(started_at_ms, original_zone_id)
```

No-live:
```text
localDate(completed_at_ms, original_zone_id)
```

Sequence:
```text
localDate(started_at_ms, original_zone_id)
```

This field is denormalized but deterministic from source temporal data.

Historical timestamp/ZoneId edit must recalculate it transactionally.

Indexes:

```text
activity_executions(primary_local_date, deleted_at_ms)
sequence_executions(primary_local_date)
```

Daily query does not need SQLite ZoneId conversion.

# 48. Manual/backdated Activity persistence

No new table is required.

Existing `activity_executions` supports all three flows because `created_at_ms` is independent from semantic `started_at_ms`.

## 48.1. Start now

```text
status = RUNNING
started_at_ms = now
completed_at_ms = NULL
primary_local_date = today in original_zone_id
```

## 48.2. Started earlier, still live

```text
status = RUNNING
started_at_ms = selected past Instant
completed_at_ms = NULL
created_at_ms = actual insertion time
primary_local_date = local date of selected startedAt
```

Create normal v1 `active_session`.

Stopwatch elapsed is derived from selected startedAt minus pauses.

## 48.3. Manual completed timed history

```text
status = COMPLETED
started_at_ms = selected past Instant
completed_at_ms = selected past Instant
active_duration_ms = completed - started
completion_reason = MANUAL_HISTORY_ENTRY
primary_local_date = local date of startedAt
```

No `active_session`.

No pause rows are created initially.

## 48.4. Manual No-live history

```text
status = COMPLETED
started_at_ms = NULL
completed_at_ms = selected past Instant
active_duration_ms = NULL
completion_reason = MANUAL_HISTORY_ENTRY
primary_local_date = local date of completedAt
```

## 48.5. Timezone

`original_zone_id` for manual entry is the ZoneId used to interpret entered local date/time.

UI default = current device ZoneId.

`original_utc_offset_minutes` should be calculated for the actual selected Instant in that ZoneId, so DST historical offset is correct.

Do not use today's UTC offset for an old historical date.

## 48.6. Input validation

Domain/UI validate:

Timed completed:
```text
started_at_ms <= completed_at_ms <= now
```

Backdated live:
```text
started_at_ms <= now
```

Cross-midnight is valid automatically.

Exact same-start/end interval may be allowed as zero duration, but UI can warn; no DB prohibition is necessary.

# 49. Start Activity transaction variants

## 49.1. Quick start

Existing transaction unchanged.

## 49.2. `startActivityAt(startLocalDateTime, zoneId)`

1. resolve local date-time + ZoneId -> Instant;
2. validate <= now;
3. create current immutable ActivitySnapshot;
4. create ActivityExecution RUNNING with selected startedAt;
5. compute primary_local_date from event ZoneId;
6. create active_session;
7. update Recent metadata.

## 49.3. `addActivityToHistory(start, end, zoneId)`

1. resolve both local date-times in same selected event ZoneId;
2. validate start <= end <= now;
3. create current ActivitySnapshot / edited local snapshot;
4. create COMPLETED execution;
5. set `completion_reason = MANUAL_HISTORY_ENTRY`;
6. set `active_duration_ms = end - start`;
7. compute primary_local_date from start;
8. persist actual Field values;
9. no active_session.

If invoked from Plan:
10. set plan_entry_id;
11. fulfill Plan transactionally.

## 49.4. `addNoLiveToHistory(completedAt, zoneId)`

Analogous:
- completed only;
- duration NULL;
- primary day from completion.

# 50. Manual historical entry and Timer runtime

Completed historical Timer does **not** execute timer state-machine replay.

This avoids incorrect behavior such as truncating a remembered 14-minute real execution to a 10-minute configured target.

For a backdated **live** Timer, runtime semantics are still real Timer semantics.

If selected startedAt means the target should already have expired:
- `OVERTIME` Timer can reconstruct current overtime state directly;
- `FINISH at zero` cannot silently remain RUNNING.

This last UI choice is product-level:
- either refuse and offer `Add as completed`;
- or allow explicit one-time `Continue in overtime`.

Schema supports both without change.

# 51. Manual Sequence backfill — not frozen yet

The DB already can represent historical SequenceExecution, occurrences and intervals.

However `start Sequence at a past time and continue now` cannot infer:
- which occurrence is current;
- which prior Steps completed/skipped;
- repeat iteration state.

Therefore v0.5 does not define a transaction that invents these values.

Recommended v1 scope:
- manual/backdated Activity: supported;
- manual/backdated Sequence: defer until explicit child-timeline entry UX is specified.

# 52. v0.5 freeze status

Database structure is now effectively frozen for Activity/Plan/Library/Statistics and normal Sequence runtime.

Two product choices remain, neither requires a schema redesign:

1. Backdated live Timer whose target already expired under FINISH-at-zero:
   - reject/offer completed history;
   - or explicit continue-overtime override.

2. Whether manual/backdated Sequence creation is required in first release.

After those choices, next DB revision should mainly be implementation cleanup rather than conceptual redesign.

# 53. Final implementation guardrails after DAO/Repository review

These rules are part of the v1 persistence contract even though they do not change table identity.

## 53.1. Snapshot replacement must not run per keystroke

`replaceActivitySnapshot(...)` / `createOrEditSequenceStep(...)` is called only for a completed semantic command.

Do not call it from:
- every `onValueChange`;
- debounce autosave of each TextField;
- focus loss of an individual field.

Recommended UI architecture:

```text
Compose editor
  -> mutable Draft/ViewModel state
  -> Done/Save/Apply
  -> one repository transaction
  -> one replacement snapshot
```

If process-death draft recovery is later required, persist editor drafts separately; do not abuse production snapshots as drafts.

## 53.2. Reference checks are indexed

Immediate orphan pruning is acceptable only if all reference probes are indexed.

Add/confirm indexes:

```text
sequence_nodes(activity_snapshot_id)
sequence_snapshot_nodes(activity_snapshot_id)
plan_entries(activity_snapshot_id)
activity_executions(snapshot_id)
sequence_occurrences(activity_snapshot_id)

plan_entries(sequence_plan_snapshot_id)
sequence_executions(snapshot_id)
```

Reference check should use `EXISTS` / `LIMIT 1`, not row counts.

A repository helper may implement:

```text
hasActivitySnapshotReferences(snapshotId)
hasSequenceSnapshotReferences(snapshotId)
```

The check runs inside the same transaction as the owner-reference swap.

## 53.3. Optional optimization: direct owner knowledge

Most snapshot replacements know the current owner being repointed.

The cleanup path should first test the known previous snapshot after the swap; it does not scan arbitrary snapshots.

Global orphan scanning remains maintenance-only.

# 54. Field display-name read strategy

Do not build one giant Daily query that joins:
- Execution;
- Snapshot;
- Snapshot fields;
- source Template fields;
- Category options;
- Plan;
- Sequence context.

Recommended pattern:

## 54.1. Base execution query

DAO loads execution/history rows and their frozen snapshot-local data needed by the screen.

## 54.2. Batch source metadata query

Collect distinct non-null:

```text
source_field_id
source_option_id
```

and fetch current source metadata in one/batched query:

```sql
SELECT id, name
FROM activity_template_fields
WHERE id IN (...)
```

Analogous query for Category options / Sequence fields.

Avoid N+1 query per Field.

## 54.3. Kotlin resolver

Repository/domain mapper builds:

```text
Map<FieldId, CurrentFieldMetadata>
Map<OptionId, CurrentOptionMetadata>
```

and resolves effective labels:

```text
local override
?? current source label
?? name/label at creation
```

## 54.4. Reactive updates

If a screen must update immediately after global Field rename:
- combine Flow of relevant history/snapshot rows with Flow of source metadata;
- or observe source metadata table separately and remap.

A Field rename may invalidate the metadata Flow without forcing every unrelated semantic Template observer to reload.

For simple historical list rows that do not display custom fields, do not fetch Field metadata at all.

# 55. Plan calendar query contract

The persistence split between floating Day and exact-time Plan is hidden behind `PlanRepository`.

## 55.1. DAO query A — floating Day Plan

For a local date range:

```sql
SELECT ...
FROM plan_entries
WHERE status = 'PLANNED'
  AND precision = 'DAY'
  AND scheduled_instant_ms IS NULL
  AND planned_day BETWEEN :startDate AND :endDate
```

## 55.2. DAO query B — exact-time Plan

Repository converts local calendar window using current device `ZoneId`:

```text
windowStart = startDate at 00:00 in current ZoneId
windowEnd   = dayAfterEndDate at 00:00 in current ZoneId
```

Then DAO:

```sql
SELECT ...
FROM plan_entries
WHERE status = 'PLANNED'
  AND scheduled_instant_ms >= :windowStartInstant
  AND scheduled_instant_ms < :windowEndInstant
```

DST-safe because boundaries are built with `ZoneId`, not by adding fixed 24h milliseconds.

## 55.3. Merge

Repository merges both result sets by effective local date.

Exact-time effective date:

```text
Instant -> current device ZoneId -> LocalDate
```

Floating Day effective date:

```text
planned_day
```

## 55.4. Month dots

Calendar density dots count only day-scoped entries:
- floating Day;
- exact-time.

Do not distribute:
- Week Plan across seven days;
- Month Plan across every day.

Those remain separate higher-level Plan entries.

## 55.5. ZoneId changes

A system timezone change must cause exact-time calendar projection to be rebuilt.

Implementation should have an injectable `ZoneIdProvider` / clock-time context and make PlanRepository re-run window calculation when it changes.

Do not cache month-dot mapping indefinitely by only month key.

# 56. Final implementation readiness

The three final review issues are implementation-shape concerns, not schema blockers:

1. snapshot pruning cost -> controlled by draft/commit boundary + indexes;
2. inherited Field labels -> resolved with batched repository composition, not mega-joins;
3. Plan calendar -> repository merges floating-date and Instant queries using current ZoneId.

No additional product decision is required before coding v1.

The schema is ready to be translated into Room Entities/DAOs.

Deferred immediately-post-v1 features remain:
- parallel live sessions;
- manual/backdated Sequence with explicit child timeline editing.
