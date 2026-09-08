# Implementation Plan: Launcher Portal

**Status:** V1 approved for implementation after product-direction change.  
**Product:** standalone Android APK, independent of Termux Launcher.  
**Working repo name:** `Launcher-Portal` (display name: Launcher Portal).  
**Primary device:** Samsung Galaxy Z Flip7 / One UI FlexWindow cover display.  
**V1 core:** configurable transparent portal widgets + direct cover-display launching + built-in Samsung Recents portal.  
**V1 optional enhancement:** user-enabled AccessibilityService for auto-open, dwell/loading behavior, and Recents fallback only.  
**V2:** broader gesture automation and richer portal layouts only after V1 device proof.

## Direction Change

The earlier blueprint treated the transparent cover portal as a feature inside the Termux Launcher fork. That is no longer the product direction.

Launcher Portal is now a standalone APK whose widget instances each point at a destination selected by the user. Termux Launcher becomes only one possible destination. A user can place several portal widgets on the cover screen and assign each one independently.

The existing `termux-launcher-coverscreen` fork remains useful for a separate companion improvement: making Termux Launcher launch child apps on the display where Termux Launcher is running. Launcher Portal must not depend on that fork.

## Product Idea

Launcher Portal replaces a traditional visible launcher page with configurable activation surfaces.

Example:

- Portal widget A -> Termux Launcher
- Portal widget B -> Firefox
- Portal widget C -> Messages
- Portal widget D -> Samsung Recents

Each widget instance receives its own `appWidgetId` and stores its own destination. The default presentation is visually transparent so the Samsung cover background remains visible.

Core interaction:

1. User adds Launcher Portal from Samsung Cover screen -> Widgets.
2. Android opens the widget configuration activity for that instance.
3. User chooses an installed launcher activity or the built-in `System Recents` destination.
4. User chooses launch mode:
   - `Tap to enter` — default, no Accessibility permission.
   - `Auto-open after dwell` — optional, requires Launcher Portal AccessibilityService.
5. Widget becomes a transparent portal to that destination.

## Goals

- Ship Launcher Portal as a small standalone APK.
- Let each widget instance select a different target app/activity.
- Launch the selected target explicitly on the cover display using `ActivityOptions.setLaunchDisplayId(...)`.
- Resolve the cover display at runtime instead of globally hard-coding display 1.
- Keep the default app useful without Accessibility permission.
- Include Samsung's real One UI Recents/task switcher as a first-class built-in V1 destination.
- Support multiple portal instances if One UI permits duplicate instances of one provider.
- If One UI blocks duplicate instances of one provider, retain an implementation fallback using multiple equivalent provider aliases without changing the user-facing model.
- Allow optional per-widget auto-open with a user-configurable dwell delay when Accessibility is enabled.
- Preserve cover-screen swiping while auto-open is armed; leaving the portal must cancel its timer.
- Keep idle CPU effectively zero in permissionless/manual mode.

## Non-goals

- Recreate or imitate Samsung Recents UI.
- Recreate Termux Launcher, app drawers, terminals, or keyboards inside `RemoteViews`.
- Replace Samsung's cover-screen home implementation.
- Bypass Android/Samsung restrictions that disallow a target activity on a secondary display.
- Require Shizuku, root, `WRITE_SECURE_SETTINGS`, or `QUERY_ALL_PACKAGES` for V1.
- Require Accessibility for ordinary app portals or the direct Recents portal.
- Add screen rotation or density overrides in V1.
- Use Accessibility to inspect user text/content.
- Auto-open portals when Accessibility is disabled.

## Research / Evidence Sources

### Samsung FlexWindow

Samsung documents native FlexWindow AppWidgets, transparent widget backgrounds, `sub_screen` provider metadata, and launching activities onto the cover display through `ActivityOptions.launchDisplayId`.

Reference material:

- https://developer.samsung.com/galaxy-z/flex_window.html
- https://developer.samsung.com/codelab/galaxy-z/widget-flex-window.html

