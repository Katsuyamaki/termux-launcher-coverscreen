# Implementation Plan: Cover-Screen Launcher Portal + Display-Local App Launching

**Status:** Draft blueprint — modularized for review; no implementation authorized by this document.  
**Target device:** Samsung Galaxy Z Flip7 / FlexWindow (cover display).  
**Primary installed edition:** Termux Launcher Nix (`com.termux.launcher.nix`), while preserving the user's normal Termux installation.  
**V1 boundary:** transparent cover widget + reliable same-display launching.  
**V2 boundary:** accessibility-based auto-launch when the portal widget becomes visible; research only until V1 is stable.

## Overview

This feature turns the Samsung FlexWindow into a low-friction entry surface for the real Termux Launcher rather than recreating the launcher inside an Android widget. V1 adds a full-size, visually transparent FlexWindow AppWidget whose whole usable surface is a tap target that opens the existing `TermuxActivity` on display 1. In parallel, V1 hardens Termux Launcher's own app-launch path so apps launched from Termux Launcher are explicitly requested on the display where the launcher is currently running, instead of leaving task placement unspecified.

The observed pattern where a warm/existing app sometimes reopens correctly on the cover display while a cold launch can show Samsung's “Please open phone to continue” gate is treated as a hypothesis, not a proven root cause. Module M0 captures device evidence before source changes.

## Goals

- Make the Flip7 cover screen feel like a launcher activation surface: navigate to the portal page, see only the normal cover-screen background, tap anywhere in the widget's usable area, and enter the real Termux Launcher.
- Preserve the real terminal, launcher chrome, in-app keyboard, sessions, and launcher behavior by launching `TermuxActivity` itself.
- Make app launching display-local: launcher on display 1 requests child apps on display 1; launcher on display 0 requests display 0.
- Improve cold-launch reliability for FlexWindow-compatible apps without bypassing Android/Samsung display eligibility rules.
- Preserve Nix-edition isolation from the user's normal Termux environment.
- Keep V1 idle overhead effectively zero: no polling, accessibility service, periodic widget refresh, recreated app catalogue, or persistent background service.
- Keep the architecture extensible for a later V2 visibility-detection experiment without coupling V1 to accessibility automation.

## Non-goals

- Rebuild the app drawer, dock, terminal, keyboard, or custom launcher views inside `RemoteViews`.
- Embed `TermuxActivity`, `TerminalView`, or arbitrary custom views into an AppWidget.
- Replace Samsung's FlexWindow home/clock launcher.
- Bypass Samsung's app compatibility policy or Android secondary-display security checks.
- Guarantee every installed app can launch on display 1.
- Implement accessibility auto-launch in V1.
- Add a foreground service, polling loop, wakelock, ongoing notification, or launcher monitor for V1.
- Change normal Termux, edition identity, bootstrap, signing, or Nix packaging as part of this feature.

## Repo Context / Diff Sources

### Direct repo inspection

The current fork was inspected directly at `Katsuyamaki/termux-launcher-coverscreen`.

Confirmed current-state facts:

- `app/src/main/java/com/termux/app/launcher/LauncherAppLauncher.java` launches normal apps through `Context.startActivity(...)` / `Activity.startActivity(...)` without an `ActivityOptions` bundle. Its `LauncherApps.startMainActivity(...)` fallback also passes `null` options. The cloned-profile helper already accepts an optional `Bundle options`, but `launchEntry(...)` currently invokes it with `null`.
- `app/src/main/AndroidManifest.xml` declares `TermuxActivity` as exported, `singleTask`, resizeable, and both `LAUNCHER` and `HOME` capable. No FlexWindow AppWidget provider is currently registered in the inspected manifest.
- The manifest retains the Termux shared-user constraint and explicitly documents why packages sharing that UID must target SDK 28 or lower.
- `gradle.properties` currently has `minSdkVersion=26`, `targetSdkVersion=28`, and `compileSdkVersion=36`.
- The contributor guide identifies the Nix edition application ID as `com.termux.launcher.nix` and treats edition identity changes separately from ordinary feature work.
- The user's fork currently exposes only `main`; this blueprint does not redefine branch/release policy.

### External platform references

Samsung's current FlexWindow documentation specifies:

