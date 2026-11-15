# Recurrence and reminder rules

All rules live in `core/` (pure Kotlin, unit-tested) and are shared by the phone app, the widget,
Wear OS and sync. Tests: `core/src/test/.../RecurrenceTest.kt` and `ReminderPlannerTest.kt`.

## Occurrences are dates, not instants

A task stores a **date** (and an optional **wall-clock time**), never a UTC instant. An occurrence is
a calendar date. The time of day is applied afterwards, in the device's current time zone.

- A task at 09:00 stays at 09:00 local after you fly from Nairobi to London.
- An all-day task is due on a date. Its reminders fire at your **all-day reminder time**, which
  defaults to 09:00 and can be changed in Settings.

## Frequencies

| Rule | Behaviour |
|---|---|
| Daily, every N days | start + k·N days |
| Every weekday | Mon–Fri. Saturdays and Sundays are skipped. Interval is ignored. |
| Weekly / custom weeks | Every N weeks, on the chosen weekdays (default: the start date's weekday). Weeks are counted from the Monday of the start week. Dates before the start are never produced. |
| Monthly | Every N months on the start date's day of month. **If a month is too short, the date moves to the last day of that month.** The anchor never drifts. A rule starting Jan 31 gives Jan 31, Feb 28 (Feb 29 in leap years), Mar 31, Apr 30, … |
| Yearly | Every N years on the start month/day. **Feb 29 falls on Feb 28 in non-leap years.** This is the rule birthdays and anniversaries use. |

## Ends

- **Never**
- **Until date**: inclusive.
- **After N occurrences**: COUNT. As in RFC 5545, excluded dates still count, so deleting or
  detaching one occurrence never pushes the series end later.

## Exceptions and edits

- **This occurrence**: the date is added to the series' `EXDATE` list. A standalone task (with
  `seriesParentId` set) is created with the edits. Its completion state carries over.
- **This and future occurrences**: the series is split. The original ends the day before
  (`UNTIL = d-1`, or `COUNT` = the occurrences before d), and a new series starts at d. For COUNT
  rules, the remaining count moves to the new series, so the total stays the same. Completion
  history on or after d moves to the new series.
- **All occurrences**: the series is rewritten in place. Completion rows that no longer match an
  occurrence get a tombstone.
- **Delete** follows the same three scopes.

## Completion history

Each completed occurrence is its own row (`occurrences` table, id `taskId@yyyy-mm-dd`). Completing
Monday doesn't complete Tuesday. Progress on the Mine screen counts occurrences individually.

## Reminders

- Offsets are "minutes before", and 0 means at the time. They are subtracted on the real
  timeline, so "15 min before 09:00" is 15 real minutes before.
- **DST gap**: a time that doesn't exist that day (e.g. 01:30 on spring-forward day) fires at
  the first valid time after the gap (02:30).
- **DST overlap**: a time that happens twice fires at the first one.
- **Time-zone or clock change, reboot, app update, or exact-alarm permission change**: all alarms
  are recomputed from the database and re-registered.
- **No duplicates**: every alarm has a deterministic key `taskId@date#offset`. Snoozes use
  `taskId@date#snooze`. The key goes in the PendingIntent's data URI, so scheduling the same key
  again replaces the existing alarm. `ReminderPlanner.diff` cancels any key no longer wanted.
  This covers deletion, completion, edits, splits, restores and syncs.
- **Missed reminders**: an alarm missed while the phone was off fires once after boot, if it was
  due within the last 15 minutes. Delivered keys are remembered so it can't fire twice.
- **Horizon**: alarms are registered for the next 35 days, at most 200 of them. The list is topped
  up on every change and at each midnight rollover.
- **Exact vs approximate**: exact alarms are used when Android allows them (on Android 12+ the
  user grants "Alarms & reminders"). Otherwise inexact `setAndAllowWhileIdle` is used, and
  Settings → Reminders says that timing is approximate.
