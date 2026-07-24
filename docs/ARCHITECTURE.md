# Architecture

## Application layers

- `project/` — isolated project roots, metadata, templates and safe path resolution.
- `runtime/` — verified ARM64 runtime pack installation and environment construction.
- `terminal/` — supervised native processes with bounded logs and timeouts.
- `server/` — static HTTP server, PHP/Node/Python launch and foreground service.
- `database/` — embedded SQLite and runtime-backed MariaDB/PostgreSQL hosting.
- `ai/` — providers, encrypted credentials, conversations, permissions and tool agent.
- `security/` — Android Keystore encrypted storage.
- `audit/` and `history/` — redacted audit trail and restorable file checkpoints.
- activities — Agent, Projects, Files, Editor, Terminal, Servers, Databases, Providers, Preview and Timeline.

## Isolation

Projects, databases, runtime packs, credentials, process logs, conversations and checkpoints are kept under the application's private storage. Project tool paths are canonicalized and cannot escape the selected root. Runtime executable paths are restricted to installed runtime packs or Android system binaries.

## Runtime model

The APK provides orchestration without Termux. In the `sideload` flavor, native Android/Bionic engines are installed from explicit verified ARM64 runtime packs. In the `modern` flavor, executable engines must be embedded as reviewed APK native libraries and imported runtime execution is rejected. Desktop/x86 Linux packages are never treated as Android executables.
