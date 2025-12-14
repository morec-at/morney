# Task 3 Implementation Log

## Summary

Added initial SQLite schema migration and a simple migration runner, and wired migrations into `doctor` so the DB is initialized on first run.

## Artifacts

### Migration SQL
- `src/main/resources/db/migrations/V001__init.sql`
  - Tables: `schema_migrations`, `books`, `accounts`, `journal_entries`, `postings`, `imported_transactions`
  - Constraints:
    - FK integrity (SQLite FKs enabled via PRAGMA)
    - `accounts.type` and `postings.side` as enum-like CHECK constraints
    - `accounts.is_active` CHECK (0/1)
    - `postings.amount > 0`
    - unique dedup: `UNIQUE(book_id, source, dedup_key)` via index
  - Indexes for common filters (by book/date/status, etc.)

### Migration Runner
- `src/main/scala/morney/storage/Migrator.scala`
  - Applies ordered migrations from classpath resources
  - Records applied versions in `schema_migrations`
  - Executes SQL scripts as multiple statements (splitting by `;`)
  - Runs within a JDBC transaction

### CLI wiring
- `src/main/scala/morney/ui/CliApp.scala`
  - `doctor` now runs migrations after opening DB

### Tests
- `src/test/scala/morney/MigratorSpec.scala`
  - Uses a temp SQLite file and asserts core tables exist after migrating

