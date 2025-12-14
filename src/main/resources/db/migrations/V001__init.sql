-- morney initial schema (double-entry household ledger)

CREATE TABLE IF NOT EXISTS schema_migrations (
  version    TEXT PRIMARY KEY,
  applied_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS books (
  id         TEXT PRIMARY KEY,
  name       TEXT NOT NULL,
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS accounts (
  id          TEXT PRIMARY KEY,
  book_id     TEXT NOT NULL REFERENCES books(id) ON DELETE RESTRICT,
  name        TEXT NOT NULL,
  type        TEXT NOT NULL CHECK (type IN ('asset','liability','equity','revenue','expense')),
  is_active   INTEGER NOT NULL CHECK (is_active IN (0,1)),
  created_at  TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_accounts_book_id ON accounts(book_id);
CREATE INDEX IF NOT EXISTS idx_accounts_book_active ON accounts(book_id, is_active);

CREATE TABLE IF NOT EXISTS journal_entries (
  id          TEXT PRIMARY KEY,
  book_id     TEXT NOT NULL REFERENCES books(id) ON DELETE RESTRICT,
  entry_date  TEXT NOT NULL, -- ISO-8601 date (YYYY-MM-DD)
  memo        TEXT,
  created_at  TEXT NOT NULL,
  updated_at  TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_journal_entries_book_date ON journal_entries(book_id, entry_date);

CREATE TABLE IF NOT EXISTS postings (
  id           TEXT PRIMARY KEY,
  entry_id     TEXT NOT NULL REFERENCES journal_entries(id) ON DELETE RESTRICT,
  account_id   TEXT NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
  side         TEXT NOT NULL CHECK (side IN ('debit','credit')),
  amount       INTEGER NOT NULL CHECK (amount > 0),
  description  TEXT
);

CREATE INDEX IF NOT EXISTS idx_postings_entry_id ON postings(entry_id);
CREATE INDEX IF NOT EXISTS idx_postings_account_id ON postings(account_id);

CREATE TABLE IF NOT EXISTS imported_transactions (
  id              TEXT PRIMARY KEY,
  book_id         TEXT NOT NULL REFERENCES books(id) ON DELETE RESTRICT,
  source          TEXT NOT NULL,
  txn_date        TEXT NOT NULL,
  amount          INTEGER NOT NULL,
  payee_or_memo   TEXT,
  raw_json        TEXT NOT NULL,
  dedup_key       TEXT NOT NULL,
  status          TEXT NOT NULL CHECK (status IN ('pending','linked','ignored')),
  linked_entry_id TEXT REFERENCES journal_entries(id) ON DELETE SET NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_imported_dedup ON imported_transactions(book_id, source, dedup_key);
CREATE INDEX IF NOT EXISTS idx_imported_book_status ON imported_transactions(book_id, status);
CREATE INDEX IF NOT EXISTS idx_imported_book_date ON imported_transactions(book_id, txn_date);
