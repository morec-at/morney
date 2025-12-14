# Task 11 Implementation Log

## Summary

Expanded the ZIO-based CLI to cover end-to-end household bookkeeping flows: books, accounts, raw entries, quick entries, reports, CSV import confirmation, export, and DB backup/restore.

## Artifacts

### CLI Entry Point
- `src/main/scala/morney/ui/CliApp.scala`
  - Opens SQLite DB and runs migrations for commands requiring storage
  - Commands implemented:
    - `book create --name ...` (creates book + default accounts), `book list`, `book select --book ...`, `book current`
    - `accounts list/add/disable`
    - `entry create/list/show` (raw postings supported via repeated `--debit ...` / `--credit ...`)
    - `quick expense/income/transfer/card-payment/split-expense`
    - `reports balances/pnl/ledger`
    - `import csv/pending/confirm/ignore`
    - `export json/csv`
    - `backup --out ...`, `restore --from ...`
  - Selected book is stored per DB path in `<dbPath>.current-book`.

### Docs
- `README.md` updated with quick-start commands.

## Notes

- CLI parsing is minimal and flag-based (no external CLI framework dependency).
- Errors are converted into readable messages (validation errors show specific causes).

