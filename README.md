# Things to Do Next Planner

A native Android to-do list and schedule planner built with Kotlin, Jetpack Compose and Material 3.
It works fully offline (Room is the source of truth). It also has a home-screen widget, an
offline planner and an optional AI planner, optional Google Drive sync, and a Wear OS companion.

| Module | What it is |
|---|---|
| `core/` | Pure Kotlin (JVM) logic shared by everything: recurrence engine, reminder planner, offline planner, AI-response validator, sync merge (HLC + tombstones), progress stats, JSON codec, Wear payload. No Android dependencies, fully unit-tested. |
| `app/` | Phone app: Compose UI, Room, DataStore, AlarmManager reminders, Glance widget, Drive sync, WorkManager, Wear Data Layer bridge. |
| `wear/` | Wear OS companion: today's tasks, upcoming deadlines, one-tap complete, "updated at" status. |
| `backend/` | Small Node.js AI planner proxy. Keeps provider keys server-side and validates requests and responses. No npm dependencies. |
| `docs/` | [Recurrence rules](docs/RECURRENCE.md) and [sync and conflict rules](docs/SYNC.md). |

## Features

- **Tasks**: create, edit, complete/reopen (with undo), duplicate, move, star, reschedule, and
  delete (with undo). Each task has a title, notes, list, priority, star, subtasks, a due date
  with optional time (or all-day, or undated), multiple reminders and an estimated duration.
  Quick capture needs only a title, and you can also dictate it with the system speech
  recognizer.
- **Views**: Inbox, Today (with Overdue and Done sections), Upcoming, Starred, Completed, and one
  view per list. Search covers titles, notes, subtasks and list names. You can filter by list,
  date, priority and status.
- **Lists**: tap **New List**, enter a name, then optionally pick a color and icon and save.
  Optional starter lists: Personal, Work, Study, Fitness, Travel, Wishlist, Family events.
- **Focus view**: shows three priority tasks with large checkboxes and one-tap Tomorrow /
  Reschedule. It can be set as the default view.
- **Recurrence**: daily, weekdays, weekly, monthly, yearly and custom (every N days, weeks,
  months or years, on chosen weekdays). A series can end on a date or after N occurrences.
  Edits and deletes apply to **This occurrence**, **This and future occurrences**, or all.
  Completion history is kept per occurrence. See [docs/RECURRENCE.md](docs/RECURRENCE.md).
- **Reminders**: local notifications with **Complete** and **Snooze** actions, three sound
  channels (default, silent, alarm) plus per-channel custom sounds, and at-time or before-time
  offsets. Alarms are restored after reboot and recalculated after time-zone or clock changes.
  Daylight saving time is handled, and alarm keys are deduplicated. Exact alarms are used where
  allowed; when they aren't, timing is clearly labelled approximate in Settings.
- **Calendar**: day, week and month views with all-day and timed tasks. Tap a task to open it.
  **+** prefills the selected date.
- **Widget** (Jetpack Glance): resizable. Shows today's and overdue tasks with checkboxes and a
  + quick-add button. It refreshes after every change and at midnight.
- **Mine**: today's and this week's completion counts, overdue tasks, and progress by list.
  Recurring tasks count each occurrence separately.
- **Appearance**: system, light or dark mode. Accent colors include wallpaper (dynamic) colors.
  Text is readable, touch targets are at least 48 dp, and every control has a label. Priority
  is always shown with `!`/`!!`/`!!!` text as well as color.
- **Planner**: type a goal, paste a rough list, or describe your availability. You get an
  editable draft with tasks, subtasks, durations, priorities and time slots. Overlaps with
  scheduled tasks, other suggestions, deadlines and your availability are flagged. You can
  untick, rename, retime or resize any item before saving. The **offline planner** always works.
  The **AI planner** goes through your backend, and you approve the exact payload before it's
  sent. Every draft is labelled with its source.
