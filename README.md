# EchoPanel

**Coordinated AI Interview Panel** — an adaptive voice interview platform where
multiple role-based AI interviewers (Technical, Product/Business, Behavioural,
Customer, Hiring Manager) run a single live voice session, share candidate
context, and challenge each other's conclusions the way a real human panel
would.

Built for EchoSphere 2026 (Round II) — PS11: Coordinated AI Interview Panel.

**New to this project? Read [`ARCHITECTURE.md`](./ARCHITECTURE.md) first.**
It walks through the whole system end to end — what problem it solves, how
one interview turn flows through every file, why the Context Graph exists,
and what's real vs. what still needs your Agora/OpenAI credentials. This
README is the quick-start; that document is the actual explanation.

## How it fits together

This is one project with two halves that talk to each other over HTTP:

```
EchoPanel/
├── backend/     FastAPI service — the reasoning layer (Context Graph,
│                Turn Arbiter, personas, difficulty control, reports).
│                Also exposes the tool-calling hooks Agora's
│                Conversational AI Engine invokes mid-call.
├── android/     Kotlin + Jetpack Compose client — Clean Architecture,
│                Hilt DI. Runs the live voice call via Agora's SDK and
│                renders the transcript, AI-disclosure banner, and
│                final report.
├── Makefile     One place to install, test, and run the backend.
└── setup.sh     First-time setup for the backend virtualenv + .env.
```

**Agora's Conversational AI Engine is the third leg**: it owns real-time
ASR, sub-second-latency audio, noise suppression, and native interruption
during the call. Our backend is the "brain" it calls into via tool-calling
hooks (`POST /agora/turn`, `POST /agora/greeting`) to decide what each
persona says next; our Android app is the client that joins the Agora
channel and displays what's happening.

```
Candidate (Android app, mic via Agora SDK)
        │
        ▼
Agora Conversational AI Engine  ──(ASR text)──►  EchoPanel backend
        │                                          │  Context Graph
        │                                          │  Turn Arbiter
        │                                          │  Persona (LLM)
        ▼                                          │  Difficulty Controller
   Agora TTS  ◄──────────(spoken_text)─────────────┘
        │
        ▼
Candidate hears the next persona's question
```

## Quick start

### 1. Backend

```bash
./setup.sh          # creates .venv, installs deps, copies .env.example → .env
# edit backend/.env with your OPENAI_API_KEY and Agora credentials
make run            # starts the API at http://localhost:8000
make test           # runs the backend test suite
```

Interactive API docs once running: http://localhost:8000/docs

### 2. Android app

Open `android/` in Android Studio — it builds with the included Gradle
wrapper, no local Gradle install needed.

- Emulator/device talking to a backend on your machine: the default
  `BACKEND_BASE_URL` (`http://10.0.2.2:8000/`) already points at the
  emulator's host-loopback address.
- Physical device: change `BACKEND_BASE_URL` in `android/app/build.gradle.kts`
  to your machine's LAN IP.
- Add your `AGORA_APP_ID` in the same file once you've created an app in the
  [Agora console](https://console.agora.io).

### 3. Try it end-to-end

1. `make run` (backend)
2. Run the Android app (emulator is fine for the UI/API flow; a real device
   is needed to test live mic audio through Agora)
3. Start a session → accept the AI-disclosure consent dialog → the app joins
   the Agora channel and the panel begins

## What's implemented vs. what needs your credentials

| Piece | Status |
|---|---|
| Context Graph, Turn Arbiter, difficulty control, contradiction/vagueness detection | ✅ implemented, unit-tested |
| 5 personas (Technical, Product/Business, Behavioural, Customer, Hiring Manager) | ✅ implemented |
| Evidence-linked final report | ✅ implemented |
| AI-disclosure banner + consent flow | ✅ implemented (Compose UI + backend consent logging) |
| Cheating/integrity detection (text signals + client-reported video/audio signals) | ✅ implemented, unit-tested — see `services/cheating_detector.py` |
| On-device face-count/gaze + app-background proctoring signals | ✅ implemented — `android/.../data/proctoring/FaceProctoringAnalyzer.kt` |
| Shared live script panel (AI-suggested + interviewer's own questions) | ✅ implemented — `api/script.py` + `presentation/interview/ProctoringAndScriptComponents.kt` |
| OpenAI-backed persona reasoning | ✅ implemented — needs your `OPENAI_API_KEY` |
| Live Agora voice call (ASR/TTS/interruption) | 🔶 scaffolded — needs your Agora App ID/credentials and the Conversational AI SDK module from Agora's console |
| Agora token server endpoint | ✅ implemented — `POST /agora/token/{session_id}` |
| Auth on backend endpoints | ⬜ not implemented — anyone with a session ID can call any endpoint; fine for a hackathon demo, not for production |

## Cheating detection & the shared script panel

Two additions layer on top of the core panel without changing its shape:

- **Cheating/integrity detection** (`backend/app/services/cheating_detector.py`) runs two kinds of check. Text signals (a sudden jump from halting to polished answers, copy-paste-style formatting, an answer that arrives implausibly fast for its length) are computed server-side on every turn, no client cooperation needed. Client signals (multiple faces on camera, no face visible, sustained gaze away from the screen, a second voice detected, app backgrounded, screen mirroring) are detected **on-device** by the Android app and reported as small structured signals — never raw audio or video — via `POST /proctoring/{session_id}/signal`. Signals accumulate into evidence-linked `CheatFlag`s: every flag traces back to the exact signals that raised it, the same evidence-linked philosophy as the final report's `VerdictItem`s. Both the interviewer's screen and the candidate's own screen poll the same `GET /proctoring/{session_id}/status`, so nobody sees a different picture of what was flagged — consistent with the project's transparency-by-design stance.
- **Shared live script panel** (`backend/app/api/script.py`) is a running list of next-questions both interviewer and candidate see identically. Entries are either AI-suggested (grounded in the same Context Graph slice the personas already use, via `generate_suggested_questions()`) or typed live by the human interviewer — both land in the same shared list via `POST /script/{session_id}/custom`.

## Repo map (details)

- `backend/app/models/schemas.py` — Context Graph, claims, sessions, reports
- `backend/app/services/turn_arbiter.py` — who speaks next, including the
  PS11 example scenario (Product independently challenges an unchallenged
  Technical claim)
- `backend/app/services/contradiction_detector.py` — vagueness + contradiction flags, computed before scoring
- `backend/app/services/difficulty_controller.py` — rolling competence → recall/applied/edge_case
- `backend/app/personas/` — per-role system prompts + OpenAI calls
- `backend/app/api/agora_hooks.py` — the endpoints Agora's engine calls mid-interview
- `backend/tests/test_services.py` — includes a dedicated test for the PS11 example scenario
- `android/app/src/main/java/com/echopanel/app/` — `domain/` (pure Kotlin), `data/` (Retrofit + Agora SDK), `presentation/` (Compose screens + ViewModels), `di/` (Hilt)
