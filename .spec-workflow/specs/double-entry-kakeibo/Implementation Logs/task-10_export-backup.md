# Task 10 Implementation Log

## Summary

Implemented JSON/CSV export for a book and DB-file backup/restore utilities for SQLite persistence.

## Artifacts

### Export
- `src/main/scala/morney/services/ExportService.scala`
  - `exportJson(bookId, outputPath)` writes a single JSON export containing:
    - accounts
    - journal entries (including postings)
    - imported transactions
  - `exportCsvBundle(bookId, outputDir)` writes:
    - `accounts.csv`
    - `journal_entries.csv`
    - `postings.csv`
    - `imported_transactions.csv`
    - plus `export.json` for convenience
  - Uses `LedgerRepository` read APIs to preserve IDs and relationships.

### Backup/Restore
- `src/main/scala/morney/services/BackupService.scala`
  - `backupDb(dbPath, backupPath)` copies the SQLite DB file
  - `restoreDb(backupPath, dbPath)` restores (copies) into a new/existing DB path

### Utilities
- `src/main/scala/morney/util/Json.scala`: minimal JSON builder/escaper
- `src/main/scala/morney/util/Csv.scala`: minimal CSV writer with quoting

### Tests
- `src/test/scala/morney/ExportBackupSpec.scala`
  - Verifies JSON/CSV files are written and have expected headers/keys
  - Verifies backup+restore reproduces DB state (same book id exists after restore)