- **Sync and backup**: optional Google sign-in with Drive **app-data** scope. Includes **Back up
  now**, **Restore**, automatic sync and a last-sync status. Stable IDs, HLC versions and
  tombstones prevent duplicates and stop deleted tasks from coming back. You can turn reminders
  off per device. Local backup files can be exported and imported through the system file picker.
- **Wear OS**: today's tasks, upcoming deadlines, and one-tap complete. Completions made offline
  are queued and delivered when the phone reconnects. The watch shows when it was last updated.

### Permissions

| Permission | Why | When requested |
|---|---|---|
| `POST_NOTIFICATIONS` | Show reminders (Android 13+) | When you add a reminder or turn reminders on |
| `SCHEDULE_EXACT_ALARM` | On-time reminders | Never forced. Settings offers "Allow exact reminders"; without it, reminders are approximate. |
| `RECEIVE_BOOT_COMPLETED` | Restore reminders after reboot | Install time (normal permission) |
| `INTERNET` | Drive sync, AI planner and ads | Install time (normal permission) |
| `AD_ID` (added by the ads SDK) | Advertising ID for AdMob | Install time (normal permission) |

The app requests **no** storage, calendar or microphone permissions:
- Backups use the system file picker (`CreateDocument` / `OpenDocument`).
- "Add to device calendar" opens your calendar app's insert screen. In-app calendars never need
  device calendar access.
- Voice capture uses the system speech recognizer activity.

If you deny a permission, the related feature falls back to manual use: tasks still save,
reminders still show in the app, and the planner still works offline.

## Android Studio setup

1. Install **Android Studio** (Narwhal 2025.1 or newer) with **JDK 17+**, Android SDK Platform
   **36** and a Wear OS system image if you want to test the watch app.
2. Open the repository root. Android Studio creates `local.properties` with `sdk.dir`.
3. Optional: copy `local.properties.example` to `local.properties` and fill in `AI_BACKEND_URL` /
   `AI_APP_TOKEN` (see below). Never commit `local.properties`.
4. Run the `app` configuration on a phone or emulator (API 26+). Run `wear` on a Wear OS emulator
   (API 30+).

### Build commands

```bash
./gradlew :core:test                    # shared logic unit tests (recurrence, DST, sync, planner…)
./gradlew :app:testDebugUnitTest        # app JVM unit tests (task views, filters, focus)
./gradlew :app:connectedDebugAndroidTest  # instrumented: Room repository, alarms, sync merge (needs a device/emulator)
./gradlew :app:lintDebug :wear:lintDebug
./gradlew :app:assembleDebug            # → app/build/outputs/apk/debug/app-debug.apk
./gradlew :wear:assembleDebug           # → wear/build/outputs/apk/debug/wear-debug.apk
./gradlew :app:installDebug :wear:installDebug
cd backend && npm test                  # AI backend tests (Node 20+)
```

CI (`.github/workflows/android.yml`) runs the unit tests, builds both debug APKs (uploaded as
the `debug-apks` artifact), runs lint and runs the backend tests on every push.

## Google Drive sync configuration

The app uses Google Identity Services' `AuthorizationClient` with the
`https://www.googleapis.com/auth/drive.appdata` scope. **There's no client secret or JSON
file in the app.** Google identifies the app by its package name and signing certificate.