### Android AppWidget model

Android assigns each placed widget a unique `appWidgetId`, and supports a per-instance configuration activity. Launcher Portal uses that identity as the storage key for destination and behavior.

### miniTools Recents reference

Reference repository: `RimorCosmicam/miniTools` (MIT).

miniTools proves that Samsung's real task switcher can be started directly as an ordinary activity:

`com.sec.android.app.launcher/com.android.quickstep.RecentsActivity`

Its implementation uses `ActivityOptions.makeBasic().setLaunchDisplayId(displayId)` and these flags:

- `FLAG_ACTIVITY_NEW_TASK`
- `FLAG_ACTIVITY_CLEAR_TASK`
- `FLAG_ACTIVITY_TASK_ON_HOME`

`CLEAR_TASK` is important because an already-existing explicit Recents activity can otherwise redraw a stale model instead of rebuilding the task list.

miniTools' AccessibilityService is not required for the direct explicit Recents start. It owns persistent overlay/gesture zones and provides `GLOBAL_ACTION_RECENTS` only as a fallback if the explicit activity start fails.

Launcher Portal should independently implement the small amount of required behavior and retain MIT attribution in source documentation if code is adapted substantially.

## Risk Rating + Routing

- **Overall V1:** R2 / medium.
- **Permissionless portals:** R1-R2; conventional AppWidget + explicit Activity launch.
- **Samsung Recents:** R2; OEM component and task behavior are device/One-UI dependent.
- **Accessibility auto-open:** R3 until visibility identification and cancellation are proven stable.
- **Blast radius:** isolated standalone APK; no shared UID and no modification of installed target apps.
- **Rollback:** strong. Disable/remove widget or uninstall Launcher Portal.

## Architecture

### Core components

Proposed package:

`com.katsuyamaki.launcherportal`

Proposed component family:

- `PortalWidgetProvider`
- `PortalConfigureActivity`
- `PortalDestinationRepository`
- `PortalAppCatalog`
- `CoverDisplayResolver`
- `PortalLaunchIntentFactory`
- `RecentsDestination`
- `PortalAccessibilityService` — optional, V1 gated
- `PortalAutoOpenController` — optional, V1 gated
- `PortalLoadingOverlay` — optional, V1 gated

### Stored per-widget state

Key by `appWidgetId`.

Required fields:

- destination type: `APP`, `RECENTS`, or `NONE`
- selected package/component for `APP`
- last-known display label/icon metadata for configuration UI only
- launch mode: `TAP` or `AUTO_DWELL`
- dwell delay
- optional loading-overlay enabled/disabled

Deleting a widget must delete its stored record.

Do not render the selected app icon, label, or screenshot in the transparent widget unless a later user-selectable visible style is added.

## Cover Display Resolution

Do not make product logic depend on literal display ID 1.

Resolve displays from `DisplayManager` at runtime. On the Flip7, the known cover panel is the non-default built-in display and has been observed as 948 x 1048. A pragmatic resolver may prefer a matching physical panel but must fall back safely to a valid non-default display rather than silently using display 0 for a cover-only action.

Log the selected display ID in debug builds.

## V1 Destination Types

### 1. Installed app

Configuration queries activities matching:

- `ACTION_MAIN`
- `CATEGORY_LAUNCHER`

Manifest package visibility should declare that query rather than request `QUERY_ALL_PACKAGES`.

Store the exact selected `ComponentName`.

Launch using an explicit intent plus `ActivityOptions.setLaunchDisplayId(resolvedCoverDisplayId)`.

If Android/Samsung rejects the target on that display, report a concise failure and do not silently move the app to display 0 unless a later explicit setting permits that behavior.

### 2. System Recents

Built-in destination label: `System Recents`.

Samsung component:

`com.sec.android.app.launcher/com.android.quickstep.RecentsActivity`

Launch intent contract:

