# Task 6 Implementation Log

## Summary

Implemented `JournalService` for creating/updating double-entry journal entries with strict validation, and added repository support for updating entries and replacing postings atomically.

## Artifacts

### Service Layer
- `src/main/scala/morney/services/JournalService.scala`
  - `PostingDraft` input model for UI/service callers
  - `JournalServiceError` ADT:
    - `Validation(errors: Chunk[DomainError])`
    - `Storage(cause: Throwable)`
  - `createEntry(...)` validates postings via domain `Validation` (borrowed draft model) then persists via repository
  - `updateEntry(...)` validates then persists via repository update

### Repository Updates
- `src/main/scala/morney/storage/LedgerRepository.scala`
  - Added `updateJournalEntry(entryId, date, memo, postings)`
- `src/main/scala/morney/storage/SqliteLedgerRepository.scala`
  - Updates `journal_entries` (date/memo/updated_at)
  - Replaces postings (delete + insert) within a single transaction
  - Returns the fully reloaded entry (including postings)

### Tests
- `src/test/scala/morney/JournalServiceSpec.scala`
  - Validates unbalanced draft returns `JournalServiceError.Validation` with `DomainError.UnbalancedEntry`
  - Creates then updates an entry and asserts `updatedAt` increases and postings are replaced

