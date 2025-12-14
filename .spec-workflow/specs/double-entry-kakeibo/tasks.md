# Tasks Document

- [x] 1. Project scaffolding (Scala + ZIO + SQLite)
  - Files: `build.sbt`, `project/`, `src/main/scala/`, `src/test/scala/`, `README.md` updates
  - Define module/package structure and a CLI entrypoint (ZIO-based)
  - Add dependencies (minimum): ZIO, SQLite JDBC, ZIO Config (optional), ZIO Test, zio-json (for export) / CSV parser (for import)
  - Decide storage location for the SQLite DB file (per book or per workspace) and how it is configured (env/args/config file)
  - _Requirements: 6, 9_
  - _Prompt: Role: Scala developer | Task: Create minimal scaffolding for a local-first double-entry household ledger using Scala + ZIO and a SQLite-backed store with a CLI entrypoint | Restrictions: Keep structure modular (domain/services/storage/ui); avoid framework lock-in beyond ZIO | Success: App runs locally, CLI starts, DB path is configurable_

- [x] 2. Define domain models and invariants
  - Files: `src/main/scala/morney/domain/` (e.g., `Models.scala`, `Validation.scala`)
  - Model: Book, Account, JournalEntry, Posting, ImportedTransaction
  - Implement validations: debit/credit totals must match; amounts are non-negative integers; required IDs/date fields
  - _Requirements: 1, 2, 5, 8_
  - _Prompt: Role: Scala domain engineer | Task: Implement the core domain models (case classes) and validation invariants for double-entry bookkeeping | Restrictions: No DB code in domain; validations must be reusable by services and CLI | Success: Invalid entries are rejected with clear errors; models cover all required fields_

- [x] 3. Design and implement SQLite schema + migrations
  - Files: `src/main/resources/db/migrations/`, `src/main/scala/morney/storage/Migrator.scala`
  - Tables: books, accounts, journal_entries, postings, imported_transactions (+ indexes)
  - Constraints: FK integrity; unique dedup key; account active flag; posting amounts positive
  - _Requirements: 1, 2, 6, 8_
  - _Prompt: Role: Storage engineer | Task: Create an initial SQLite schema and a simple migration runner (ordered SQL files) for the ledger | Restrictions: Use SQLite features (FKs, indexes); keep schema evolution via migrations | Success: DB initializes cleanly and enforces core constraints_

- [x] 4. Implement repository layer (LedgerRepository)
  - Files: `src/main/scala/morney/storage/LedgerRepository.scala`, `src/main/scala/morney/storage/SqliteLedgerRepository.scala`
  - CRUD for: books, accounts, journal entries (create/list/update), imported transactions
  - Filtering: date range, accountId, status
  - _Requirements: 1, 2, 6, 8_
  - _Prompt: Role: Scala backend developer | Task: Implement repository methods for storing and retrieving ledger data using SQLite (JDBC wrapped in ZIO) | Restrictions: Keep SQL isolated; transactions for multi-row writes (entry+postings) | Success: Repository supports all use-cases and preserves data integrity_

- [x] 5. Account management use-cases (defaults + lifecycle)
  - Files: `src/main/scala/morney/services/AccountsService.scala`
  - Create default chart of accounts on new book
  - Add/edit/disable accounts; prevent deletion when referenced
  - _Requirements: 1, 6_
  - _Prompt: Role: Scala backend dev | Task: Implement account management workflows including default accounts and safe disabling | Restrictions: No hard-deletes for used accounts | Success: New books get defaults; used accounts cannot be deleted; inactive accounts are hidden by default_

- [x] 6. Journal entry service (create/update + validation)
  - Files: `src/main/scala/morney/services/JournalService.scala`
  - Create entry with postings; validate debit/credit equality
  - Update policy: allow editing memo/date/postings (initially), maintain updatedAt
  - _Requirements: 2, 5_
  - _Prompt: Role: Scala backend developer | Task: Implement journal entry creation/editing service with strict double-entry validation | Restrictions: All writes in a single transaction; return actionable validation errors | Success: Mismatched totals are rejected; saved entries can be listed and viewed_

