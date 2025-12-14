package morney.services

import morney.domain.*
import zio.{Chunk, IO}

import java.time.LocalDate

final case class SplitLine(
  accountId: Id,
  amount: Long,
  description: Option[String]
)

final class QuickEntryService(journal: JournalService) {
  def expense(
    bookId: Id,
    date: LocalDate,
    payFromAccountId: Id,
    expenseAccountId: Id,
    amount: Long,
    memo: Option[String]
  ): IO[JournalServiceError, JournalEntry] =
    journal.createEntry(
      bookId = bookId,
      date = date,
      memo = memo,
      postings = Chunk(
        PostingDraft(expenseAccountId, PostingSide.Debit, amount, None),
        PostingDraft(payFromAccountId, PostingSide.Credit, amount, None)
      )
    )

  def splitExpense(
    bookId: Id,
    date: LocalDate,
    payFromAccountId: Id,
    splits: Chunk[SplitLine],
    memo: Option[String]
  ): IO[JournalServiceError, JournalEntry] = {
    val debitLines = splits.map(s => PostingDraft(s.accountId, PostingSide.Debit, s.amount, s.description))
    val total = splits.map(_.amount).sum
    journal.createEntry(
      bookId = bookId,
      date = date,
      memo = memo,
      postings = debitLines :+ PostingDraft(payFromAccountId, PostingSide.Credit, total, None)
    )
  }

  def income(
    bookId: Id,
    date: LocalDate,
    depositToAccountId: Id,
    revenueAccountId: Id,
    amount: Long,
    memo: Option[String]
  ): IO[JournalServiceError, JournalEntry] =
    journal.createEntry(
      bookId = bookId,
      date = date,
      memo = memo,
      postings = Chunk(
        PostingDraft(depositToAccountId, PostingSide.Debit, amount, None),
        PostingDraft(revenueAccountId, PostingSide.Credit, amount, None)
      )
    )

  def transfer(
    bookId: Id,
    date: LocalDate,
    fromAccountId: Id,
    toAccountId: Id,
    amount: Long,
    memo: Option[String]
  ): IO[JournalServiceError, JournalEntry] =
    journal.createEntry(
      bookId = bookId,
      date = date,
      memo = memo,
      postings = Chunk(
        PostingDraft(toAccountId, PostingSide.Debit, amount, None),
        PostingDraft(fromAccountId, PostingSide.Credit, amount, None)
      )
    )

  def cardPayment(
    bookId: Id,
    date: LocalDate,
    bankAccountId: Id,
    cardLiabilityAccountId: Id,
    amount: Long,
    memo: Option[String]
  ): IO[JournalServiceError, JournalEntry] =
    journal.createEntry(
      bookId = bookId,
      date = date,
      memo = memo,
      postings = Chunk(
        PostingDraft(cardLiabilityAccountId, PostingSide.Debit, amount, None),
        PostingDraft(bankAccountId, PostingSide.Credit, amount, None)
      )
    )
}

