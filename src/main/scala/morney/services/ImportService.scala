package morney.services

import morney.domain.*
import morney.importers.{CsvMapping, GenericCsvImporter}
import morney.storage.{DuplicateImportedTransaction, LedgerRepository}
import zio.{Chunk, IO, ZIO}

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID

enum DuplicatePolicy {
  case Skip, Overwrite
}

final case class ImportSummary(
  attempted: Int,
  inserted: Int,
  duplicatesSkipped: Int,
  duplicatesOverwritten: Int
)

sealed trait ImportServiceError extends Product with Serializable

object ImportServiceError {
  final case class Parse(message: String) extends ImportServiceError
  final case class NotFound(importedId: Id) extends ImportServiceError
  final case class InvalidState(importedId: Id, status: ImportStatus) extends ImportServiceError
  final case class Storage(cause: Throwable) extends ImportServiceError
  final case class Journal(cause: JournalServiceError) extends ImportServiceError
}

final class ImportService(
  repo: LedgerRepository,
  journal: JournalService
) {
  def importCsv(
    bookId: Id,
    source: String,
    csv: String,
    mapping: CsvMapping,
    onDuplicate: DuplicatePolicy = DuplicatePolicy.Skip
  ): IO[ImportServiceError, ImportSummary] =
    for {
      rows <- ZIO.fromEither(GenericCsvImporter.parse(csv, mapping)).mapError(ImportServiceError.Parse(_))
      summary <- ZIO.foldLeft(rows)(ImportSummary(rows.size, 0, 0, 0)) { (acc, row) =>
        val dedupKey = row.dedupKeyCandidate.getOrElse(stableHash(source, row.date, row.amount, row.memo))
        val txn = ImportedTransaction(
          id = UUID.randomUUID().toString,
          bookId = bookId,
          source = source,
          date = row.date,
          amount = row.amount,
          payeeOrMemo = row.memo,
          raw = toJsonString(row.raw),
          dedupKey = dedupKey,
          status = ImportStatus.Pending,
          linkedEntryId = None
        )
        repo
          .insertImportedTransaction(txn)
          .as(acc.copy(inserted = acc.inserted + 1))
          .catchSome { case _: DuplicateImportedTransaction =>
            onDuplicate match {
              case DuplicatePolicy.Skip =>
                ZIO.succeed(acc.copy(duplicatesSkipped = acc.duplicatesSkipped + 1))
              case DuplicatePolicy.Overwrite =>
                repo
                  .updateImportedTransactionByDedupKey(
                    bookId = bookId,
                    source = source,
                    dedupKey = dedupKey,
                    date = row.date,
                    amount = row.amount,
                    payeeOrMemo = row.memo,
                    raw = toJsonString(row.raw)
                  )
                  .as(acc.copy(duplicatesOverwritten = acc.duplicatesOverwritten + 1))
            }
          }
          .mapError(ImportServiceError.Storage(_))
      }
    } yield summary

  def listPending(bookId: Id, source: Option[String] = None): IO[ImportServiceError.Storage, List[ImportedTransaction]] =
    repo
      .listImportedTransactions(bookId, source = source, status = Some(ImportStatus.Pending))
      .mapError(ImportServiceError.Storage(_))

  def ignore(importedId: Id): IO[ImportServiceError.Storage, Unit] =
    repo.setImportedTransactionStatus(importedId, ImportStatus.Ignored, linkedEntryId = None).mapError(ImportServiceError.Storage(_))

  def confirmAsEntry(
    importedId: Id,
    bookId: Id,
    memoOverride: Option[String],
    postings: Chunk[PostingDraft]
  ): IO[ImportServiceError, JournalEntry] =
    for {
      imported <- repo.getImportedTransaction(importedId).mapError(ImportServiceError.Storage(_))
      txn <- ZIO.fromOption(imported).orElseFail(ImportServiceError.NotFound(importedId))
      _ <- ZIO.fail(ImportServiceError.NotFound(importedId)).unless(txn.bookId == bookId)
      _ <- ZIO
        .fail(ImportServiceError.InvalidState(importedId, txn.status))
        .unless(txn.status == ImportStatus.Pending)
      entry <- journal
        .createEntry(
          bookId = bookId,
          date = txn.date,
          memo = memoOverride.orElse(txn.payeeOrMemo),
          postings = postings
        )
        .mapError(ImportServiceError.Journal(_))
      _ <- repo
        .setImportedTransactionStatus(importedId, ImportStatus.Linked, linkedEntryId = Some(entry.id))
        .mapError(ImportServiceError.Storage(_))
    } yield entry

  private def stableHash(source: String, date: LocalDate, amount: Long, memo: Option[String]): String = {
    val input = s"$source|$date|$amount|${memo.getOrElse("")}"
    val md = MessageDigest.getInstance("SHA-256")
    val bytes = md.digest(input.getBytes(StandardCharsets.UTF_8))
    bytes.map("%02x".format(_)).mkString
  }

  private def toJsonString(map: Map[String, String]): String = {
    val entries = map.toList.sortBy(_._1).map { case (k, v) =>
      s""""${escapeJson(k)}":"${escapeJson(v)}""""
    }
    entries.mkString("{", ",", "}")
  }

  private def escapeJson(s: String): String =
    s.flatMap {
      case '"'  => "\\\""
      case '\\' => "\\\\"
      case '\b' => "\\b"
      case '\f' => "\\f"
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case c if c.isControl => "\\u%04x".format(c.toInt)
      case c => c.toString
    }
}
