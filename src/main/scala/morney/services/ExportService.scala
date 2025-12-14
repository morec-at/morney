package morney.services

import morney.domain.*
import morney.storage.LedgerRepository
import morney.util.{Csv, Json}
import zio.{Chunk, Task, ZIO}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.time.Instant

final case class ExportPaths(
  jsonPath: Path,
  accountsCsvPath: Path,
  journalEntriesCsvPath: Path,
  postingsCsvPath: Path,
  importedTransactionsCsvPath: Path
)

final class ExportService(repo: LedgerRepository) {
  def exportJson(bookId: Id, outputPath: Path): Task[Path] =
    for {
      accounts <- repo.listAccounts(bookId, includeInactive = true)
      entries <- repo.listJournalEntries(bookId, morney.storage.DateRange.unbounded)
      imports <- repo.listImportedTransactions(bookId, source = None, status = None)
      json = buildExportJson(bookId, accounts, entries, imports)
      _ <- ZIO.attemptBlocking {
        val parent = outputPath.getParent
        if (parent != null) Files.createDirectories(parent)
        Files.writeString(outputPath, json + "\n", StandardCharsets.UTF_8)
      }
    } yield outputPath

  def exportCsvBundle(bookId: Id, outputDir: Path): Task[ExportPaths] =
    for {
      accounts <- repo.listAccounts(bookId, includeInactive = true)
      entries <- repo.listJournalEntries(bookId, morney.storage.DateRange.unbounded)
      imports <- repo.listImportedTransactions(bookId, source = None, status = None)
      paths <- ZIO.attemptBlocking {
        Files.createDirectories(outputDir)
        val accountsCsv = outputDir.resolve("accounts.csv")
        val entriesCsv = outputDir.resolve("journal_entries.csv")
        val postingsCsv = outputDir.resolve("postings.csv")
        val importsCsv = outputDir.resolve("imported_transactions.csv")
        val jsonPath = outputDir.resolve("export.json")

        writeAccountsCsv(accountsCsv, accounts)
        writeEntriesCsv(entriesCsv, entries)
        writePostingsCsv(postingsCsv, entries.flatMap(_.postings))
        writeImportsCsv(importsCsv, imports)
        Files.writeString(jsonPath, buildExportJson(bookId, accounts, entries, imports) + "\n", StandardCharsets.UTF_8)

        ExportPaths(
          jsonPath = jsonPath,
          accountsCsvPath = accountsCsv,
          journalEntriesCsvPath = entriesCsv,
          postingsCsvPath = postingsCsv,
          importedTransactionsCsvPath = importsCsv
        )
      }
    } yield paths

  private def buildExportJson(
    bookId: Id,
    accounts: List[Account],
    entries: List[JournalEntry],
    imports: List[ImportedTransaction]
  ): String = {
    val accountsJson = accounts.map { a =>
      Json.obj(
        List(
          "id" -> Json.str(a.id),
          "bookId" -> Json.str(a.bookId),
          "name" -> Json.str(a.name),
          "type" -> Json.str(encodeAccountType(a.`type`)),
          "isActive" -> Json.bool(a.isActive),
          "createdAt" -> Json.instant(a.createdAt)
        )
      )
    }

    val entriesJson = entries.map { e =>
      val postingsJson = e.postings.map { p =>
        Json.obj(
          List(
            "id" -> Json.str(p.id),
            "entryId" -> Json.str(p.entryId),
            "accountId" -> Json.str(p.accountId),
            "side" -> Json.str(encodePostingSide(p.side)),
            "amount" -> Json.num(p.amount),
            "description" -> (p.description.map(Json.str).getOrElse("null"))
          )
        )
      }
      Json.obj(
        List(
          "id" -> Json.str(e.id),
          "bookId" -> Json.str(e.bookId),
          "date" -> Json.str(e.date.toString),
          "memo" -> (e.memo.map(Json.str).getOrElse("null")),
          "createdAt" -> Json.instant(e.createdAt),
          "updatedAt" -> Json.instant(e.updatedAt),
          "postings" -> Json.arr(postingsJson)
        )
      )
    }

    val importsJson = imports.map { t =>
      Json.obj(
        List(
          "id" -> Json.str(t.id),
          "bookId" -> Json.str(t.bookId),
          "source" -> Json.str(t.source),
          "date" -> Json.str(t.date.toString),
          "amount" -> Json.num(t.amount),
          "payeeOrMemo" -> (t.payeeOrMemo.map(Json.str).getOrElse("null")),
          "raw" -> Json.str(t.raw),
          "dedupKey" -> Json.str(t.dedupKey),
          "status" -> Json.str(encodeImportStatus(t.status)),
          "linkedEntryId" -> (t.linkedEntryId.map(Json.str).getOrElse("null"))
        )
      )
    }

    Json.obj(
      List(
        "formatVersion" -> Json.num(1),
        "exportedAt" -> Json.instant(Instant.now()),
        "bookId" -> Json.str(bookId),
        "accounts" -> Json.arr(accountsJson),
        "journalEntries" -> Json.arr(entriesJson),
        "importedTransactions" -> Json.arr(importsJson)
      )
    )
  }