- `ACTION_MAIN`
- explicit component above
- `CATEGORY_DEFAULT`
- `NEW_TASK | CLEAR_TASK | TASK_ON_HOME`
- `ActivityOptions.setLaunchDisplayId(resolvedCoverDisplayId)`

Direct explicit start is the primary implementation and does **not** require Accessibility.

If direct start fails and the optional AccessibilityService is enabled, V1 may use `performGlobalAction(GLOBAL_ACTION_RECENTS)` as a fallback. That fallback is best-effort because the system chooses placement.

Do not add density override or rotation behavior to the Recents implementation in V1.

## Widget UX

### Default: Tap to enter

The widget body is transparent.

The whole usable widget root is one click target.

Tap -> selected destination launches on the resolved cover display.

This mode:

- requires no Accessibility permission;
- requires no persistent service;
- has no auto-open timer;
- is the permanent fallback even if auto-open ships.

### Multiple portals

V1 must test whether Samsung's FlexWindow host permits multiple independent instances of the same `PortalWidgetProvider`.

Expected ideal model:

- instance 101 -> Firefox
- instance 102 -> Termux Launcher
- instance 103 -> Recents

If Samsung refuses duplicate instances of one provider, implement a bounded set of equivalent provider aliases (`Portal 1`, `Portal 2`, etc.) that reuse the same configuration/storage code. This fallback is only activated if device evidence requires it.

## Optional Accessibility Enhancement

Accessibility is **off by default** and must not be requested merely to use Launcher Portal.

The service exists only for user-selected enhanced behavior:

1. detect when a configured Launcher Portal page appears to become the active FlexWindow widget;
2. run that portal's dwell timer;
3. optionally show a non-touchable fake loading/transition overlay;
4. cancel immediately if the user swipes away;
5. launch the selected destination after the timer expires;
6. optionally provide Recents global-action fallback if direct Recents launch fails.

The service must not read or persist user text, notifications, terminal content, or arbitrary window content.

### Visibility-detection gate

There is no supported AppWidget callback for “this widget page became visible.” Therefore auto-open is not accepted merely because an AccessibilityService exists.

V1 implementation must first prove a stable signal on the Flip7/One UI host that distinguishes:

- portal widget A visible;
- portal widget B visible;
- neighboring non-portal widget visible;
- cover home/clock visible;
- a target app visible.

Candidate evidence may include accessibility window/node metadata exposed by SystemUI/AppWidgetHost. The exact signal is `TBD_PENDING_DEVICE_PROOF`.

If a stable unique signal is not found, V1 still ships successfully with tap-to-enter portals and System Recents. Auto-open becomes deferred.

## Auto-Open Dwell / Loading Design

The user's core navigation problem is that immediate auto-open would make it impossible to swipe past one portal to reach another.

V1 optional behavior therefore uses **dwell**, not immediate page activation.

Sequence:

1. Accessibility identifies portal instance N as active.
2. Start N's configurable timer.
3. Optionally display `Entering portal...` / countdown overlay on the cover display.
4. Overlay must be non-focusable and non-touchable so cover swipes can continue underneath it.
5. If another page becomes active before expiry, cancel timer and remove overlay.
6. If N remains active until expiry, launch N's destination.

The timer should be configurable per widget or via a global default with per-widget override.

Exact supported delay range is `TBD_PENDING_DEVICE_USABILITY_TEST`. Do not choose an artificially small minimum before proving that normal swipe navigation can beat the timer reliably.

### Loading overlay

Preferred technical shape when Accessibility is enabled:

- `TYPE_ACCESSIBILITY_OVERLAY`
- bound to the cover display context
- `FLAG_NOT_FOCUSABLE`
- `FLAG_NOT_TOUCHABLE`
- no input interception
- short, simple visual state such as `Entering portal...`

The loading overlay is cosmetic. Timer correctness must not depend on its visibility.

