# Examples

This document shows example CLI commands for common household bookkeeping workflows.

## Setup

```sh
sbt "run -- doctor"
sbt "run -- book create --name home"
sbt "run -- book list"
sbt "run -- book select --book <bookId>"
```

List accounts and copy IDs:

```sh
sbt "run -- accounts list"
```

## Quick entries

### Expense

```sh
sbt "run -- quick expense --date 2025-12-01 --pay-from <cashId> --expense <foodId> --amount 1200 --memo grocery"
```

### Split expense

```sh
sbt "run -- quick split-expense --date 2025-12-05 --pay-from <bankId> --memo split \
  --split <foodId> 2000 grocery --split <utilitiesId> 3000 electric"
```

### Income

```sh
sbt "run -- quick income --date 2025-12-01 --deposit-to <bankId> --revenue <salaryId> --amount 100000 --memo salary"
```

### Transfer

```sh
sbt "run -- quick transfer --date 2025-12-03 --from <bankId> --to <cashId> --amount 20000 --memo withdraw"
```

### Card payment (repayment)

```sh
sbt "run -- quick card-payment --date 2025-12-31 --bank <bankId> --card <cardLiabilityId> --amount 15000 --memo payment"
```

## Raw journal entry

```sh
sbt "run -- entry create --date 2025-12-10 --memo manual \
  --debit <expenseId> 500 --credit <cashId> 500"
```

## Reports

```sh
sbt "run -- reports balances --asof 2025-12-31"
sbt "run -- reports pnl --from 2025-12-01 --to 2025-12-31"
sbt "run -- reports ledger --account <cashId> --from 2025-12-01 --to 2025-12-31"
```

## CSV import

### Import CSV as pending

CSV example:

```csv
date,amount,memo,id
2025-12-01,1200,grocery,tx-1
```

Import:

```sh
sbt "run -- import csv --source bank-a --file ./transactions.csv --date-col date --amount-col amount --memo-col memo --dedup-col id --duplicate overwrite"
```

List pending:

```sh
sbt "run -- import pending --source bank-a"
```

### Confirm pending into a journal entry

```sh
sbt "run -- import confirm --id <importedId> --debit <foodId> 1200 --credit <cashId> 1200"
```

### Ignore pending

```sh
sbt "run -- import ignore --id <importedId>"
```

## Export

```sh
sbt "run -- export json --out ./export.json"
sbt "run -- export csv --dir ./export-bundle"
```

## Backup / restore

```sh
sbt "run -- backup --out ./morney.db.bak"
sbt "run -- restore --from ./morney.db.bak"
```