1. In [Google Cloud Console](https://console.cloud.google.com/), create a project or pick one.
2. **APIs & Services → Library → Google Drive API → Enable**.
3. **OAuth consent screen**: choose External, fill in the app name and support email, and add
   the scope `.../auth/drive.appdata`. While the app is in *Testing*, add your Google account
   under **Test users**.
4. **Credentials → Create credentials → OAuth client ID → Android**:
   - Package name: `com.robertlangat.todoplanner`
   - SHA-1: run `./gradlew :app:signingReport` and copy the **debug** variant's SHA-1. Create a
     second Android client with your **release** keystore's SHA-1 before publishing.
5. Install the debug APK, then open **Mine → Settings → Google Drive sync → Sign in with Google**.

Errors like `DEVELOPER_ERROR` or code 10 mean the SHA-1 or package name doesn't match an Android
OAuth client. If you see "access blocked", add your account as a test user.

## AI planner backend configuration

The app never holds a model-provider key. It calls `POST {AI_BACKEND_URL}/v1/plan`, and
`backend/server.mjs` adds the key server-side.

```bash
cd backend
cp .env.example .env            # never commit .env
# Local testing without any key:
AI_PROVIDER=mock npm start      # → http://localhost:8787  (GET /healthz)
# Real model:
AI_PROVIDER=anthropic ANTHROPIC_API_KEY=<your-key> AI_MODEL=claude-sonnet-5-5 npm start
```

- **Emulator**: set the URL to `http://10.0.2.2:8787` (debug builds allow plain HTTP only to
  `10.0.2.2` and `localhost`).
- **Physical device**: run `adb reverse tcp:8787 tcp:8787`, then use `http://localhost:8787`.
- **Production**: deploy `backend/` to any Node 20+ host with HTTPS (Cloud Run, Fly.io, Render…).
  Set the env vars as host secrets and use the `https://` URL.
- Set the URL at build time (`AI_BACKEND_URL` in `local.properties`) or at runtime (Settings →
  AI planner). Then turn on **Enable AI planner**.
- `APP_SHARED_TOKEN` / `AI_APP_TOKEN` is an optional gate against casual abuse. It can be pulled
  out of the APK, so use the backend's rate limit (`RATE_LIMIT_PER_MINUTE`) and the provider's
  spend limits as your real protection.

Request and response contract: see the comments at the top of `backend/server.mjs` and
`core/.../planner/AiPlanValidator.kt`. The backend validates the request and sanitises the
model output. The app validates again (clamps durations, drops untitled items, re-checks
overlaps). Timeouts and errors show a message and an **Use offline planner** button, and your
tasks are never touched.

## AdMob ads

| | ID |
|---|---|
| App ID | `ca-app-pub-8306955081407788~5196832723` (in the manifest via `manifestPlaceholders`) |
| Banner | `ca-app-pub-8306955081407788/1308277343` |
| Interstitial | `ca-app-pub-8306955081407788/1257587713` |

- **Debug builds show Google's test ads** (labelled "Test Ad"), so testing never counts as
  invalid traffic on your AdMob account. Release builds use the real units. To try the real units
  in a debug build, run `./gradlew :app:assembleDebug -PrealAds=true`, and never tap your own
  live ads. Register your phone as a test device in AdMob → Settings → Test devices.
- **Placement**: an anchored adaptive banner above the bottom navigation on Tasks, Calendar and
  Mine. Interstitials appear only after you save a task or a plan, at most once every 3 minutes,
  on every 3rd save, and never in the first minute. There are no ads in the widget, on the watch,
  at launch or when ticking checkboxes.
- **Consent**: Google's User Messaging Platform shows the GDPR / US-state consent form where the
  law requires it, and ads load only after consent allows. Create the message in **AdMob →
  Privacy & messaging** (European regulations, and US states if you want). When required,
  Settings shows **Ad privacy options**.
- **Before publishing**: add `app-ads.txt` to your developer website and link your app in AdMob
  after it's on Google Play. In Play Console, declare ads and the Advertising ID (`AD_ID`
  permission, added by the SDK) in the Data safety form.

## Wear OS testing

1. In Android Studio's **Device Manager**, create a Wear OS emulator (API 30+) and a phone
   emulator with Google Play.
2. Pair them: **Device Manager → ⋮ on the Wear emulator → Pair Wearable** (or use the Wear OS
   app on the phone emulator).
3. Install both apps signed with the **same debug key** (they share the applicationId
   `com.robertlangat.todoplanner`):
   `./gradlew :app:installDebug :wear:installDebug`
