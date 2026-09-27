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

### Models and tools

- **OpenAI-compatible gateway**: hand-rolled OkHttp + SSE streaming; Zhipu GLM / OpenAI / DeepSeek / any compatible endpoint, multi-profile switching, `reasoning_effort` passthrough;
- **20+ built-in tools**: device actions (observe / action / gesture / batch / wait_for), file I/O, webpage fetching, notifications, clipboard, app launching, speech transcription (OpenAI / iFLYTEK-compatible);
- **Multi-turn agent loop**: context compression, per-run step caps, instant cancel, every tool call persisted and replayable.

## Architecture

Six pure-Kotlin modules, zero frameworks (no Hilt / Koin / MVVM scaffolding); the core runtime never touches the Android SDK:

| Module | Responsibility |
|---|---|
| `:app` | Compose UI, manual wiring (`PrototypeApplication`), overlay, updater |
| `:agent-core` | `ChatRuntime` loop, tool contracts, `DecisionGate` |
| `:model` | OpenAI-compatible gateway (OkHttp + SSE), probing |
| `:device` | Accessibility service, Root/AIDL service, VirtualDisplay, input injection |
| `:tools` | Device / file / network / notification / clipboard tool providers |
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
- [x] Semantic action safety gate
- [x] In-app upgrades via GitHub Releases
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
