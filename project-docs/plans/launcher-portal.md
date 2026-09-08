# Implementation Plan: Launcher Portal

**Status:** V1 approved for implementation after standalone-product direction change and Recents evidence correction.  
**Product:** standalone Android APK, independent of Termux Launcher.  
**Working repo name:** `Launcher-Portal` (display name: Launcher Portal).  
**Primary device:** Samsung Galaxy Z Flip7 / One UI FlexWindow.  
**V1 core:** configurable transparent app portals + multiple widget instances + Samsung Recents bridge.  
**Accessibility:** not required for ordinary app portals; currently the leading required mechanism for the Samsung Recents bridge and separately optional for auto-open/dwell.  
**V2:** broader gesture automation and richer layouts only after V1 proof.

## Direction Change

Launcher Portal is a standalone APK. Each widget instance is an independent portal whose destination is selected during widget configuration. Termux Launcher is only one possible destination.

The earlier plan to embed the portal inside the Termux Launcher fork is superseded. The Termux fork may separately receive display-local child-app launching later, but Launcher Portal must not depend on it.

## Product Model

Example:

- Portal A -> Termux Launcher
- Portal B -> Firefox
- Portal C -> Messages
- Portal D -> System Recents

Each placed widget receives its own `appWidgetId` and stores its own destination and behavior.

Default visual state: transparent. The normal Samsung cover-screen background remains visible.

Default interaction: tap anywhere in the usable widget surface to enter the configured destination.

Optional interaction: auto-open after a configurable dwell delay, only if the user enables Launcher Portal's AccessibilityService and the visibility-detection experiment succeeds.

## Goals

- Keep ordinary app portals permissionless.
- Use per-widget configuration and persistent `appWidgetId -> destination` state.
- Resolve the cover display at runtime and launch selected apps with `ActivityOptions.setLaunchDisplayId(...)`.
- Support multiple independent portals.
- Include Samsung's native One UI Recents as a V1 destination if the AccessibilityService caller experiment succeeds.
- Keep the Recents accessibility profile narrow: no window-content retrieval required for the initial experiment.
- Preserve cover-screen swiping by using dwell/cancel rather than immediate auto-open.
- Make Accessibility opt-in and feature-scoped.
- Avoid Shizuku, root, `WRITE_SECURE_SETTINGS`, and `QUERY_ALL_PACKAGES` in V1.

## Non-goals

- Recreate Samsung Recents UI.
- Recreate Termux Launcher inside `RemoteViews`.
- Replace Samsung's cover home.
- Bypass Samsung/MultiStar app eligibility rules.
- Re-test already-failed shell, TermuxAm, ordinary Activity trampoline, or metadata-only Recents approaches.
- Add density override or cover rotation to V1.
- Read or persist user screen text through Accessibility.

## Evidence: Ordinary App Launching

Patched TermuxAm with `ActivityOptions.setLaunchDisplayId(1)` successfully launched ordinary compatible apps such as DroidOS Keyboard Picker onto display 1.

This proves:

- display 1 is correct on the test device;
- explicit secondary-display launching works;
- Termux is not globally prohibited from launching onto FlexWindow.

Launcher Portal app portals should therefore use explicit selected `ComponentName`s plus runtime-resolved cover display options.

## Evidence: Samsung Recents

Samsung Recents component:

`com.sec.android.app.launcher/com.android.quickstep.RecentsActivity`

miniTools launches the real One UI Recents activity using:

- `ACTION_MAIN`
- `CATEGORY_DEFAULT`
- explicit Recents component
- `FLAG_ACTIVITY_NEW_TASK`
- `FLAG_ACTIVITY_CLEAR_TASK`
- `FLAG_ACTIVITY_TASK_ON_HOME`
- `ActivityOptions.setLaunchDisplayId(displayId)`

`CLEAR_TASK` is intentional: without it, an existing explicit Recents activity can redraw a stale task list instead of rebuilding current recents.

### Already failed and must not be repeated

1. Termux -> explicit Samsung Recents with the exact miniTools-like intent and display options -> Samsung shows `Open phone to continue`.
2. A MultiStar-authorized ordinary Activity on display 1 -> trampoline -> exact Samsung Recents intent -> still `Open phone to continue`.
3. Adding miniTools' Samsung application metadata to that authorized package -> still fails.

Therefore these are ruled out as sufficient mechanisms:

- shell/Termux caller alone;
- ordinary Activity caller on display 1;
- ordinary Activity + MultiStar authorization;
- ordinary Activity + Samsung cover metadata.

### Leading remaining hypothesis

miniTools normally calls `Recents.open(this, displayId)` where `this` is its running `AccessibilityService`.

That caller type is the largest remaining behavioral difference.

The next useful Recents experiment is therefore exactly:

**enabled Launcher Portal AccessibilityService -> exact Samsung Recents intent -> `ActivityOptions.setLaunchDisplayId(coverDisplayId)`**

Do not add an Activity trampoline between the service and Recents.

## Accessibility Model

### Ordinary app portals

