# Mobile Agent

[English](README.en.md) | [简体中文](README.md)

**An on-device agent that literally holds your phone** — an open-source Android agent that sees the screen and taps the screen for you.

[![Platform](https://img.shields.io/badge/Platform-Android%207.0%2B-3DDC84?logo=android&logoColor=white)](https://github.com/KYRIE66nb/mobile-agent)
[![Language](https://img.shields.io/badge/Kotlin-100%25-7F52FF?logo=kotlin&logoColor=white)](https://github.com/KYRIE66nb/mobile-agent)
[![Release](https://img.shields.io/github/v/release/KYRIE66nb/mobile-agent)](https://github.com/KYRIE66nb/mobile-agent/releases/latest)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue)](LICENSE)

> Early-stage project: APIs and interactions may still change. Try it on a test device first, and do not use it for payments or account-security operations.

## Why it is different

Desktop agents top out at the browser. This one's ceiling is **every app installed on your phone**.

No APIs, no web versions — the agent looks at the screen and taps it like a person would. WeChat, Meituan, Settings, Gallery: if a human can operate the UI, the agent can too.

### Highlights at a glance

- **Any app, no exceptions**: dual-path perception — semantic nodes for standard controls, pixel coordinates for self-drawn UIs (Flutter, games, image grids).
- **Safety before execution**: external side-effect calls pass a semantic gate (allow / confirm / block) — it asks rather than silently acting.
- **Foreground or background**: the accessibility service drives the main screen root-free; an optional Root virtual display works in the background while your screen stays yours.
- **Desktop agents welcome**: an authenticated loopback bridge + adb forward + a zero-dependency MCP server let a computer agent read and drive the phone.
- **Runs once, replays forever**: proven flows solidify into deterministic recipes; scheduled/notification triggers run unattended with hard tool-scope boundaries.
- **Your data stays local**: conversations, artifacts, and verdict records live on-device with full export; outbound traffic goes only to the model/speech endpoints you configured.

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/agent-loop.dark.png">
  <img src="docs/assets/agent-loop.light.png" alt="Mobile Agent execution loop" width="100%">
</picture>

[Interactive architecture map](docs/assets/agent-loop.html) — open locally; supports zoom, path tracing, and dark mode.

## Screenshots

Real captures from a physical device — no mockups. The dark floating pill on some frames is the agent overlay (expand / artifacts / settings / dismiss); it streams what the agent is doing while a task runs.

### One task, end to end

<table>
  <tr>
    <td width="25%"><img src="docs/assets/screenshots/chat-empty.png"></td>
    <td width="25%"><img src="docs/assets/screenshots/ask-user.png"></td>
    <td width="25%"><img src="docs/assets/screenshots/tool-approval.png"></td>
    <td width="25%"><img src="docs/assets/screenshots/tool-calls.png"></td>
  </tr>
  <tr>
    <td><b>Ask.</b> Describe the task in the composer — model chip and per-send reasoning effort are right there.</td>
    <td><b>Clarify.</b> Ambiguous requests pause on a structured question with tappable options instead of guessing.</td>
    <td><b>Approve.</b> Tools marked "ask every time" wait for your verdict — deny, allow once, or always.</td>
    <td><b>Act.</b> Tool calls stream as collapsible cards: read-only calls run in parallel, writes stay sequential.</td>
  </tr>
</table>

### Conversations and tool management

<table>
  <tr>
    <td width="20%"><img src="docs/assets/screenshots/chat.png"></td>
    <td width="20%"><img src="docs/assets/screenshots/history-drawer.png"></td>
    <td width="20%"><img src="docs/assets/screenshots/tools.png"></td>
    <td width="20%"><img src="docs/assets/screenshots/tools-2.png"></td>
    <td width="20%"><img src="docs/assets/screenshots/tools-3.png"></td>
  </tr>
  <tr>
    <td>Markdown-rendered answers; the composer carries voice input, attachments, thinking toggle, and the model chip.</td>
    <td>Pinned / recent conversations with search — local storage only, nothing leaves the device.</td>
    <td>Every tool group is switchable, each with its own permission level (allow / while in use / ask every time).</td>
    <td>Unavailable tools say exactly why — here screen operation wants Root, and notifications want access first.</td>
    <td>"Ask user" and "Scheduled tasks" groups — clarification on ambiguity, triggers run unattended inside their authorized scope.</td>
  </tr>
</table>

### Settings: safety gate, failover, capabilities, models

<table>
  <tr>
    <td width="25%"><img src="docs/assets/screenshots/settings-general.png"></td>
    <td width="25%"><img src="docs/assets/screenshots/settings-general-2.png"></td>
    <td width="25%"><img src="docs/assets/screenshots/settings-general-3.png"></td>
    <td width="25%"><img src="docs/assets/screenshots/personalize.png"></td>
  </tr>
  <tr>
    <td>Theme, language, step cap, the action safety gate, and the dedicated decision backend entry.</td>
    <td>Decision backend picker, model failover, spoken results, ad guard, scheduled tasks.</td>
    <td>Device-operation target (auto / main / background), verbose logs, persistent overlay, Root status.</td>
    <td>Custom instructions — long-term preferences, habits, and reply style the agent remembers.</td>
  </tr>
  <tr>
    <td width="20%"><img src="docs/assets/screenshots/capabilities.png"></td>
    <td width="20%"><img src="docs/assets/screenshots/capabilities-groups.png"></td>
    <td width="20%"><img src="docs/assets/screenshots/model-profile.png"></td>
    <td width="20%"><img src="docs/assets/screenshots/model-effort.png"></td>
    <td width="20%"><img src="docs/assets/screenshots/model-vision.png"></td>
  </tr>
  <tr>
    <td>Capability overview: every system authorization and advanced channel, with one-tap jumps to grant screens.</td>
    <td>Each tool group reports its status — available, limited, or unsupported — with the blocker named.</td>
    <td>Model profiles: base URL, model ID, context window, up to 20 saved configurations.</td>
    <td>Auto-compaction threshold plus reasoning-effort levels with one-tap official presets.</td>
    <td>The vision-model toggle — screenshots ride along with observations so the model can tap by pixel.</td>
  </tr>
</table>

### Voice, data, updates

<table>
  <tr>
    <td width="25%"><img src="docs/assets/screenshots/voice.png"></td>
    <td width="25%"><img src="docs/assets/screenshots/data-usage.png"></td>
    <td width="25%"><img src="docs/assets/screenshots/data-backup.png"></td>
    <td width="25%"><img src="docs/assets/screenshots/about.png"></td>
  </tr>
  <tr>
    <td>Speech transcription over OpenAI-compatible or iFLYTEK endpoints, with a test-connection button.</td>
    <td>Per-model token usage, cache cleanup, and artifact retention policy.</td>
    <td>Full JSON export/import of the conversation store, merged idempotently by record key.</td>
    <td>Version info and in-app updates straight from GitHub Releases.</td>
  </tr>
</table>

### Naruto substitution timer — real match footage

https://github.com/user-attachments/assets/2d88800f-3ffe-45b2-bfee-56f474d6c136

Arena footage: enemy substitution "estimated recovered", own suspected substitution counting down at 7.0s — all inferred from pixels on-device, no model call, no network.

<table>
  <tr>
    <td width="33%"><img src="docs/assets/screenshots/substitution-settings.png"></td>
    <td width="33%"><img src="docs/assets/screenshots/substitution-calibration.png"></td>
    <td width="33%"><img src="docs/assets/screenshots/substitution-ingame.png"></td>
  </tr>
  <tr>
    <td>Enable, auto-start on app open, cooldown and sampling settings — status and session controls inline.</td>
    <td>Dot-slot calibration draws both players' chakra rows on a real captured frame; sliders fine-tune the boxes.</td>
    <td>In a match, a countdown capsule floats over the game — enemy / own cooldown state, monitored locally.</td>
  </tr>
</table>

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

**Optional dedicated decision backend (Laya/Jev)**: safety-gate verdicts can be delegated to an independent small-model service (self-hosted Laya or managed TypeSafe Jev — see `deploy/laya/`). Off by default; outbound traffic needs explicit consent and carries only the minimal decision context; local policy stays authoritative — the service picks candidates, it cannot invent actions. SHADOW/ENFORCE modes; verdict records are reviewable in settings.

### Two device-control engines

- **Accessibility service** (no root): node-tree reads, taps, text input, swipes, foreground-app tracking;
- **Root virtual display** (optional): a standalone `RootDeviceService` works on a `VirtualDisplay` — screenshots and touch injection run on a **background screen** while your main screen stays yours; watch the agent live via the floating overlay.

**Execution target preference** (Settings → General → Device operation target):

| Mode | Behaviour |
|---|---|
| **Auto** (default) | The agent picks main/background per task; when the virtual display is unavailable it **falls back to the main screen**, disclosed in both the approval text and tool results |
| **Main screen only** | Always runs in the foreground — saves rootless devices a doomed virtual-display attempt |
| **Background only** | Uses the virtual display or fails — **never silently touches your main screen** |

**Shizuku fallback channel**: on non-rooted devices with Shizuku installed and granted, `system_shell` runs allow-listed management commands (am/pm/dumpsys/settings/input/content…; pipes and chaining rejected) as shell(uid 2000) — query app info, clear caches, inject key events without taking over your screen.

### Desktop control (USB · Android Use Bridge)

The on-phone agent can act by itself — or **lend its hands to an agent on your computer**: the app ships an authenticated HTTP bridge bound only to `127.0.0.1:17325`, exposed to a zero-dependency Python MCP stdio server over `adb forward`. Claude Code, Devin, or any MCP client reads the screen, taps, and types as if the phone were a remote device.

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/desktop-bridge.dark.png">
  <img src="docs/assets/desktop-bridge.light.png" alt="Desktop agent control chain" width="100%">
</picture>

[Interactive diagram](docs/assets/desktop-bridge.html) (download and open in a browser — zoom, path tracing, dark mode)

- **Safety boundaries by design**: HTTP is loopback-only; the Bearer pairing token can only be read through `adb shell`; the service is non-exported and the App settings toggle is authoritative; bridge sessions are exclusive and the desktop client never blind-retries a possibly-executed action — a dropped connection reports "uncertain", never a duplicate tap.
- **21 `aua_*` tools**: `observe` (node tree + screenshot), `find` / `tap` / `long_press` / `drag` / `swipe` / `type_text` / `press_button` / `launch_app`, `screenshot` (system capture saved in the phone gallery), plus `batch`, `scroll_find`, `collect_list`, `apps`, `screen` (interactive local preview), and the `setup` / `ready` / `doctor` / `metrics` provisioning suite.
- **Same gateway as the in-app agent**: selectors resolve accessibility nodes by text/description/id/type/regex and fall back to screenshot pixel coordinates; Unicode text splits at grapheme boundaries, scrolls and batches are verified rather than assumed.

Desktop 0.2.0 adapts iPhone-use 0.3.15: target-bound input continuation, shared-session preview controls, and readiness checks that fail when required observations are missing. The desktop bridge requires Android 11+. See [usage, upstream comparison and validation limits](docs/desktop-control.md).

Provisioning path (verified on a Redmi, Android 16):

```bash
python3 desktop/server/android_use.py   # register as an MCP stdio server
# aua_setup: discover → configure → enable_accessibility → pair → forward → ready
# Enable the bridge: Settings → General → Desktop control (USB) — on release builds the
# in-app toggle is the only authority (run-as is unavailable on non-debuggable builds)
```

<table>
  <tr>
    <td width="50%"><img src="docs/assets/screenshots/bridge-settings.png"></td>
    <td width="50%"><img src="docs/assets/screenshots/desktop-preview.png"></td>
  </tr>
  <tr>
    <td><b>Phone side</b>: Settings → General → Desktop control (USB) — one toggle, status text shows the listening port and pairing path.</td>
    <td><b>Desktop side</b>: `aua_screen` opens a local preview with tap, drag and navigation controls. Pause and wait for confirmation before authentication; the last frame remains visible and controls are disabled.</td>
  </tr>
</table>

### System cleanup

Say "clean up my phone" or "free some memory" — three paths chosen automatically by permission level: `system_storage_stats` (per-app or Top-N; missing Usage access opens the grant page), `system_clear_cache` (root direct-clears `cache/code_cache` and reports freed bytes — sign-in state untouched; without root it degrades to on-screen steps), and `system_free_memory` for background RAM. Cleanup calls pass the safety gate and an approval dialog.

Captured on a real device — the whole run after one "clean up my phone":

<table>
  <tr>
    <td width="33%"><img src="docs/assets/screenshots/cleanup-approval.png"></td>
    <td width="33%"><img src="docs/assets/screenshots/cleanup-askuser.png"></td>
    <td width="33%"><img src="docs/assets/screenshots/cleanup-result.png"></td>
  </tr>
  <tr>
    <td>Cache clearing goes through the safety gate: scope, risk notes, and the exact parameters are shown before anything runs.</td>
    <td>Mid-task it asks back — kill background apps for RAM or stick to cache? Structured options, one tap to answer.</td>
    <td>Wrap-up: per-app cache ranking, what was cleaned, and how to batch the rest — it even warns you not to hit "Clear data".</td>
  </tr>
</table>

### Ad guard

Popups and shake ads flash too briefly for a model loop — blocking runs on a **deterministic rule engine** inside the accessibility event stream (millisecond latency, no model); the model's job is configuring rules on demand. Splash "Skip" auto-taps, ad-marked popups auto-close, and `auto_back` rules undo jumps to landing pages (in-app ad pages are matched by class pattern too). Tell it "this app keeps popping lottery ads" and it writes a rule on the spot via `adguard_add_rule`. Per-rule cooldowns plus a global circuit breaker, block replays via `adguard_status`; toggled in settings, off by default.

Real run — "block Douyin's splash ads", watched live in the floating window:

<table>
  <tr>
    <td width="50%"><img src="docs/assets/screenshots/adguard-approval.png"></td>
    <td width="50%"><img src="docs/assets/screenshots/adguard-result.png"></td>
  </tr>
  <tr>
    <td>Rule construction in the open: candidate skip texts, context conditions weighed one by one, and the new rule lands only after approval — it even caught its own typo mid-way, deleted the rule and rebuilt it.</td>
    <td>Done: guard switched on, accessibility connected, 3 rules (2 built-in + the new "Douyin splash skip"); it discloses that the package name couldn't be verified on-device and offers a popup rule as a follow-up.</td>
  </tr>
</table>

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/ad-guard.dark.png">
  <img src="docs/assets/ad-guard.light.png" alt="Ad guard bypass loop" width="100%">
</picture>

[Interactive ad-guard loop](docs/assets/ad-guard.html) — open locally; supports zoom, path tracing, and dark mode.

### Recipes

High-frequency flows should not be re-explored every time — recipes compile a proven flow into a **deterministic step sequence** with zero model calls. `wechat_send_message` works out of the box; flows the agent drove once can be saved via `recipe_save` as parameterized templates (`{contact}`, `{message}`) and replayed by `recipe_run`; a timed-out step returns its **index** so the model resumes at the breakpoint instead of restarting. Recipes run in the foreground and need the accessibility service.

### Scheduled tasks (Triggers)

Hand "every morning" / "when a notification arrives" automations to triggers — they run **unattended** in the background and deliver results as local notifications:

- **Three kinds**: schedule (one-shot / daily / weekdays / weekly), notification matching (package + title + text regexes), and interval polling (≥15 min); RTC_WAKEUP scheduling, rescheduled after boot;
- **Scope is a hard boundary**: each task carries a `tool_scope` allowlist — out-of-scope calls are denied and the default is read-only; unattended runs are approval-free but bounded by cooldowns, daily caps (persisted), and a 3-strike circuit breaker; sensitive notifications never trigger; notification bodies are injected as low-trust data;
- **Isolated history + quick replies**: each task runs in its own `Trigger·name` conversation away from the main list; `notifications_reply` answers chat messages through the notification's RemoteInput without opening the app (must be in scope explicitly).

Say "read my schedule at 8 every morning" to create one; battery policies may delay firing, and notification matching needs Notification Access. Optional offline TTS can speak results aloud.

### Substitution timer (Naruto Mobile)

A purely **on-device** inference timer for the Substitution-jutsu cooldown in arena duels: no chat turn, no model call, no network — grant screen capture once, enter a match, and a countdown capsule floats above the game when either side's chakra dots drop:

- **Root-free capture**: MediaProjection + a `mediaProjection`-typed foreground service reads the main display; ImageReader keeps only the newest frame (slow devices drop, never queue);
- **Honest inference**: each side's dot slots are grid-sampled into HSV classification; a count is confirmed only after 3 consecutive frames agree, and only a stable `n→n−1` drop becomes a *suspected* substitution that starts a monotonic-clock cooldown — active skill costs, multi-dot jumps, flicker, and occlusions never masquerade as substitutes;
- **Explicit auto-start semantics**: opening the app idempotently walks grant → standby → monitoring; denial never re-prompts in a loop; pause-this-session and permanent-off are separate states; leaving the game hides the timer;
- **Calibration-driven**: the settings page frames both dot rows on a real in-memory game frame; coordinates, cooldown, and thresholds are persisted and adjustable;
- **Hard boundaries**: no input injection, no process memory reads, no detection bypass — it observes pixels and shows an estimated cooldown only (details: `docs/substitution-timer.md`).

### Models and tools

- **OpenAI-compatible gateway**: hand-rolled OkHttp + SSE streaming; GLM / OpenAI / DeepSeek / any compatible endpoint, multi-profile switching, `reasoning_effort` passthrough;
- **Call and run resilience**: transient failures retry with backoff, a failed primary profile can fail over to a chosen backup, produced content is never replayed; context overflow compacts in three stages and retries the round once instead of killing the task;
- **Multi-turn agent loop**: step caps, instant cancel, every tool call persisted and replayable; read-only calls run in parallel, observations are compactly serialized to cut per-round token cost;
- **50+ built-in tools**: device actions, file/document I-O and surgical editing, webpage fetching, notifications, clipboard, app launching, speech transcription, system maintenance, ad guard, recipes, scheduled tasks, contacts/calendar — details in the sections above.

## Architecture

Six pure-Kotlin modules, zero frameworks (no Hilt / Koin / MVVM scaffolding); the core runtime never touches the Android SDK:

| Module | Responsibility |
|---|---|
| `:app` | Compose UI, manual wiring (`PrototypeApplication`), overlay, updater |
| `:agent-core` | `ChatRuntime` loop, tool contracts, `DecisionGate` |
| `:model` | OpenAI-compatible gateway (OkHttp + SSE), probing |
| `:device` | Accessibility service, ad-guard engine, Root/AIDL service, VirtualDisplay, input injection |
| `:tools` | Device / file / network / notification / clipboard / system-maintenance / ad-guard / recipe tool providers |
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
- Reasoning effort: keep the parameter name `reasoning_effort`; the editor can one-tap fill official levels (GLM-5.3: `low`/`high`/`max`; GLM-5.2: `minimal`–`max` full range); the composer chip switches effort per send
- Auto-compaction threshold: adjustable on the same editor page (30%–95%, default 80%) — raise it for large-window models; set the context length to the model's real window (e.g. GLM-5.3's 1M)

## Roadmap

**Shipped**: vision-coordinate operation, semantic safety gate, in-app upgrades, virtual-display background execution (auto-fallback), system cleanup, ad guard, recipes, triggers, Shizuku fallback shell, Office document I/O and surgical editing, notification quick replies, capability overview, pluggable decision backends, Naruto substitution timer, USB desktop-control bridge.

**In progress**

- [ ] More built-in recipes (Alipay, Meituan — version-adapted flows)

## Permissions and data boundaries

The app may request microphone, notifications, display-over-other-apps, boot startup, installed-app list, and accessibility access. Root, accessibility, screenshots, and external model calls are highly privileged — used **only after you explicitly enable or approve them**.

Model requests, speech transcription, and webpage access send selected content to the third-party services you configured. Read the [Privacy Notice](docs/PRIVACY.md) before installing.

## Documentation

- [Product Definition](docs/PRODUCT.md) · [Design Guidelines](docs/DESIGN.md) · [Technical Architecture](docs/ARCHITECTURE.md) · [Implementation Plan](docs/PLAN.md) — currently in Chinese
- [Changelog](CHANGELOG.md) · [Third-Party Notices](docs/THIRD_PARTY_NOTICES.md) · [Security Policy](SECURITY.md)

## License

Source code is licensed under the [Apache License 2.0](LICENSE); third-party components remain under their respective licenses.
