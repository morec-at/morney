package morney.domain

import zio.Chunk

import java.time.{Instant, LocalDate}

type Id = String

enum AccountType {
  case Asset, Liability, Equity, Revenue, Expense
}

enum PostingSide {
  case Debit, Credit
}

enum ImportStatus {
  case Pending, Linked, Ignored
}

final case class Book(
  id: Id,
  name: String,
  createdAt: Instant
)

final case class Account(
  id: Id,
  bookId: Id,
  name: String,
  `type`: AccountType,
  isActive: Boolean,
  createdAt: Instant
)

final case class Posting(
  id: Id,
  entryId: Id,
  accountId: Id,
  side: PostingSide,
  amount: Long,
  description: Option[String]
)

final case class JournalEntry(
  id: Id,
  bookId: Id,
  date: LocalDate,
  memo: Option[String],
  postings: Chunk[Posting],
  createdAt: Instant,
  updatedAt: Instant
)

final case class ImportedTransaction(
  id: Id,
  bookId: Id,
  source: String,
  date: LocalDate,
  amount: Long,
  payeeOrMemo: Option[String],
  raw: String,
  dedupKey: String,
  status: ImportStatus,
  linkedEntryId: Option[Id]
)
