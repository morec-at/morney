# Task 4 Implementation Log

## Summary

Implemented a SQLite-backed repository layer (`LedgerRepository`) for basic CRUD on books, accounts, and journal entries, including listing entries by date range and fetching postings.

## Artifacts

### Repository Interface
- `src/main/scala/morney/storage/LedgerRepository.scala`
  - `createBook`, `listBooks`
  - `createAccount`, `setAccountActive`, `listAccounts`
  - `createJournalEntry`, `getJournalEntry`, `listJournalEntries`
  - `DateRange` helper for date filtering

### SQLite Implementation
- `src/main/scala/morney/storage/SqliteLedgerRepository.scala`
  - Uses JDBC wrapped in `ZIO.attemptBlocking`
  - Writes `journal_entries` + `postings` in a single transaction
  - Loads postings in bulk for `listJournalEntries` via `IN (...)` query
  - Encodes enums to DB strings consistent with migration CHECK constraints

### Tests
- `src/test/scala/morney/RepositorySpec.scala`
  - Creates temp DB, runs migrations, exercises book/account and entry flows