4. Add a task due today on the phone. The watch's **Today** list updates in a few seconds and
   shows "Updated hh:mm".
5. Tick it on the watch. The phone marks that occurrence complete, and the widget and reminders
   update. To test offline queueing, turn on airplane mode on the watch, tick a task (it shows
   "1 waiting to sync"), then reconnect.

A physical watch needs a phone with the Wear OS / Pixel Watch app paired to it, and both APKs
sideloaded with the same key.

## Manual test checklist (device needed)

Automated tests cover the logic (see below). These steps check the platform behaviour on a device:

| Scenario | Steps |
|---|---|
| Task creation and completion | Add from the FAB, from Calendar (the date is prefilled) and from the widget **+**. Complete, then Undo. |
| Monthly and yearly | Create "Pay rent" monthly starting on the 31st and check the Feb/Apr dates in Calendar → Month. Create a yearly birthday on Feb 29. |
| Snooze | Add a reminder 2 minutes ahead and tap **Snooze** on the notification. Check it fires again after the snooze length and only once: `adb shell dumpsys alarm \| grep -c todoplanner` |
| Reboot recovery | Schedule a reminder, run `adb reboot`, and wait for it to fire. |
| Time-zone change | Settings → System → Date & time → change the zone. A 09:00 task should still ring at 09:00 local. |
| Exact vs approximate | `adb shell appops set com.robertlangat.todoplanner SCHEDULE_EXACT_ALARM deny`, then open Settings → Reminders. It should show "approximate". |
| Widget completion | Add the widget, tick a task, and check the app updates. Leave it past midnight and check the list rolls over. |
| Offline restore | Back up, enable airplane mode, then tap Restore. You should see an error and your tasks unchanged. Reconnect and Restore again. |
| Two-device sync | Sign in with the same account on two devices. Delete a task on A and edit it on B while B is offline. Sync both: the task stays deleted. |
| Recurring edit | Edit a daily task with a reminder using **This and future**. `dumpsys alarm` should show one alarm per occurrence. |

## Automated tests

- `core` (42 tests, run with `./gradlew :core:test`): monthly-31st and Feb-29 rules, weekday and custom-weekly rules,
  COUNT/UNTIL/EXDATE, series splitting, DST gap and overlap, time-zone change, reminder
  dedupe/cancel/snooze, missed-alarm catch-up, sync merge (union, deletion wins, undo restore,
  LWW tie-break, idempotence, tombstone purge, HLC skew), offline planner, rough-text parser, AI
  validator, the backend→app contract, and the Wear payload.
- `app` JVM (5 tests): Today/Upcoming/Inbox/Starred/Completed views, search, filters, Focus view.
- `app` instrumented (6 tests): Room repository create/complete/undo/delete, per-occurrence
  completion, recurring edits without duplicate reminders, synced deletion staying deleted,
  restore without duplicates, and real AlarmManager reconcile idempotence including reboot-style
  rebuild and snooze.
- `backend` (5 tests): request validation, response sanitising, JSON extraction, auth, errors and
  rate limiting.

## Project layout

```
app/src/main/java/com/robertlangat/todoplanner/
  data/       Room entities/DAOs, TaskRepository (all writes), SettingsRepository (DataStore), SyncMapping
  domain/     view models of tasks/occurrences
  reminders/  ReminderScheduler (AlarmManager reconcile), receivers, notification channels
  widget/     Glance widget + complete action
  sync/       DriveAuth, DriveClient (REST), SyncManager, SyncWorker
  planner/    AiPlannerClient
  wear/       PhoneWearBridge + listener service
  ui/         tasks, editor, calendar, mine, planner, settings, theme, components
```

## Secrets

Nothing secret is committed. `local.properties`, `.env`, keystores and `google-services.json` are
git-ignored. Configuration is documented with placeholders in `local.properties.example` and
`backend/.env.example`.