If the overlay causes One UI host instability or obscures navigation cues, auto-open may keep the dwell timer without the fake loading screen.

## Permission Model

### Core / default

No Accessibility.

No Shizuku.

No root.

No `WRITE_SECURE_SETTINGS`.

No `QUERY_ALL_PACKAGES`.

### Optional

`BIND_ACCESSIBILITY_SERVICE` only when the user deliberately enables auto-open / enhanced behavior.

The app should clearly explain that ordinary portals and the Recents portal work without this permission.

## Module Plan

### M0 — Standalone baseline + repo bootstrap

Purpose:

- create standalone Android project;
- establish package/application identity;
- record Flip7 display baseline;
- preserve this canonical blueprint in the new repo;
- mark the previous Termux-hosted portal blueprint superseded.

Exit:

- standalone app builds;
- install coexists with normal Termux and Termux Launcher;
- no privileged permissions required.

### M1 — Configurable transparent portal

Purpose:

- native Samsung FlexWindow AppWidget registration;
- per-widget configuration activity;
- app picker;
- `appWidgetId` state;
- transparent full-area tap target;
- direct selected-app launch on resolved cover display.

Mandatory smoke:

- widget appears in Samsung cover widget picker;
- configure Firefox/another known compatible app;
- transparent page appears;
- tap launches correct component on cover display;
- delete/re-add cleans state;
- display 0 is not accidentally used as a fallback.

### M2 — Multiple portal instances

Purpose:

- prove several instances of one provider can coexist;
- if not, implement provider-alias fallback.

Mandatory smoke:

- at least three portals assigned to distinct destinations;
- each independently launches the correct destination;
- reconfiguring one does not mutate another.

### M3 — Samsung System Recents portal

Purpose:

- implement `System Recents` built-in destination using the Samsung component/flags proven by miniTools.

Mandatory smoke:

- Recents cold launch on cover display;
- repeated Recents launch reflects current recent tasks;
- dismissing Recents returns appropriately to cover home/task flow;
- no Accessibility permission required for direct path.

### M4 — Optional Accessibility auto-open proof

Purpose:

- determine whether One UI exposes a reliable signal identifying the active portal widget instance.

No production auto-open code is accepted until signal identity is proven.

Outcome must be one of:

- `GO`
- `NO_GO`
- `NEEDS_MORE_EVIDENCE`

### M5 — Optional dwell + loading auto-open

Only if M4 returns GO.

Purpose:

- configurable dwell timer;
- cancellation on swipe-away;
- optional non-touchable loading overlay;
- launch after sustained portal visibility;
- Recents `GLOBAL_ACTION_RECENTS` fallback when direct Recents start fails.

Mandatory smoke:

- swipe rapidly across multiple portal pages without accidental launch;
- dwell on target portal -> exactly one launch;
- swipe away during countdown -> zero launch;
- returning starts a fresh timer;
- overlay never blocks swipe/tap input;
- service disabled -> manual portal remains fully functional.

### M6 — V1 hardening

Purpose:

- task reuse;
- package uninstall/reinstall handling;
- stale component recovery;
- configuration UX;
- accessibility opt-in disclosure;
- One UI restart/reboot behavior;
- battery/idle validation.

## Mandatory Smoke Goal IDs

