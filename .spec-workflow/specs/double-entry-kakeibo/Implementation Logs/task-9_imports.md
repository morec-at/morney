# Task 9 Implementation Log

## Summary

Implemented a CSV import pipeline that stores imported rows as pending transactions with dedup support, and a pending→confirmed workflow that links an imported transaction to a created journal entry.

## Artifacts

### CSV Importer
- `src/main/scala/morney/importers/GenericCsvImporter.scala`
  - `CsvMapping` selects columns by header name (date/amount/memo/dedup)
  - Minimal CSV parser supports quoted fields and escaped quotes (`""`)
  - Parses `yyyy-MM-dd` or `yyyy/M/d` dates and integer amounts (supports commas / yen symbols / parentheses for negatives)
  - Produces `ImportedRow` containing parsed fields + raw header/value map

### Repository Support
- `src/main/scala/morney/storage/LedgerRepository.scala`
  - Added methods to insert/list/get/update imported transactions and set status/link
- `src/main/scala/morney/storage/SqliteLedgerRepository.scala`
  - Implements imported transaction CRUD using the `imported_transactions` table
  - Detects duplicates via SQLite UNIQUE constraint and throws `DuplicateImportedTransaction`

### Import Service
- `src/main/scala/morney/services/ImportService.scala`
  - `importCsv(...)` inserts pending imported transactions
    - dedupKey uses CSV id column when present, otherwise a stable SHA-256 hash
    - duplicate policy: `Skip` or `Overwrite` (updates row by dedup key)
  - `listPending(...)` lists pending imported transactions
  - `ignore(id)` marks as ignored
  - `confirmAsEntry(...)` creates a journal entry (via `JournalService`) and links imported transaction as `linked`
  - Raw CSV row map is stored as a JSON string (no external JSON lib)

### Tests
- `src/test/scala/morney/ImportServiceSpec.scala`
  - Imports CSV, verifies skip/overwrite dedup behavior
  - Confirms pending import into linked journal entry
  - Ignores pending import and verifies it disappears from pending list

