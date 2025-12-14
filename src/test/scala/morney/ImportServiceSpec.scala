package morney

import morney.domain.*
import morney.importers.CsvMapping
import morney.services.{DuplicatePolicy, ImportService, JournalService, PostingDraft}
import morney.storage.{Db, Migrator, SqliteLedgerRepository}
import zio.Chunk
import zio.test.{ZIOSpecDefault, assertTrue}
import zio.ZIO

import java.nio.file.Files
import java.time.LocalDate

object ImportServiceSpec extends ZIOSpecDefault {
  override def spec =
    suite("import service")(
      test("imports CSV into pending and dedups by key (skip/overwrite)") {
        ZIO.scoped {
          for {
            tmp <- ZIO.attempt(Files.createTempFile("morney-", ".db"))
            _ <- ZIO.attempt(Files.deleteIfExists(tmp))
            ok <- Db.open(tmp).flatMap { conn =>
              val repo = SqliteLedgerRepository(conn)
              val journal = JournalService(repo)
              val svc = ImportService(repo, journal)
              val mapping = CsvMapping(dateColumn = "date", amountColumn = "amount", memoColumn = Some("memo"), dedupColumn = Some("id"))

              val csv1 =
                """date,amount,memo,id
                  |2025-12-01,1200,grocery,tx-1
                  |""".stripMargin

              val csvOverwrite =
                """date,amount,memo,id
                  |2025-12-01,1300,grocery-updated,tx-1
                  |""".stripMargin

              for {
                _ <- Migrator.migrate(conn)
                book <- repo.createBook("home")
                first <- svc.importCsv(book.id, "bank-a", csv1, mapping, onDuplicate = DuplicatePolicy.Skip)
                secondSkip <- svc.importCsv(book.id, "bank-a", csv1, mapping, onDuplicate = DuplicatePolicy.Skip)
                secondOverwrite <- svc.importCsv(book.id, "bank-a", csvOverwrite, mapping, onDuplicate = DuplicatePolicy.Overwrite)
                pending <- svc.listPending(book.id, source = Some("bank-a"))
              } yield {
                first.inserted == 1 &&
                secondSkip.duplicatesSkipped == 1 &&
                secondOverwrite.duplicatesOverwritten == 1 &&
                pending.size == 1 &&
                pending.head.amount == 1300L &&
                pending.head.payeeOrMemo.contains("grocery-updated")
              }
            }
          } yield assertTrue(ok)
        }
      },
      test("can confirm pending import into linked journal entry") {
        ZIO.scoped {
          for {
            tmp <- ZIO.attempt(Files.createTempFile("morney-", ".db"))
            _ <- ZIO.attempt(Files.deleteIfExists(tmp))
            ok <- Db.open(tmp).flatMap { conn =>
              val repo = SqliteLedgerRepository(conn)
              val journal = JournalService(repo)
              val svc = ImportService(repo, journal)
              val mapping = CsvMapping(dateColumn = "date", amountColumn = "amount", memoColumn = Some("memo"), dedupColumn = Some("id"))

              val csv =
                """date,amount,memo,id
                  |2025-12-10,500,lunch,tx-2
                  |""".stripMargin

              for {
                _ <- Migrator.migrate(conn)
                book <- repo.createBook("home")
                cash <- repo.createAccount(book.id, "Cash", AccountType.Asset)
                food <- repo.createAccount(book.id, "Food", AccountType.Expense)
                _ <- svc.importCsv(book.id, "bank-a", csv, mapping)
                pending <- svc.listPending(book.id, source = Some("bank-a"))
                importedId = pending.head.id
                entry <- svc.confirmAsEntry(
                  importedId = importedId,
                  bookId = book.id,
                  memoOverride = None,
                  postings = Chunk(
                    PostingDraft(food.id, PostingSide.Debit, 500L, None),
                    PostingDraft(cash.id, PostingSide.Credit, 500L, None)
                  )
                )
                updated <- repo.getImportedTransaction(importedId)
                fetchedEntry <- repo.getJournalEntry(entry.id)
              } yield updated.exists(t => t.status == ImportStatus.Linked && t.linkedEntryId.contains(entry.id)) &&
                fetchedEntry.exists(_.date == LocalDate.parse("2025-12-10"))
            }
          } yield assertTrue(ok)
        }
      },
      test("ignore removes from pending list") {
        ZIO.scoped {
          for {
            tmp <- ZIO.attempt(Files.createTempFile("morney-", ".db"))
            _ <- ZIO.attempt(Files.deleteIfExists(tmp))
            ok <- Db.open(tmp).flatMap { conn =>
              val repo = SqliteLedgerRepository(conn)
              val journal = JournalService(repo)
              val svc = ImportService(repo, journal)
              val mapping = CsvMapping(dateColumn = "date", amountColumn = "amount", memoColumn = Some("memo"), dedupColumn = Some("id"))

              val csv =
                """date,amount,memo,id
                  |2025-12-11,100,coffee,tx-3
                  |""".stripMargin

              for {
                _ <- Migrator.migrate(conn)
                book <- repo.createBook("home")
                _ <- svc.importCsv(book.id, "bank-a", csv, mapping)
                pending <- svc.listPending(book.id, source = Some("bank-a"))
                _ <- svc.ignore(pending.head.id)
                pendingAfter <- svc.listPending(book.id, source = Some("bank-a"))
              } yield pending.nonEmpty && pendingAfter.isEmpty
            }
          } yield assertTrue(ok)
        }
      }
    )
}