| Goal | Module | Scenario | Pass criteria |
|---|---|---|---|
| `PORTAL-M1-001` | M1 | Widget discovery | Portal appears and can be added to FlexWindow |
| `PORTAL-M1-002` | M1 | Configure app | Per-instance picker persists exact component |
| `PORTAL-M1-003` | M1 | Transparent tap | Full usable area launches selected app on cover display |
| `PORTAL-M1-004` | M1 | Unsupported target | Failure is safe; no surprise display-0 launch |
| `PORTAL-M2-001` | M2 | Three independent portals | Each launches its own destination |
| `PORTAL-M2-002` | M2 | Reconfigure one | Other instances remain unchanged |
| `PORTAL-M3-001` | M3 | Direct Recents | Samsung Recents opens on cover display without Accessibility |
| `PORTAL-M3-002` | M3 | Fresh Recents model | Newly used task appears after relaunch |
| `PORTAL-M4-001` | M4 | Portal visibility signal | Active instance uniquely identifiable or NO_GO recorded |
| `PORTAL-M5-001` | M5 | Swipe-through | No accidental auto-launch while passing portals |
| `PORTAL-M5-002` | M5 | Dwell | Sustained visibility launches exactly once |
| `PORTAL-M5-003` | M5 | Cancel | Swipe-away cancels countdown and overlay |
| `PORTAL-M5-004` | M5 | Accessibility disabled | Tap mode and direct Recents still work |

## Security / Privacy

- All app launches use explicit stored `ComponentName`s or the fixed Samsung Recents component.
- Never accept arbitrary component/display commands from exported intents without validation.
- Per-widget preferences contain only component identities and portal settings.
- Accessibility events are processed only for identifying portal/window state required by the opted-in feature.
- Do not persist accessibility node text/content.
- No network permission is necessary for core V1.

## Performance

Permissionless mode:

- no persistent process requirement;
- no polling;
- no timers while idle;
- no icon rendering in the active transparent RemoteViews;
- configuration catalog loaded only when needed.

Accessibility mode:

- event-driven only;
- no periodic screen scraping;
- one active dwell timer maximum;
- loading overlay exists only while a portal is armed.

## Compatibility / Failure Handling

- Missing/uninstalled target: mark portal unconfigured and reopen configuration instead of crashing.
- Target refuses cover display: show concise failure and stay on cover UI.
- Samsung Recents component missing: hide/disable `System Recents` destination on unsupported devices.
- Cover display unresolved: do not silently launch to main display.
- Accessibility unavailable/disabled: auto-open settings are inactive; manual portals remain valid.
- One UI changes accessibility host metadata: auto-open automatically degrades to manual tap rather than guessing.

## Documentation

README should explain two layers clearly:

1. **Launcher Portal core** — transparent configurable portals and System Recents; no Accessibility required.
2. **Auto-open enhancement** — optional Accessibility permission, dwell timer, and optional loading overlay.

Also document that Android/Samsung can still reject some apps on the cover display.

## Open Questions

1. Does One UI permit multiple instances of the same third-party FlexWindow provider?
2. What exact accessibility signal, if any, uniquely identifies the active portal `appWidgetId`?
3. What dwell-delay range feels usable for swiping between portal pages?
4. Does a non-touchable accessibility loading overlay leave all FlexWindow swipe navigation intact?
5. Does Samsung's explicit Recents component remain stable across the target One UI builds we support?
6. Should dwell defaults be global, per-widget, or global-with-override? Initial recommendation: global default + per-widget override.
7. Should a rejected app launch ever offer an explicit user setting to retry on display 0? V1 default: no.

## V1 Acceptance

V1 is considered complete if M1-M3 and M6 pass even if M4 returns NO_GO. Auto-open is an enhancement, not a condition of Launcher Portal's core release.

If M4 returns GO, M5 may be included in V1 after its own review/smoke gates.

## Backout

- Disable/remove AccessibilityService -> product immediately returns to manual portal mode.
- Remove Recents destination -> ordinary portals remain intact.
- Remove widget provider aliases -> normal provider remains intact if One UI supports duplicate instances.
- Uninstall Launcher Portal -> no target app data or Termux installation is modified.

## First Implementation Respin

Start with M0/M1 only:

- standalone project bootstrap;
- Samsung FlexWindow widget registration;
- per-instance configuration;
- launcher app catalog;
- cover display resolver;
- transparent manual tap launch.

Do **not** start Accessibility auto-open before M4 evidence exists.

Recents can follow immediately in M3 because the direct implementation is already well evidenced by miniTools and does not depend on Accessibility.