  private def writeAccountsCsv(path: Path, accounts: List[Account]): Unit = {
    val header = Csv.header(List("id", "book_id", "name", "type", "is_active", "created_at"))
    val body = accounts.map { a =>
      Csv.row(
        List(
          a.id,
          a.bookId,
          a.name,
          encodeAccountType(a.`type`),
          if (a.isActive) "1" else "0",
          a.createdAt.toString
        )
      )
    }.mkString
    Files.writeString(path, header + body, StandardCharsets.UTF_8)
  }

  private def writeEntriesCsv(path: Path, entries: List[JournalEntry]): Unit = {
    val header = Csv.header(List("id", "book_id", "entry_date", "memo", "created_at", "updated_at"))
    val body = entries.map { e =>
      Csv.row(
        List(
          e.id,
          e.bookId,
          e.date.toString,
          e.memo.getOrElse(""),
          e.createdAt.toString,
          e.updatedAt.toString
        )
      )
    }.mkString
    Files.writeString(path, header + body, StandardCharsets.UTF_8)
  }

  private def writePostingsCsv(path: Path, postings: List[Posting]): Unit = {
    val header = Csv.header(List("id", "entry_id", "account_id", "side", "amount", "description"))
    val body = postings.map { p =>
      Csv.row(
        List(
          p.id,
          p.entryId,
          p.accountId,
          encodePostingSide(p.side),
          p.amount.toString,
          p.description.getOrElse("")
        )
      )
    }.mkString
    Files.writeString(path, header + body, StandardCharsets.UTF_8)
  }

  private def writeImportsCsv(path: Path, imports: List[ImportedTransaction]): Unit = {
    val header =
      Csv.header(List("id", "book_id", "source", "txn_date", "amount", "payee_or_memo", "raw_json", "dedup_key", "status", "linked_entry_id"))
    val body = imports.map { t =>
      Csv.row(
        List(
          t.id,
          t.bookId,
          t.source,
          t.date.toString,
          t.amount.toString,
          t.payeeOrMemo.getOrElse(""),
          t.raw,
          t.dedupKey,
          encodeImportStatus(t.status),
          t.linkedEntryId.getOrElse("")
        )
      )
    }.mkString
    Files.writeString(path, header + body, StandardCharsets.UTF_8)
  }

  private def encodeAccountType(t: AccountType): String =
    t match {
      case AccountType.Asset     => "asset"
      case AccountType.Liability => "liability"
      case AccountType.Equity    => "equity"
      case AccountType.Revenue   => "revenue"
      case AccountType.Expense   => "expense"
    }

  private def encodePostingSide(s: PostingSide): String =
    s match {
      case PostingSide.Debit  => "debit"
      case PostingSide.Credit => "credit"
    }

  private def encodeImportStatus(s: ImportStatus): String =
    s match {
      case ImportStatus.Pending => "pending"
      case ImportStatus.Linked  => "linked"
      case ImportStatus.Ignored => "ignored"
    }
}
