# Task 2 Implementation Log

## Summary

Added core domain models for books/accounts/journal entries/postings/imported transactions and reusable validation for double-entry invariants.

## Artifacts

### Domain Models
- `src/main/scala/morney/domain/Models.scala`
  - Types: `Book`, `Account`, `JournalEntry`, `Posting`, `ImportedTransaction`
  - Enums: `AccountType`, `PostingSide`, `ImportStatus`
  - `JournalEntry.postings` uses `zio.Chunk`

### Validation
- `src/main/scala/morney/domain/Validation.scala`
  - Validates:
    - required fields (e.g., `bookId`, `accountId`)
    - posting amount > 0
    - entry must have postings
    - debitTotal == creditTotal
  - Errors represented as `DomainError` ADT and returned as `Either[Chunk[DomainError], A]`