- standard Android AppWidget metadata plus Samsung metadata named `com.samsung.android.appwidget.provider`;
- Samsung provider XML using `display="sub_screen"`;
- FlexWindow widget sizing around `352dp × 339dp`, `resizeMode="horizontal|vertical"`, and `widgetCategory="keyguard"`;
- `PendingIntent.getActivity(...)` with `ActivityOptions.launchDisplayId`, where cover screen is display 1;
- transparent widget background support.

Android documents `ActivityOptions.setLaunchDisplayId(int)` from API 26 and allows a `RemoteViews` root to use `setOnClickPendingIntent(...)`. Android can still reject a target that is not allowed on the requested display.

Reference URLs:

- https://developer.samsung.com/galaxy-z/flex_window.html
- https://developer.samsung.com/codelab/galaxy-z/widget-flex-window.html
- https://developer.android.com/reference/android/app/ActivityOptions#setLaunchDisplayId(int)
- https://developer.android.com/reference/android/widget/RemoteViews#setOnClickPendingIntent(int,%20android.app.PendingIntent)

### Baseline status

The uploaded implementation-plan template expects a repo snapshot such as `repomix-output.xml` plus incremental `plan.md` diffs. No Termux Launcher repomix snapshot or execution-plan diff was supplied for this fork. Direct inspection is sufficient for blueprint design, but M0 must record a fresh implementation baseline before code edits if the execution workflow requires one.

**Context decision:** blueprint drafting sufficient; execution baseline refresh belongs in M0.

## Risk Rating + Model Routing

- **Risk rating:** `R2 — medium implementation risk`.
- **Difficulty / architecture impact:** moderate. The widget is small, but app placement crosses Android activity/task/profile behavior, Samsung FlexWindow, and PendingIntent semantics.
- **Blast radius:** bounded mainly to launcher start code plus isolated AppWidget resources/manifest registration.
- **Lifecycle/windowing risk:** meaningful. Incorrect fallback ordering could launch on the wrong display, create duplicate tasks, or regress display-0 behavior.
- **Privacy/security risk:** low-to-medium in V1. No new privileged service is required; launch intents and PendingIntents must remain explicit and constrained.
- **Testability / rollback:** good on the user's Flip7; rollback is strong if modules remain isolated and legacy unspecified-display behavior stays a bounded fallback.
- **Recommended implementer:** Android-capable GPT or Claude Sonnet class, high thinking.
- **Recommended reviewer:** frontier-capable reviewer, high thinking, focused on task/display routing, PendingIntent flags, profile paths, and edition isolation.
- **V2:** reassess separately; accessibility/SystemUI visibility detection should be treated at least `R3` until proven stable.

## Current State (Codebase Reality)

### Launcher app-start path

`LauncherAppLauncher.launchEntry(...)` currently resolves an app through multiple fallbacks:

1. cloned-profile main-activity path;
2. package-default launch intent;
3. explicit launcher component;
4. explicit component without launcher category;
5. resolved launcher fallback;
6. `LauncherApps.startMainActivity(...)` fallbacks;
7. shell/`am` fallback for profile launch.

The normal `startActivity` path adds `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_RESET_TASK_IF_NEEDED`, but does not carry a launch-display option. Normal-profile `LauncherApps.startMainActivity(...)` also currently receives `null` options.

### Observed device behavior

- Some applications open correctly from Termux Launcher on the cover display.
- Some launches show Samsung's “Please open phone to continue” message.
- The failure appears more common when there is no existing target task than when the app has already been associated with display 1.

This does **not** prove missing `launchDisplayId` is the only cause. Samsung app eligibility, task reuse, background launch policy, or app-specific secondary-display restrictions may also contribute.

### Widget state

The inspected manifest has no cover-portal provider. Existing `BIND_APPWIDGET` usage for hosting widgets does not itself expose Termux Launcher as a Samsung FlexWindow widget.

## Proposed UX

### V1 cover portal

1. Phone is closed and FlexWindow is active.
2. User navigates to the Termux Launcher portal widget page.
3. The page appears visually empty/transparent; the normal Samsung background remains visible.
4. User taps anywhere in the widget's usable area.
5. The real `TermuxActivity` opens on display 1.
6. The existing terminal, launcher UI, and in-app keyboard appear unchanged.
7. When the user launches a compatible app from Termux Launcher, the launcher requests the display it is currently running on.

