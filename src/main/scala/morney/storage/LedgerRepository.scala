package morney.storage

import morney.domain.*
import zio.Chunk
import zio.Task

import java.time.LocalDate

final case class DateRange(from: Option[LocalDate], to: Option[LocalDate])

object DateRange {
  val unbounded: DateRange = DateRange(None, None)
}

trait LedgerRepository {
  def createBook(name: String): Task[Book]
  def listBooks(): Task[List[Book]]

  def createAccount(bookId: Id, name: String, accountType: AccountType): Task[Account]
  def setAccountActive(accountId: Id, isActive: Boolean): Task[Unit]
  def listAccounts(bookId: Id, includeInactive: Boolean): Task[List[Account]]

  def createJournalEntry(
    bookId: Id,
    date: LocalDate,
    memo: Option[String],
    postings: Chunk[Posting]
  ): Task[JournalEntry]

  def updateJournalEntry(
    entryId: Id,
    date: LocalDate,
    memo: Option[String],
    postings: Chunk[Posting]
  ): Task[JournalEntry]

  def getJournalEntry(entryId: Id): Task[Option[JournalEntry]]
  def listJournalEntries(bookId: Id, range: DateRange): Task[List[JournalEntry]]

  def insertImportedTransaction(txn: ImportedTransaction): Task[Unit]
  def updateImportedTransactionByDedupKey(
    bookId: Id,
    source: String,
    dedupKey: String,
    date: LocalDate,
    amount: Long,
    payeeOrMemo: Option[String],
    raw: String
  ): Task[Unit]
  def getImportedTransaction(id: Id): Task[Option[ImportedTransaction]]
  def listImportedTransactions(bookId: Id, source: Option[String], status: Option[ImportStatus]): Task[List[ImportedTransaction]]
  def setImportedTransactionStatus(id: Id, status: ImportStatus, linkedEntryId: Option[Id]): Task[Unit]
}
