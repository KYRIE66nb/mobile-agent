# Mobile Agent

[English](README.md) | [简体中文](README.zh-CN.md)

**An on-device agent that literally holds your phone** — an open-source Android agent that sees the screen and taps the screen for you.

[![Platform](https://img.shields.io/badge/Platform-Android%207.0%2B-3DDC84?logo=android&logoColor=white)](https://github.com/KYRIE66nb/mobile-agent)
[![Language](https://img.shields.io/badge/Kotlin-100%25-7F52FF?logo=kotlin&logoColor=white)](https://github.com/KYRIE66nb/mobile-agent)
[![Release](https://img.shields.io/github/v/release/KYRIE66nb/mobile-agent)](https://github.com/KYRIE66nb/mobile-agent/releases/latest)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue)](LICENSE)

> Early-stage project: APIs and interactions may still change. Try it on a test device first, and do not use it for payments or account-security operations.

## Why it is different

Desktop agents top out at the browser. This one's ceiling is **every app installed on your phone**.

No APIs, no web versions — the agent looks at the screen and taps it like a person would. WeChat, Meituan, Settings, Gallery: if a human can operate the UI, the agent can too.

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/agent-loop.dark.png">
  <img src="docs/assets/agent-loop.light.png" alt="Mobile Agent execution loop" width="100%">
</picture>

[Interactive architecture map](docs/assets/agent-loop.html) — open locally; supports zoom, path tracing, and dark mode.

## Core capabilities

### Dual-path UI perception

| Path | How it works | Best for |
|---|---|---|
| **Accessibility node tree** | Reads semantic UI nodes (text, bounds, clickability) and taps by semantics | Standard controls — precise and cheap in tokens |
| **Vision coordinates** | Sends screenshots to a vision model and injects touches at pixel coordinates | Icons, image grids, Flutter/self-drawn UIs |

Enable the **Vision model** toggle on a profile (GLM-4.5V, GPT-4o, …) and `device_observe` screenshots are sent to the model as image blocks; the model answers with pixel `x/y` that map directly to taps, swipes, and text input.

### Action safety gate (DecisionGate)

Every tool call with **external side effects** (sending messages, changing settings, deleting data…) passes a semantic verdict before execution:

- `allow` — ordinary actions run without interruption;
- `confirm` — merged into the existing approval dialog, verdict reason attached;
- `block` — high-risk irreversible actions are denied, and the reason goes back to the model so it can pick another route.

Timeouts or verdict failures degrade to manual confirmation — **never silently allowed**. Toggleable in settings; read-only tools never enter the gate.

### Two device-control engines

- **Accessibility service** (no root): node-tree reads, taps, text input, swipes, foreground-app tracking;
- **Root virtual display** (optional): a standalone `RootDeviceService` works on a `VirtualDisplay` — screenshots and touch injection run on a **background screen** while your main screen stays yours; watch the agent live via the floating overlay.

**Execution target preference** (Settings → General → Device operation target):

| Mode | Behaviour |
|---|---|
| **Auto** (default) | The agent picks main/background per task; when the virtual display is unavailable it **falls back to the main screen**, disclosed in both the approval text and tool results |
| **Main screen only** | Always runs in the foreground — saves rootless devices a doomed virtual-display attempt |
| **Background only** | Uses the virtual display or fails — **never silently touches your main screen** |

### System cleanup

Say "clean up my phone" or "free some memory" — three paths chosen automatically by permission level:

- **Storage stats** `system_storage_stats` — per-app breakdown or a Top-N consumer ranking; querying other apps needs Usage access, and the agent opens the grant page instead of failing blind;
- **Cache clearing** `system_clear_cache` — no argument cleans this app's caches; a package name + root makes `RootDeviceService` empty that app's `cache/code_cache` dirs and reports freed bytes (**sign-in state and user data untouched**); without root it degrades to opening the app's details page for on-screen clearing;
- **Memory freeing** `system_free_memory` — `killBackgroundProcesses` for one app or everything launchable, reporting available RAM before and after.

Cleanup calls are `EXTERNAL_WRITE`/`DESTRUCTIVE` — they pass the safety gate and an approval dialog.

### Ad guard

Popups and shake ads flash for only a few seconds — far too fast for a model loop. Blocking runs on a **deterministic rule engine** inside the accessibility event stream (millisecond latency, no model in the loop); the model's job is configuring rules on demand:

- **Skip splash ads** — auto-taps "Skip"-style nodes;
- **Close ad popups** — taps "× / Close" when the screen carries an ad marker;
- **Cancel shake-ad jumps** — `auto_back` rules press Back the moment the foreground is thrown from a guarded app to a browser/shop landing page;
- **Agent-programmable** — tell it "this app keeps popping lottery ads" or "one shake throws me into a store", and it writes a rule on the spot via `adguard_add_rule` so the next ad is killed instantly.

Per-rule cooldowns plus a global circuit breaker stop misconfigured loops; every block surfaces a toast, and `adguard_status` replays recent blocks. Requires the accessibility service; toggled in settings, off by default.

### Models and tools

- **OpenAI-compatible gateway**: hand-rolled OkHttp + SSE streaming; Zhipu GLM / OpenAI / DeepSeek / any compatible endpoint, multi-profile switching, `reasoning_effort` passthrough;
- **40+ built-in tools**: device actions (observe / action / gesture / batch / wait_for), file I/O, webpage fetching, notifications, clipboard, app launching, speech transcription (OpenAI / iFLYTEK-compatible), system maintenance (storage stats / cache clearing / memory freeing), ad-guard rule management;
- **Multi-turn agent loop**: context compression, per-run step caps, instant cancel, every tool call persisted and replayable.

## Architecture

Six pure-Kotlin modules, zero frameworks (no Hilt / Koin / MVVM scaffolding); the core runtime never touches the Android SDK:

| Module | Responsibility |
|---|---|
| `:app` | Compose UI, manual wiring (`PrototypeApplication`), overlay, updater |
| `:agent-core` | `ChatRuntime` loop, tool contracts, `DecisionGate` |
| `:model` | OpenAI-compatible gateway (OkHttp + SSE), probing |
| `:device` | Accessibility service, ad-guard engine, Root/AIDL service, VirtualDisplay, input injection |
| `:tools` | Device / file / network / notification / clipboard / system-maintenance / ad-guard tool providers |
| `:data` | Room persistence, DataStore settings, Keystore secret protection |

Stack: Kotlin 2.0 + Jetpack Compose (Material3) + Room + DataStore + Coil + libsu. Gradle Kotlin DSL with version catalog. minSdk 24, targetSdk 36.

## Getting started

### Install

Grab the APK from [Releases](https://github.com/KYRIE66nb/mobile-agent/releases/latest), or update in-app via **Settings → About → Check for updates**.

### Build from source

```bash
git clone https://github.com/KYRIE66nb/mobile-agent.git
cd mobile-agent
./gradlew :app:assembleDebug    # APK lands in app/build/outputs/apk/debug/
```

### Configure a model (Zhipu example)

Settings → Models → New profile:

- Base URL: `https://open.bigmodel.cn/api/paas/v4`
- Model: `glm-5.3-flash` (for vision use `glm-4.5v` and enable the Vision toggle)
- API key: your Zhipu key

## Roadmap

- [x] Vision path for screen operation (observe → coordinate-action loop)
- [x] Semantic action safety gate (allow / confirm / block verdicts)
- [x] In-app upgrades via GitHub Releases (check → download → installer)
- [x] Foreground / background virtual-display preference (with auto-fallback)
- [x] System cleanup (storage stats / cache clearing / memory freeing; root direct-clear with no-root fallback)
- [x] Ad guard (accessibility rule engine: splash skip / popup close / shake-ad interception, agent-programmable)
- [ ] Reliable templates for high-frequency app flows (e.g. sending a WeChat message)
- [ ] Triggers: notification / location / scheduled tasks
- [ ] Non-root degraded control via Shizuku foreground operations
- [ ] PDF / Word / Excel reading and generation
- [ ] Pluggable standalone decision-model backend (Jev-like)

## Permissions and data boundaries

The app may request microphone, notifications, display-over-other-apps, boot startup, installed-app list, and accessibility access. Root, accessibility, screenshots, and external model calls are highly privileged — used **only after you explicitly enable or approve them**.

Model requests, speech transcription, and webpage access send selected content to the third-party services you configured. Read the [Privacy Notice](PRIVACY.md) before installing.

## Documentation

- [Product Definition](docs/PRODUCT.md) · [Design Guidelines](docs/DESIGN.md) · [Technical Architecture](docs/ARCHITECTURE.md) · [Implementation Plan](docs/PLAN.md) — currently in Chinese
- [Third-Party Notices](THIRD_PARTY_NOTICES.md) · [Security Policy](SECURITY.md)

## License

Source code is licensed under the [Apache License 2.0](LICENSE); third-party components remain under their respective licenses.