### Display-local behavior

- Launcher on display 0 → request target app on display 0.
- Launcher on display 1 → request target app on display 1.
- No reliable source display → preserve legacy unspecified-display behavior instead of guessing.
- Explicit same-display launch rejected → catch/log safely and follow the approved fallback policy; never crash the launcher.

### Important edge cases

- cold target with no existing task;
- warm target already on display 1;
- unsupported/rejected target;
- launcher on display 0;
- non-Activity context where source display cannot be trusted;
- portal tap when `TermuxActivity` already exists as a `singleTask`;
- Samsung host-reserved gesture regions that cannot be made clickable.

## Spec / Contract

### Contract A — display-local app launch

- Derive desired display from the active launcher context/window when reliable.
- Do **not** hard-code display 1 in general launcher code.
- Propagate an `ActivityOptions.setLaunchDisplayId(sourceDisplayId)` bundle through applicable `startActivity` and `LauncherApps.startMainActivity` paths.
- Pass the same options bundle into cloned/work-profile paths where supported.
- Do not add shell display arguments until the target device's exact `am start` support is verified.
- Catch invalid/disallowed-display failures and classify/log them without crashing.
- If legacy unspecified-display fallback remains, make it explicit and observable rather than silently mixed with same-display attempts.

### Contract B — transparent FlexWindow portal

- Register one AppWidget provider with both Android and Samsung FlexWindow metadata.
- Use a transparent root layout filling the widget allocation.
- Render no text, icon, button, terminal, keyboard, screenshot, app grid, or card in V1.
- Bind the whole usable root to one `PendingIntent`.
- Target the app's own existing `TermuxActivity` explicitly.
- Use Samsung's documented PendingIntent `ActivityOptions` requesting display 1.
- Require no `RemoteViewsService`, periodic update, accessibility service, or background service.
- Remain functional after process recreation by rebuilding the `RemoteViews`/PendingIntent normally.

### Contract C — V2 boundary

V2 may investigate AccessibilityService or another local signal to detect the portal page becoming visible and auto-launch Termux Launcher. V1 must not depend on that service, and the V1 tap-anywhere PendingIntent remains the permanent fallback even if V2 later ships.

## Architecture + Design Decisions

### M0 — Evidence and baseline

Purpose: distinguish launcher display-routing defects from Samsung compatibility restrictions before code changes.

Outputs:

- exact source commit/baseline;
- Nix test-binary identity;
- cold vs warm launch matrix for known compatible targets plus at least one rejected/unsupported target;
- source/target display and task-placement evidence;
- classification of the missing-display-target hypothesis as `SUPPORTED`, `PARTIALLY_SUPPORTED`, or `NOT_SUPPORTED`.

### M1 — Display-local launcher core

Purpose: add a single display-aware launch-options seam and propagate it through existing launcher fallbacks.

Design:

- keep policy outside `TermuxActivity` where practical;
- add a small launcher-owned resolver/options helper rather than growing the Activity's public API;
- preserve current fallback ordering unless evidence proves ordering itself is part of the bug;
- support null/no-options mode for controlled legacy fallback.

### M2 — Transparent FlexWindow portal

Purpose: expose Termux Launcher as an invisible Samsung cover widget that opens the real launcher.

Expected component family:

- `CoverLauncherWidgetProvider` or equivalent;
- one transparent `RemoteViews` layout;
- one standard Android AppWidget provider XML;
- one Samsung FlexWindow provider XML;
- manifest receiver registration;
- minimal picker-label resource if needed.

Do not add a proxy Activity unless direct PendingIntent launching fails in device proof.

### M3 — V1 integration/hardening

Purpose: prove portal + display-local child app launching as one end-to-end flow, close task/profile/fallback regressions, and document platform limitations.

### M4 / V2 — visibility auto-launch research

Purpose: research only. Determine whether Samsung SystemUI emits a stable accessibility/window signal that uniquely identifies the portal widget page becoming active on display 1.

Any future auto-launch design must include:

