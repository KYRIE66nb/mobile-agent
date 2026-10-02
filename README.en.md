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

- **Any app, no exceptions**: dual-path perception — semantic nodes for standard controls, pixel coordinates for self-drawn UIs (Flutter, games, image grids). There is no "unsupported app".
- **Safety before execution**: every action with external side effects passes a semantic gate (allow / confirm / block), backed by per-tool switches, allow-once / always grants, and approval dialogs — it asks rather than silently acting.
- **Foreground or background**: the accessibility service drives the main screen root-free; an optional Root virtual display works on a **background screen** while yours stays free — watch live via the floating overlay.
- **Runs once, replays forever**: proven flows solidify into deterministic recipes with zero model calls; scheduled/notification triggers run unattended with hard tool-scope boundaries.
- **Your data stays local**: conversations, artifacts, and verdict records live on-device with full export/import; outbound traffic goes only to the model/speech endpoints you configured.
- **50+ system-level tools**: file I/O, Office document generation & surgical editing, web fetch, notification quick-reply, clipboard, contacts/calendar, storage cleanup, memory freeing, restricted Root/Shizuku shell — an agent that is also a toolbox.

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

https://github.com/user-attachments/assets/85936c9f-b971-43ba-98e9-bf004bf29ebd

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

**Optional dedicated decision backend (Laya/Jev)**: Settings → Dedicated decision backend can point at an independent small-model service (the managed TypeSafe Jev API or a self-hosted Laya — see `deploy/laya/`). It supplies verdicts for the safety gate and, optionally, picks among locally built candidates for low-risk navigation. Default is **off** — behavior is identical to before until you opt in; it applies to interactive tasks only, not triggers/unattended runs in this version. Outbound traffic needs explicit consent and carries only the minimal decision context (never full chat history or screenshots); local validation stays authoritative — the service picks candidates, it cannot invent actions. SHADOW mode only records, ENFORCE applies verdicts. Verdict metadata is kept in `decision_records` for 30 days and is viewable in settings.

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
- **Cancel shake-ad jumps** — `auto_back` rules press Back the moment the foreground is thrown from a guarded app to a browser/shop landing page; in-app ad pages (same-package WebView/landing activities) are undone too when `class_pattern` matches their feature class names — rules without a pattern never touch normal same-app navigation;
- **Agent-programmable** — tell it "this app keeps popping lottery ads" or "one shake throws me into a store", and it writes a rule on the spot via `adguard_add_rule` so the next ad is killed instantly.

Per-rule cooldowns plus a global circuit breaker stop misconfigured loops; every block surfaces a toast, and `adguard_status` replays recent blocks. Requires the accessibility service; toggled in settings, off by default.

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/ad-guard.dark.png">
  <img src="docs/assets/ad-guard.light.png" alt="Ad guard bypass loop" width="100%">
</picture>

[Interactive ad-guard loop](docs/assets/ad-guard.html) — open locally; supports zoom, path tracing, and dark mode.

### Recipes

High-frequency flows should not be re-explored by the model every time. Recipes compile a proven flow into a **deterministic step sequence** — executed without a single model call, fast and repeatable:

- **Built-in templates**: `wechat_send_message` (open WeChat → search contact → type → send) works out of the box;
- **Agent-solidified**: after the agent drives a UI flow once with generic tools, `recipe_save` stores it as a template — next time `recipe_run` replays it in seconds, parameterized with `{contact}`, `{message}` placeholders;
- **Graceful degradation**: when a step times out or a node misses, the error carries the **failed step index** and the model continues from the breakpoint via `device_observe` / `device_action` instead of restarting.

