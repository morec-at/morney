# morney
An accounting book

## Development (Scala + ZIO + SQLite)

### Requirements
- Java (17+ recommended)
- sbt

### Run
- Show help: `sbt "run -- help"`
- Health check / DB connectivity + migrate: `sbt "run -- doctor"`

### Quick start (CLI)
```sh
# create a book + default accounts
sbt "run -- book create --name home"

# list books and select current
sbt "run -- book list"
sbt "run -- book select --book <bookId>"

# list accounts (copy account ids)
sbt "run -- accounts list"

# quick expense
sbt "run -- quick expense --date 2025-12-01 --pay-from <cashId> --expense <foodId> --amount 1200 --memo grocery"

# reports
sbt "run -- reports balances --asof 2025-12-31"
sbt "run -- reports pnl --from 2025-12-01 --to 2025-12-31"
```

More examples: `docs/examples.md`

### Configuration
- DB path (priority: CLI > env > default)
  - CLI: `--db /path/to/morney.db`
  - env: `MORNEY_DB_PATH=/path/to/morney.db`
  - default: `~/.morney/morney.db`