- phone-closed/display-1 guards;
- FlexWindow-host check;
- portal-page identity check;
- launcher-not-already-foreground check;
- debounce/cooldown/loop prevention;
- permanent V1 manual-tap fallback.

### Tradeoffs

1. **Transparent portal vs recreated launcher widget** — choose transparent portal to preserve the actual terminal/keyboard/launcher and avoid divergent UI.
2. **Same-display policy vs hard-coded display 1** — choose same-display for child apps; only the cover portal itself hard-codes display 1.
3. **Direct PendingIntent vs proxy Activity** — choose Samsung's documented direct PendingIntent first; proxy is evidence-driven fallback only.
4. **V1 tap vs accessibility auto-launch** — choose documented tap interaction for V1; isolate visibility automation as V2 research.

## File Change List (Expected)

### Existing files likely updated

- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/termux/app/launcher/LauncherAppLauncher.java`

### Possible new M1 helper/test files

- `app/src/main/java/com/termux/app/launcher/LauncherDisplayTargetResolver.java` or equivalent
- focused tests under the matching `app/src/test/java/.../launcher/` package

### Expected M2 files

- `app/src/main/java/com/termux/app/launcher/cover/CoverLauncherWidgetProvider.java`
- `app/src/main/res/layout/widget_cover_launcher_portal.xml`
- `app/src/main/res/xml/cover_launcher_widget_info.xml`
- `app/src/main/res/xml/samsung_cover_launcher_widget_info.xml`
- `app/src/main/res/values/cover_launcher_strings.xml` if needed

### Explicitly not expected in V1

- no `RemoteViewsService`;
- no AccessibilityService;
- no terminal/keyboard duplicate UI;
- no persistent background service;
- no edition identity rewrite.

## Phased Delivery Map

| Module | Scope | User-visible change | Primary gate | Exit criteria | Backout |
|---|---|---|---|---|---|
| M0 — Evidence/baseline | Source snapshot + Flip7 cold/warm matrix + display/task logs | None | Repro/evidence | Assumptions classified proven/unproven | No code changes |
| M1 — Display-local launch | Derive source display; propagate `ActivityOptions` | Child apps preferentially stay on launcher display | Display-1 cold/warm + display-0 regression | Known compatible apps launch on current display; unsupported apps fail safely | Revert options path to legacy behavior |
| M2 — Transparent portal | FlexWindow widget + transparent full-area click + display-1 `TermuxActivity` PendingIntent | Cover page looks like background; tap opens real launcher | Widget discovery/transparency/tap smoke | Widget selectable, transparent, repeatably opens display 1 | Remove isolated provider/resources |
| M3 — V1 hardening | Task reuse, repeat loop, edition isolation, fallback/profile review | Reliable portal → launcher → app flow | Full V1 matrix | Mandatory V1 goals pass or platform limitation explicitly accepted | Revert bounded hardening changes |
| M4 — V2 research | Accessibility/SystemUI visibility study | None in research phase | Stable event identity | Written GO/NO-GO/needs-more-evidence | Drop V2; V1 remains complete |

## Workflow Benchmark Contract Adaptation

The uploaded implementation-plan template defines a DroidOS-specific benchmark/result-file contract. This repo's inspected durable-doc workflow does not establish that same harness, so this blueprint does **not** invent one. It instead defines stable smoke-goal IDs and evidence requirements. If this repository later adopts benchmark automation, these IDs should map into it without changing the behavioral contract.

Canonical build/test commands remain owned by the repository's current `AGENTS.md`; this durable plan intentionally does not duplicate branch/release/build instructions that the repo says should have one authority.

### Mandatory smoke goals

| Goal ID | Module | Required | Scenario | Evidence | Pass criteria |
|---|---|---:|---|---|---|
| `COVER-M0-001` | M0 | yes | Cold vs warm launches from display 1 | task/display log + observation | Source display, target result, and Samsung gate result captured |
| `COVER-M1-001` | M1 | yes | Cold-launch known FlexWindow-compatible app from launcher on display 1 | task/display log + observation | Target created on display 1 without launcher crash |
| `COVER-M1-002` | M1 | yes | Warm/reopen compatible app | task/display log + observation | Existing task reused/resumed correctly on display 1 |
| `COVER-M1-003` | M1 | yes | Launch compatible app from launcher on display 0 | task/display log + observation | Target stays on display 0; no hard-coded cover routing |
| `COVER-M1-004` | M1 | yes | Attempt target rejected for display 1 | log + observation | Launcher remains stable; rejection/fallback explicit; no bypass |
| `COVER-M2-001` | M2 | yes | Enable widget under Cover screen → Widgets | screenshot/observation | Portal is discoverable and enableable |
| `COVER-M2-002` | M2 | yes | Navigate to portal | screenshot/observation | No app-rendered visible content; host background remains visible as far as One UI permits |
| `COVER-M2-003` | M2 | yes | Tap center/edges of usable widget | task/display log + observation | Tap opens/resumes Termux Launcher on display 1 |
| `COVER-M2-004` | M2 | yes | Repeat leave → portal → tap | repeated observation | No stale PendingIntent or service dependency |
| `COVER-M3-001` | M3 | yes | Portal → launcher → compatible cold app | combined evidence | Launcher and target remain on display 1 as intended |
| `COVER-M3-002` | M3 | yes | Nix coexistence regression | package/build/install observation | Normal Termux remains separate; Nix launcher identity unchanged |
| `COVER-M3-003` | M3 | yes | Idle portal behavior | process/log observation | No polling, foreground service, recurring update, or accessibility service in V1 |
| `COVER-M4-001` | M4 | research | Swipe onto portal without tapping | accessibility/window-event capture | Stable unique visibility signal found or NO-GO recorded |

## Phase Details

### M0: Evidence + Implementation Baseline

**Objective**

Turn the current cover-launch theory into measured facts before source changes.

**Included**

- exact source commit/baseline;
- Nix test APK identity/build path;
- cover/main display IDs;
- cold/warm target matrix;
- task/activity placement logs;
- Samsung rejection observations.

**Excluded**

- source edits;
- widget implementation;
- accessibility automation.

**Test gate**

Run `COVER-M0-001` with at least one known FlexWindow-compatible target and one rejected/unsupported target so a Samsung policy rejection is not mistaken for a launcher routing bug.

**Exit criteria**

- hypothesis classified `SUPPORTED`, `PARTIALLY_SUPPORTED`, or `NOT_SUPPORTED`;
- platform restrictions documented separately from launcher placement behavior;
- implementation baseline recorded.

### M1: Display-Local Launcher Core

**Objective**

Make app starts explicitly target the display hosting Termux Launcher whenever that display can be resolved reliably.

**Included**

- display resolver/options helper;
- propagation into normal `startActivity`, `LauncherApps.startMainActivity`, and profile APIs where supported;
- safe fallback/error logging.

**Excluded**

- FlexWindow widget;
- accessibility automation;
- unrelated UI/refactors;
- unsupported-app bypasses.

**Design notes**

- Prefer a launcher-local helper/seam over adding another broad public `TermuxActivity` method.
- Preserve launch fallback ordering unless M0 evidence requires an intentional change.
- Shell profile fallback display targeting remains `TBD_PENDING_DEVICE_CLI_PROOF`.

**Test gate**

`COVER-M1-001` through `COVER-M1-004`.

**Exit criteria**

Compatible cold/warm apps follow the source display, display 0 is not regressed, and rejected apps fail safely.

### M2: Transparent FlexWindow Portal

**Objective**

Add the visually empty full-area cover widget that opens the real launcher on display 1.

**Included**

- AppWidget provider;
- transparent root layout;
- Android provider XML;
- Samsung `sub_screen` provider XML;
- direct Activity PendingIntent;
- minimal widget-picker identity.

**Excluded**

- app grid;
- terminal preview;
- keyboard;
- search;
- collection service;
- periodic updates;
- proxy Activity unless direct launch is proven insufficient.

**Design notes**

- Use Samsung's documented `PendingIntent.getActivity(... ActivityOptions(...launchDisplayId=1).toBundle())` pattern.
- If One UI adds unavoidable host chrome, document it rather than faking transparency with a wallpaper screenshot.
- Swipes remain FlexWindow navigation; ordinary taps in usable widget area launch Termux Launcher.

**Test gate**

`COVER-M2-001` through `COVER-M2-004`.

**Exit criteria**

Portal is available on Flip7, visually transparent, full-area clickable where the host permits, and repeatedly opens/resumes `TermuxActivity` on display 1.

### M3: V1 Integration + Hardening

**Objective**

Prove the complete cover flow and close regressions involving task reuse, fallback behavior, profiles, and Nix coexistence.

**Scope rule**

Any new source change beyond M1/M2 must be justified by a failing mandatory smoke goal.

**Test gate**

`COVER-M3-001` through `COVER-M3-003` plus applicable M1/M2 regressions.

**Exit criteria**

All mandatory V1 goals pass, or a platform limitation is explicitly accepted with device evidence.

### M4 / V2: Accessibility Visibility Auto-Launch Research

**Objective**

Determine whether the remaining portal tap can be removed reliably by detecting that the portal page became visible.

**Included**

- event capture/research;
- Samsung SystemUI accessibility/window-state analysis;
- debounce/loop-prevention design.

**Excluded**

- shipping an AccessibilityService before a stable signal is proven.

**Test gate**

`COVER-M4-001`, including negative swipes to neighboring widgets and repeated back/forward navigation to measure false positives.

**Exit criteria**

Written `GO`, `NO_GO`, or `NEEDS_MORE_EVIDENCE` decision. A separate reviewed implementation plan is required before any V2 production service is written.

## Rollout / Backout

### V1 rollout

- Finish M0 before source edits.
- Validate M1 independently before relying on it for portal child-app launches.
- Implement M2 only after the direct display-1 PendingIntent contract is device-proven.
- Run M3 end-to-end before treating the portal as stable.
- Keep V2 absent/disabled in V1.

### Backout

- M1 can return to the current unspecified-display path if same-display options regress normal launcher behavior.
- M2 can be removed by unregistering the provider and deleting isolated resources without changing `TermuxActivity` UI.
- If direct PendingIntent is unreliable, stop and review evidence before adding a proxy Activity.
- V2 research can be abandoned with no V1 impact.

## Build Variants / Binary Scope

- Primary target: Nix edition (`com.termux.launcher.nix`) coexisting with normal Termux.
- The feature must not alter Nix application ID/shared-user/bootstrap identity.
- Exact branch/build mechanics for the Nix test APK must be recorded in M0 because the current user fork exposes only `main` while repository guidance treats edition identity separately.
- If shared source causes the widget to appear in other editions, that exposure must be an explicit M2 decision rather than an accident.
- `targetSdkVersion=28` remains unchanged; this feature does not authorize a shared-user/target-SDK architecture change.

## Open Questions (must resolve before the affected module is coded)

1. **M0 — root-cause confidence:** Does explicit same-display targeting remove cold-launch “Please open phone to continue” cases for apps already known to be FlexWindow-compatible, or are some failures independent Samsung policy gates?
2. **M1 — source display resolver API:** Which min-SDK-safe framework path best derives the active Activity/display ID without adding a broad new `TermuxActivity` seam? Compile-check rather than relying on memory.
3. **M1 — fallback policy:** If explicit same-display launch is rejected, should the launcher automatically retry legacy unspecified-display launch, or fail rather than risk jumping to the main screen?
4. **M1 — shell/profile fallback:** Does the Flip7's exact `am start` environment support the desired display option? Do not implement until verified.
5. **M2 — edition exposure:** Should the portal provider ship only in the Nix edition or all Termux Launcher editions built from shared source?
6. **M2 — host transparency:** Does Flip7/One UI render a truly chrome-free transparent third-party widget in the user's cover layout, or add unavoidable host treatment?
7. **M2 — `singleTask` behavior:** Does a display-1 PendingIntent reliably move/resume an existing `TermuxActivity` task in all relevant states? If not, review before adding a proxy.
8. **M4/V2 — visibility signal:** Is there a stable accessibility/window event that uniquely identifies the portal page becoming selected without reading unrelated UI content?

Open questions block only their affected module. M0 may begin as evidence gathering without resolving later-module questions.

## Security + Data Handling Risks

- V1 introduces no network transport and should store no new user data.
- Launch diagnostics may contain package/component names, display IDs, and task state; avoid terminal/session content.
- Portal PendingIntents must be explicit and target the app's own known launcher component.
- Do not accept arbitrary externally supplied components or display IDs.
- V2 has materially higher privacy/permission risk because accessibility can observe other UI; a separate threat-model review is mandatory before implementation.

## Observability + Ops Readiness

Suggested stable debug markers, exact names subject to execution-plan review:

- `COVER_LAUNCH_SOURCE_DISPLAY`
- `COVER_LAUNCH_TARGET_DISPLAY`
- `COVER_LAUNCH_PATH`
- `COVER_LAUNCH_RESULT`
- `COVER_PORTAL_WIDGET_UPDATE`
- `COVER_PORTAL_PENDING_INTENT_READY`

Requirements:

- rate-limit/debug-gate noisy launch diagnostics;
- log result/fallback classification, not stack traces alone;
- never log terminal text, keyboard text, shell command content, or secrets;
- preserve enough evidence to distinguish wrong-display placement from Samsung rejection.

## Performance + Resource Budgets

- Portal idle CPU: effectively zero; no polling/timers/services.
- Portal steady-state memory: normal AppWidget/RemoteViews metadata only; no icon catalogue, bitmap cache, or terminal snapshot.
- App-launch overhead: one display-resolution/options construction step; no blocking package scan added to the click path.
- No continuously repainting animation.
- No new long-lived bitmap ownership.
- V2 performance budget remains undefined until a viable event signal exists; polling is not an acceptable default.

## Compatibility / Migration Notes

- Users who never enable the FlexWindow widget should see no portal UI change.
- Display-0 launcher behavior must remain compatible.
- `targetSdkVersion=28` remains unchanged.
- Nix and normal Termux coexistence remains unchanged.
- V1 requires no data/schema migration.
- Downgrade/backout removes the portal and returns app placement to prior behavior without persistent-data cleanup.

## Accessibility + i18n

### V1

- Widget body contains no visible text, so there is no text-scaling/localization burden there.
- Widget picker label should reuse/localize a normal product string if exposed broadly.
- A visually invisible tappable widget can confuse TalkBack users; before broad distribution, decide whether the root should expose an accessibility description such as “Open Termux Launcher” while remaining visually transparent.
- Do not make the surface inaccessible merely to preserve the visual illusion.

### V2

AccessibilityService use would be privileged automation, not an accessibility feature. Its purpose and scope must be disclosed if ever shipped.

## Documentation + Support Notes

If V1 ships beyond the developer's own build, document:

- how to enable the widget under Samsung Cover screen → Widgets;
- that the portal is intentionally transparent;
- that tapping it opens the real Termux Launcher;
- that Samsung/Android may still reject apps not allowed on the cover display;
- how to distinguish “portal did not open Termux Launcher” from “Termux Launcher opened but child app was rejected”;
- that V2 auto-launch is not part of V1.

Do not duplicate canonical build/release instructions from `AGENTS.md` in this durable plan.

## Review / Approval Gates

Before implementation:

1. Run the user's implementation-plan review workflow against this blueprint.
2. Resolve or explicitly accept open questions for the module being authorized.
3. Confirm the M0 source/device baseline.
4. Approve one module at a time; M1 and M2 should not be bundled into a large first patch.
5. V2 requires a separate post-V1 review even if M4 research returns GO.

## Next-Phase Implementation Respin

**Recommended first phase:** M0 evidence/baseline, followed by M1 display-local launch core.  
**Complexity:** medium.  
**Risk:** R2 for V1.  
**Respin status:** compact respin recommended before M1 source edits.

### Next Phase Scope

- Capture current source commit and Nix test-build identity.
- Reproduce cold/warm cover launches and record display/task behavior.
- Finalize same-display fallback policy from evidence.
- Then implement only M1.

### Forbidden Scope for First Source Patch

- no FlexWindow widget yet;
- no accessibility service;
- no terminal/keyboard UI changes;
- no edition identity changes;
- no unrelated launcher refactor.

### Validation Gates

- mandatory M0 evidence exists;
- M1 patch has focused tests for any pure resolver/options policy added;
- device smoke proves both display 1 and display 0 behavior;
- unsupported-app rejection cannot crash the launcher.

### Backout

Revert M1 helper/option propagation and restore the current unspecified-display path. Because V1 stores no new persistent state, backout should require no migration or cleanup.
