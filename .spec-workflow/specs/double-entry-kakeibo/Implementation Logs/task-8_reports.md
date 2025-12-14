# Task 8 Implementation Log

## Summary

Implemented deterministic report generation (balance sheet, income statement, and per-account ledger with running balance) derived solely from postings and account types.

## Artifacts

### Service
- `src/main/scala/morney/services/ReportsService.scala`
  - `balanceSheet(bookId, asOf)`:
    - sums postings up to `asOf`
    - groups totals by `AccountType`
  - `incomeStatement(bookId, from, to)`:
    - sums revenue/expense postings in range
    - computes `netIncome = revenueTotal - expenseTotal`
  - `ledger(bookId, accountId, from?, to?)`:
    - computes `openingBalance` as-of `from - 1 day`
    - returns lines with `delta` and running `balance`
  - Sign convention:
    - Asset/Expense: debit increases (+), credit decreases (-)
    - Liability/Equity/Revenue: credit increases (+), debit decreases (-)

### Tests
- `src/test/scala/morney/ReportsServiceSpec.scala`
  - Verifies balance sheet assets total and P&L totals
  - Verifies ledger opening balance and running balance behavior