No Accessibility permission.

### Recents bridge

Until disproven by the next experiment, treat Accessibility as required for the Recents destination.

Use a deliberately narrow service configuration comparable to miniTools:

- `accessibilityEventTypes="typeWindowStateChanged"`
- `accessibilityFeedbackType="feedbackGeneric"`
- `accessibilityFlags="flagDefault"`
- `canRetrieveWindowContent="false"`

The initial Recents experiment does not require reading accessibility nodes or screen text.

If the service is disabled, ordinary portals remain fully functional and `System Recents` should be shown as unavailable or prompt the user to enable the optional service.

### Auto-open

Auto-open is a separate capability gate even if it later shares the same service class.

Do not broaden the Recents-only accessibility configuration merely to implement auto-open.

If unique widget-instance visibility cannot be determined without window-content retrieval, stop and review the privacy/UX tradeoff before changing `canRetrieveWindowContent`.

## Samsung / MultiStar Requirements

A newly installed Launcher Portal package may need explicit Good Lock / MultiStar cover-screen authorization before its own Activities are allowed on FlexWindow.

Launcher Portal should also include the relevant Samsung compatibility metadata used by working cover-screen apps:

- `com.samsung.android.multidisplay.keep_process_alive = true`
- `com.samsung.android.activity.showWhenLocked = true`
- `com.samsung.android.coverscreen.subscreen_compatible = true`

Appropriate cover activities may also advertise:

`com.samsung.android.support.COVER_SCREEN`

These metadata values are compatibility aids, not a Recents bypass; device testing already proved metadata alone is insufficient.

## Permissions Explicitly Not Required for Recents

Do not add these merely for the Recents feature:

- `WRITE_SECURE_SETTINGS`
- `PACKAGE_USAGE_STATS`
- Shizuku
- root

miniTools uses other permissions for unrelated density, sorting, boot, vibration, or rotation behavior.

## Core Architecture

Proposed package:

`com.katsuyamaki.launcherportal`

Primary components:

- `PortalWidgetProvider`
- `PortalConfigureActivity`
- `PortalDestinationRepository`
- `PortalAppCatalog`
- `CoverDisplayResolver`
- `PortalLaunchIntentFactory`
- `RecentsDestination`
- `PortalAccessibilityService`
- `PortalAutoOpenController` — gated behind later proof
- `PortalLoadingOverlay` — gated behind later proof

## Per-Widget State

Store by `appWidgetId`:

- destination type: `APP`, `RECENTS`, `NONE`
- exact app `ComponentName` for `APP`
- launch mode: `TAP`, optionally later `AUTO_DWELL`
- dwell delay if enabled
- optional loading-overlay preference

Delete state when the widget instance is removed.

## App Picker

Query launchable apps through:

- `ACTION_MAIN`
- `CATEGORY_LAUNCHER`

Declare the matching manifest `<queries>` entry instead of requesting `QUERY_ALL_PACKAGES`.

Store the exact chosen `ComponentName`.

## Cover Display Resolution

Do not hard-code display 1 in product policy even though it is display 1 on the test Flip7.

Resolve from `DisplayManager` at runtime. Prefer the known non-default built-in cover panel; on the current Flip7 it is observed at 948 x 1048.

If no credible cover display is found, fail safely rather than silently launching onto display 0.

## Widget UX

### Manual portal

Transparent `RemoteViews` root fills the usable widget area.

Tap -> selected destination.

For normal apps:

- explicit target component;
- `ActivityOptions.setLaunchDisplayId(resolvedCoverDisplayId)`.

If Samsung rejects the target, report failure and remain on the cover UI. Do not silently jump to display 0 in V1.

### Multiple portals

V1 must test whether One UI allows multiple instances of the same third-party provider.

Ideal:

- instance 101 -> Firefox
- instance 102 -> Termux Launcher
- instance 103 -> Recents

If One UI refuses duplicate instances, implement a bounded set of provider aliases that share the same configuration/storage backend.

## System Recents Destination

Primary implementation path:

1. User enables Launcher Portal AccessibilityService for Recents.
2. Portal action sends an internal explicit command to the already-running service.
3. Service resolves the cover display.
4. Service directly calls the exact Samsung Recents intent with display options.
5. No ordinary Activity trampoline is used.

Intent contract:

- `ACTION_MAIN`
- `CATEGORY_DEFAULT`
- component `com.sec.android.app.launcher/com.android.quickstep.RecentsActivity`
- `NEW_TASK | CLEAR_TASK | TASK_ON_HOME`
- `ActivityOptions.setLaunchDisplayId(resolvedCoverDisplayId)`

If this service-context path fails, record `RECENTS_SERVICE_CONTEXT_NO_GO` before trying any new workaround.

`performGlobalAction(GLOBAL_ACTION_RECENTS)` may be tested only as a secondary fallback after the explicit service-context path, because the system may choose its own display.

## Auto-Open / Dwell Design

Immediate auto-open is intentionally rejected because it would make swiping through several portal pages impractical.

If visibility detection succeeds:

