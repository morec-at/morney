# Task 7 Implementation Log

## Summary

Implemented quick-entry helpers that map household workflows (expense/income/transfer/card payment) into valid double-entry journal entries, including split expenses.

## Artifacts

### Service
- `src/main/scala/morney/services/QuickEntryService.scala`
  - `expense(...)`: debit expense, credit payment source
  - `splitExpense(...)`: multiple debit lines + single credit line for the sum
  - `income(...)`: debit asset, credit revenue
  - `transfer(...)`: debit destination asset, credit source asset
  - `cardPayment(...)`: debit card liability, credit bank asset
  - All methods delegate to `JournalService` for validation/persistence.

### Tests
- `src/test/scala/morney/QuickEntryServiceSpec.scala`
  - Verifies postings shapes and amounts for expense, split expense, transfer, and card payment.

