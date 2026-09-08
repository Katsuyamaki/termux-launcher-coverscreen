# Superseded: Cover-Screen Launcher Portal inside Termux Launcher

**Status:** Superseded by the standalone Launcher Portal direction on 2026-09-08.

The original plan placed a transparent Samsung FlexWindow portal directly inside the Termux Launcher fork. The product direction changed before implementation: Launcher Portal is now a standalone APK whose widget instances can launch any selected app, including Termux Launcher, and can also expose Samsung's real Recents activity.

The canonical implementation blueprint is now:

[`launcher-portal.md`](launcher-portal.md)

## What remains relevant to this Termux fork

The standalone Launcher Portal does **not** require Termux Launcher modifications.

A separate companion improvement may still be useful here: when Termux Launcher itself is running on a secondary display, its app-launch path can explicitly request that same display for child apps. That work should receive its own scoped plan if/when it is implemented; it is no longer part of Launcher Portal V1.

## Historical decision

The standalone architecture was chosen because a per-widget configuration model makes the portal useful for arbitrary apps instead of only Termux Launcher. Each placed widget can represent a different destination, while the core app remains permissionless. Optional Accessibility permission is reserved for enhanced auto-open/dwell behavior rather than required for ordinary portal launching.