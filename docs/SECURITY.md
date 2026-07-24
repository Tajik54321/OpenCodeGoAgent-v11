# Security model

- API keys and database passwords are encrypted with an AES-GCM key generated inside Android Keystore.
- Provider metadata and database profile JSON files contain only secret references.
- The audit trail redacts authorization headers, API keys, passwords, tokens and changed file content.
- Every AI capability is project-scoped and independently permissioned.
- Remote provider calls require the `network` permission.
- Secret files such as `.env`, PEM and key files require `read_secrets`.
- Destructive file, process and SQL operations require `destructive`.
- AI file writes, patches, moves and deletes create restorable checkpoints outside the project root.
- Destructive AI SQL creates a safety backup first.
- ZIP import limits entry count and expanded size and blocks Zip Slip.
- Project paths and runtime paths are canonicalized.
- Static servers bind to `127.0.0.1` unless LAN is explicitly enabled.
- MariaDB/PostgreSQL are configured to bind to localhost.
- Android application backup is disabled.

Clear-text HTTP is permitted because localhost/LAN model servers and development sites commonly use HTTP. It should not be used for remote untrusted provider endpoints.

## Distribution security trade-off

The `sideload` flavor targets API 28 only so Android still permits explicitly imported app-private native runtime executables. It is intended for direct trusted installation, not Play distribution. Runtime ZIPs remain opt-in and must pass manifest/hash/ABI checks.

The `modern` flavor targets API 37 and refuses imported executable runtimes. Its native engines must be packaged as reviewed APK libraries (`liboc_*.so`), matching Android's executable-code policy.

SQL permissions use a conservative multi-statement classifier: read-only access cannot be bypassed with appended statements or write CTEs. AI read operations also run through SQLite read-only handles or MariaDB/PostgreSQL read-only transactions. Destructive operations trigger a separate project permission plus a safety backup. Database-native users and grants remain the final enforcement layer for MariaDB/PostgreSQL.