Recipes run on the **main display in the foreground** (they drive the target app's UI) and require the accessibility service.

### Scheduled tasks (Triggers)

Hand "every morning" / "when a notification arrives" automations to triggers — they run **unattended** in the background and deliver results as local notifications:

- **Three kinds**: schedule (one-shot / daily / weekdays / weekly), notification matching (package + title + text regexes, AND), and interval polling (≥15 min); `AlarmManager` RTC_WAKEUP scheduling with automatic rescheduling after boot;
- **Scope is a hard boundary**: every task carries a `tool_scope` allowlist — the model inside a triggered run **only sees authorized tools** and out-of-scope calls are denied outright; the default scope is read-only (files, notification reads, calendar/contacts queries); external side effects (messaging, cleanup, device control) require explicit authorization at creation time;
- **Unattended ≠ unconstrained**: in-scope tools run without approval dialogs (nobody would see them anyway); per-trigger cooldown, daily run caps (persisted — a process restart cannot reset them), and a 3-strike circuit breaker auto-disable runaway tasks; the app's own notifications, ongoing notifications, and OTP/account-security notifications **never trigger**; notification bodies are injected as low-trust data, never as instructions;
- **Isolated history**: each task runs in a dedicated `Trigger·name` conversation (hidden from the main chat list and share targets), separate from day-to-day chats and fully replayable;
- **Quick replies**: `notifications_reply` sends text straight back to the source app through the notification's own RemoteInput (reply to chat messages without opening the app); it is an external side-effect tool — notification tasks need it explicitly in scope; `notifications_list` output is marked low-trust — notification bodies are third-party-controlled text, reference only, never instructions;
- **Usage**: tell the agent "read my schedule at 8 every morning" or "alert me when the boss messages me on WeChat" and it assembles the trigger via `trigger_save`; manage them under Settings → General → Scheduled tasks (enable, inspect scope, delete);
- **Platform limits**: battery savers and OEM background policies may delay firing (no exact-alarm permission is requested); notification matching needs Notification Access granted;
- **Spoken results**: enable "Speak task results" under Settings → General to have finished tasks read their summary aloud through the system TTS (offline, opt-in).

### Substitution timer (Naruto Mobile)

A purely **on-device** inference timer for the Substitution-jutsu cooldown in arena duels: no chat turn, no model call, no network — grant screen capture once, enter a match, and a countdown capsule floats above the game when either side's chakra dots drop:

- **Root-free capture**: MediaProjection + a `mediaProjection`-typed foreground service reads the main display; ImageReader keeps only the newest frame (slow devices drop, never queue);
- **Honest inference**: each side's dot slots are grid-sampled into HSV classification; a count is confirmed only after 3 consecutive frames agree, and only a stable `n→n−1` drop becomes a *suspected* substitution that starts a monotonic-clock cooldown — active skill costs, multi-dot jumps, flicker, and occlusions never masquerade as substitutes;
- **Explicit auto-start semantics**: opening the app idempotently walks grant → standby → monitoring; denial never re-prompts in a loop; pause-this-session and permanent-off are separate states; leaving the game hides the timer;
- **Calibration-driven**: the settings page frames both dot rows on a real in-memory game frame; coordinates, cooldown, and thresholds are persisted and adjustable;
- **Hard boundaries**: no input injection, no process memory reads, no detection bypass — it observes pixels and shows an estimated cooldown only (details: `docs/substitution-timer.md`).

### Models and tools

- **OpenAI-compatible gateway**: hand-rolled OkHttp + SSE streaming; Zhipu GLM / OpenAI / DeepSeek / any compatible endpoint, multi-profile switching, `reasoning_effort` passthrough;
- **Call resilience**: transient failures (408/429/5xx, connection drops, timeouts) retry with exponential backoff; when the primary profile fails outright before any output, the request can fail over to a chosen backup profile (Settings → General → "Model failover", on by default, with a dropdown to pick the backup model). Content already produced is never replayed, so output cannot duplicate;
- **50+ built-in tools**: device actions (observe / action / gesture / batch / wait_for), file I/O, document tools (PDF/Word/Excel extract; docx/xlsx generate and surgical edit), webpage fetching, notifications, clipboard, app launching, speech transcription (OpenAI / iFLYTEK-compatible), system maintenance (storage stats / cache clearing / memory freeing / **restricted shell** over Root or Shizuku), ad-guard rule management, recipe execution/saving, scheduled-task management, contacts/calendar queries (runtime-permission gated);
- **Multi-turn agent loop**: context compression, per-run step caps, instant cancel, every tool call persisted and replayable; read-only tool calls in one round **run in parallel** (writes stay sequential), and screen observations are compactly serialized with precomputed node centers to cut per-round token cost;
- **Context-overflow self-healing**: in-run compaction degrades gracefully — older tool results fold into references, finished steps collapse, then oversized results truncate to excerpts (full content stays retrievable via `history_read`); when the service explicitly reports a context overflow (context_length_exceeded, HTTP 413, ...) before any output, the runtime compresses and retries the same round once instead of killing the task; the profile's context window / max output fields drive the local budget — set them to the real endpoint limits to trigger fallbacks less often;
- **Capability overview**: Settings → Capabilities shows every system authorization (accessibility, notification access, usage access, overlay, notifications, microphone), advanced channels (Root/Shizuku), service configuration (model/speech), and each capability group's availability — with one-tap jumps to the matching grant screen;
- **Optional dedicated decision backend (Laya/Jev)**: safety-gate verdicts and low-risk navigation can be delegated to an independent small-model service (self-hosted Laya or managed TypeSafe Jev over the shared `/v1/systemone` protocol — see `deploy/laya/`); off by default, the two backends are mutually exclusive, outbound data needs explicit consent and carries only the minimal decision context (never full chat history or screenshots), local policy stays authoritative (the service picks candidates, it cannot invent actions), SHADOW only records while ENFORCE applies; keys are Keystore-encrypted and revocable, and verdict metadata is persisted for review;
- **Data management**: Settings → Data offers conversation export/import (a JSON backup covering conversations, messages, runs, tool calls, and context snapshots; imports merge idempotently without clobbering existing records) plus 30-day artifact pruning.

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

- [x] Vision path for screen operation (observe → coordinate-action loop)
- [x] Semantic action safety gate (allow / confirm / block verdicts)
- [x] In-app upgrades via GitHub Releases (check → download → installer)
- [x] Foreground / background virtual-display preference (with auto-fallback)
- [x] System cleanup (storage stats / cache clearing / memory freeing; root direct-clear with no-root fallback)
- [x] Ad guard (accessibility rule engine: splash skip / popup close / shake-ad interception, agent-programmable)
- [x] Recipes for high-frequency app flows (deterministic steps, parameterized, model takes over at breakpoints)
- [ ] More built-in recipes (Alipay, Meituan — version-adapted flows)
- [x] Trigger system: scheduled / notification-matched / interval tasks (scoped tools, unattended-safe)
- [x] Non-root degraded control via Shizuku shell channel (allow-listed commands, user-granted)
- [x] PDF / Word / Excel reading and generation (fully local, no network)
- [x] Surgical editing of existing documents (docx paragraph-level / xlsx cell-level; images and styles preserved; XML located by a tolerant segment scanner — quoted attributes, comments, CDATA, and nested same-name elements no longer misparse)
- [x] Notification RemoteInput quick replies (reply without opening the app; sensitive notifications excluded)
- [x] Capability/authorization overview page (system grants + advanced channels + capability groups + one-tap setup)
- [x] Pluggable standalone decision-model backend (Laya / Jev over `/v1/systemone`; off by default, interactive tasks only, SHADOW/ENFORCE modes)
- [x] Naruto substitution timer (root-free MediaProjection capture, fully local dot-count inference + floating cooldown, no model or network)

## Permissions and data boundaries

The app may request microphone, notifications, display-over-other-apps, boot startup, installed-app list, and accessibility access. Root, accessibility, screenshots, and external model calls are highly privileged — used **only after you explicitly enable or approve them**.

Model requests, speech transcription, and webpage access send selected content to the third-party services you configured. Read the [Privacy Notice](PRIVACY.md) before installing.

## Documentation

- [Product Definition](docs/PRODUCT.md) · [Design Guidelines](docs/DESIGN.md) · [Technical Architecture](docs/ARCHITECTURE.md) · [Implementation Plan](docs/PLAN.md) — currently in Chinese
- [Third-Party Notices](THIRD_PARTY_NOTICES.md) · [Security Policy](SECURITY.md)

## License

Source code is licensed under the [Apache License 2.0](LICENSE); third-party components remain under their respective licenses.