1. active portal instance identified;
2. start its dwell timer;
3. optionally show `Entering portal...` / countdown;
4. user swipes away -> cancel timer and remove overlay;
5. user remains -> launch exactly once.

Loading overlay, if used:

- `TYPE_ACCESSIBILITY_OVERLAY`
- cover-display context
- `FLAG_NOT_FOCUSABLE`
- `FLAG_NOT_TOUCHABLE`
- cosmetic only; timer correctness must not depend on it.

## Module Plan

### M0 — Repo/bootstrap and baseline

- create standalone Android project;
- package/application identity;
- Samsung cover metadata;
- canonical blueprint in the standalone repo;
- confirm coexistence with normal Termux and Termux Launcher.

### M1 — Permissionless configurable app portal

- FlexWindow widget registration;
- per-instance configuration activity;
- app picker;
- transparent full-area tap target;
- runtime cover display resolver;
- direct selected-app launch.

### M2 — Multiple independent portals

- prove duplicate provider instances;
- if blocked, add provider-alias fallback.

### M3 — Recents AccessibilityService caller experiment

This is the first Recents implementation work. Do not repeat prior failed paths.

Experiment:

- enable minimal `PortalAccessibilityService`;
- invoke service internally;
- service directly starts Samsung Recents with exact proven flags/display options.

Possible outcomes:

- `GO_SERVICE_CONTEXT`
- `NO_GO_SERVICE_CONTEXT`
- `NEEDS_MORE_EVIDENCE`

If GO, integrate `System Recents` as a V1 destination requiring the optional service.

### M4 — Recents hardening

Only after M3 GO:

- repeated launch freshness;
- `CLEAR_TASK` verification;
- dismissal/task behavior;
- service-disabled UX;
- optional `GLOBAL_ACTION_RECENTS` fallback evaluation.

### M5 — Auto-open visibility proof

Determine whether One UI exposes a stable signal that uniquely identifies the active portal widget instance.

Do not enable production auto-open until this returns GO.

### M6 — Dwell + loading auto-open

Only after M5 GO:

- configurable dwell;
- cancel on swipe-away;
- non-touchable loading overlay;
- exactly-once launch.

### M7 — V1 hardening

- stale/uninstalled components;
- reboot/process death;
- MultiStar onboarding;
- accessibility disclosures;
- battery/idle checks;
- failure UX.

## Mandatory Smoke Goals

| Goal | Module | Pass criteria |
|---|---|---|
| `PORTAL-M1-001` | M1 | Widget appears and can be configured on FlexWindow |
| `PORTAL-M1-002` | M1 | Selected app persists by exact component |
| `PORTAL-M1-003` | M1 | Tap launches known-compatible app on cover display |
| `PORTAL-M1-004` | M1 | Rejected app does not jump silently to display 0 |
| `PORTAL-M2-001` | M2 | Three portals independently launch three destinations |
| `PORTAL-M3-001` | M3 | Minimal AccessibilityService is enabled and running with no window-content retrieval |
| `PORTAL-M3-002` | M3 | Service caller directly requests Samsung Recents on cover display |
| `PORTAL-M3-003` | M3 | Result classified GO/NO_GO without Activity trampoline |
| `PORTAL-M4-001` | M4 | Repeated Recents launch reflects current task list |
| `PORTAL-M5-001` | M5 | Active portal instance uniquely identifiable or NO_GO recorded |
| `PORTAL-M6-001` | M6 | Swiping across portals causes no accidental auto-launch |
| `PORTAL-M6-002` | M6 | Sustained dwell launches exactly once |
| `PORTAL-M6-003` | M6 | Swipe-away cancels timer and overlay |
| `PORTAL-M7-001` | M7 | Accessibility disabled -> ordinary manual app portals still work |

## Security / Privacy

- All app launches use stored explicit `ComponentName`s or the fixed Samsung Recents component.
- Internal service commands must be explicit and non-exported or otherwise strongly validated.
- Recents-only Accessibility mode must not retrieve window content.
- Do not log accessibility node text, terminal content, notifications, or user-entered text.
- No network permission is required for core V1.

## Performance

Manual portal mode:

- no polling;
- no persistent service required;
- no timers while idle.

Accessibility mode:

- event-driven;
- no periodic screen scraping;
- at most one dwell timer;
- overlay exists only during an armed dwell.

## V1 Acceptance

V1 ordinary app portals are complete when M1, M2, and M7 pass.

V1 Recents is included only if M3 returns `GO_SERVICE_CONTEXT` and M4 passes.

Auto-open may join V1 only if M5 returns GO and M6 passes. Otherwise manual portals remain the release behavior.

## First Implementation Respin

Start with M0 + M1 for the standalone product.

In parallel or immediately afterward, M3 is the **only** Recents experiment worth performing next:

**AccessibilityService context -> exact Samsung RecentsActivity intent -> resolved cover display.**

Do not spend more time on TermuxAm flags, ordinary Activity trampolines, or Samsung metadata-only Recents experiments; those have already been ruled out.