# Task 12 Implementation Log

## Summary

Added domain-focused unit tests and verified the full test suite remains fast and deterministic.

## Artifacts

### Tests
- `src/test/scala/morney/DomainSpec.scala`
  - Validates core invariants:
    - entries must have postings
    - debit/credit totals must match
    - posting amounts must be > 0