- [x] 7. Quick entry helpers (expense / income / transfer / card payment)
  - Files: `src/main/scala/morney/services/QuickEntryService.scala`
  - Generate correct postings for:
    - Expense: debit expense, credit payment source (cash/bank/card liability)
    - Income: debit asset, credit revenue
    - Transfer: debit to-asset, credit from-asset
    - Card payment: debit card liability, credit bank asset
  - Support split expenses (multiple expense debits)
  - _Requirements: 3, 4, 5_
  - _Prompt: Role: Scala backend developer | Task: Implement quick-entry functions that map household workflows into valid journal entries (including split lines) | Restrictions: Must reuse journal service validations; keep UI-independent | Success: Generated postings match requirements and always balance_

- [x] 8. Reporting: balances, P&L, ledger views
  - Files: `src/main/scala/morney/services/ReportsService.scala`
  - Balance summary (assets/liabilities/equity) as-of date
  - Income statement (revenue/expenses) for date range
  - Ledger view per account (running balance)
  - _Requirements: 7_
  - _Prompt: Role: Scala data/finance engineer | Task: Implement report calculations derived solely from postings | Restrictions: Keep calculations deterministic and testable; avoid float math | Success: Reports match expected totals and reconcile with postings_

- [x] 9. CSV import pipeline + pending transactions
  - Files: `src/main/scala/morney/services/ImportService.scala`, `src/main/scala/morney/importers/GenericCsvImporter.scala`
  - Import CSV into `ImportedTransaction` with configurable column mapping
  - Dedup strategy using `dedupKey` (external id or stable hash)
  - Pending list; ignore; link/confirm into journal entry
  - _Requirements: 8_
  - _Prompt: Role: Scala backend developer | Task: Implement CSV import, dedup, and a pending→confirmed workflow that creates linked journal entries | Restrictions: Imported rows must not affect reports until confirmed; confirm creates balanced entries | Success: Duplicate detection works; pending items can be confirmed/ignored; confirmed items link to entries_

- [x] 10. Export + backup/restore
  - Files: `src/main/scala/morney/services/ExportService.scala`, `src/main/scala/morney/services/BackupService.scala`
  - Export to JSON/CSV (accounts, entries, postings, imports)
  - Backup: copy SQLite file or export bundle; restore into new DB
  - _Requirements: 9_
  - _Prompt: Role: Scala backend developer | Task: Implement export and backup/restore for safe data portability | Restrictions: Must preserve IDs and relationships; avoid partial exports | Success: Export is complete; restore recreates identical ledger state_

- [x] 11. CLI UI for core flows
  - Files: `src/main/scala/morney/ui/CliApp.scala`
  - Commands:
    - book: create/list/select
    - accounts: list/add/edit/disable
    - entry: create (raw), list, show
    - quick: expense/income/transfer/card-payment (with split)
    - reports: balances, pnl, ledger
    - import: csv, pending, confirm, ignore
    - export/backup/restore
  - _Requirements: 1, 2, 3, 4, 6, 7, 8, 9_
  - _Prompt: Role: Scala CLI developer | Task: Build a usable ZIO-based CLI (e.g., zio-cli) that exposes the primary bookkeeping workflows end-to-end | Restrictions: Keep commands consistent; helpful errors; avoid unnecessary UI deps | Success: A user can complete the main flows using only the CLI_

- [x] 12. Automated tests (ZIO Test)
  - Files: `src/test/scala/morney/DomainSpec.scala`, `src/test/scala/morney/JournalSpec.scala`, `src/test/scala/morney/QuickEntrySpec.scala`, `src/test/scala/morney/ReportsSpec.scala`, `src/test/scala/morney/ImportSpec.scala`
  - Focus on invariants and report correctness:
    - balancing validation and split expenses
    - card purchase + payment flows
    - report totals and running balances
    - dedup behavior and confirm/ignore behavior
  - _Requirements: All_
  - _Prompt: Role: QA engineer | Task: Add fast, deterministic unit/integration tests for the ledger domain/services using SQLite temp DBs with ZIO Test | Restrictions: Avoid network and flaky time dependencies | Success: Tests cover critical flows and pass consistently_

- [x] 13. Documentation and examples
  - Files: `README.md`, `docs/examples.md`
  - Provide: sbt setup, data location, example CLI commands, CSV import mapping examples, common troubleshooting
  - _Requirements: 7, 8, 9_
  - _Prompt: Role: Technical writer | Task: Document how to use the CLI for real household bookkeeping workflows with examples | Restrictions: Keep docs concise and accurate; include example CSV schemas | Success: A new user can install, record transactions, view reports, and back up data_
