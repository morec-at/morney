package morney.services

import morney.domain.*
import morney.storage.LedgerRepository
import zio.{Chunk, IO, ZIO}

import java.time.{Instant, LocalDate}

final case class PostingDraft(
  accountId: Id,
  side: PostingSide,
  amount: Long,
  description: Option[String]
)

sealed trait JournalServiceError extends Product with Serializable

object JournalServiceError {
  final case class Validation(errors: Chunk[DomainError]) extends JournalServiceError
  final case class Storage(cause: Throwable) extends JournalServiceError
}

final class JournalService(repo: LedgerRepository) {
  def createEntry(
    bookId: Id,
    date: LocalDate,
    memo: Option[String],
    postings: Chunk[PostingDraft]
  ): IO[JournalServiceError, JournalEntry] =
    for {
      validated <- ZIO.fromEither(validateDraft(bookId, date, memo, postings)).mapError(JournalServiceError.Validation(_))
      persisted <- repo.createJournalEntry(
        bookId = validated.bookId,
        date = validated.date,
        memo = validated.memo,
        postings = validated.postings
      ).mapError(JournalServiceError.Storage(_))
    } yield persisted

  def updateEntry(
    entryId: Id,
    bookId: Id,
    date: LocalDate,
    memo: Option[String],
    postings: Chunk[PostingDraft]
  ): IO[JournalServiceError, JournalEntry] =
    for {
      validated <- ZIO.fromEither(validateDraft(bookId, date, memo, postings)).mapError(JournalServiceError.Validation(_))
      persisted <- repo.updateJournalEntry(
        entryId = entryId,
        date = validated.date,
        memo = validated.memo,
        postings = validated.postings
      ).mapError(JournalServiceError.Storage(_))
    } yield persisted

  private def validateDraft(
    bookId: Id,
    date: LocalDate,
    memo: Option[String],
    postings: Chunk[PostingDraft]
  ): Either[Chunk[DomainError], JournalEntry] = {
    val entryId = "draft"
    val ts = Instant.EPOCH
    val postingModels = postings.zipWithIndex.map { case (p, idx) =>
      Posting(
        id = s"draft-$idx",
        entryId = entryId,
        accountId = p.accountId,
        side = p.side,
        amount = p.amount,
        description = p.description
      )
    }
    val entry = JournalEntry(
      id = entryId,
      bookId = bookId,
      date = date,
      memo = memo,
      postings = postingModels,
      createdAt = ts,
      updatedAt = ts
    )
    Validation.validateJournalEntry(entry)
  }
}
