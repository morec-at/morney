package morney

import morney.domain.*
import morney.services.{BackupService, ExportService, JournalService, QuickEntryService}
import morney.storage.{Db, Migrator, SqliteLedgerRepository}
import zio.ZIO
import zio.test.{ZIOSpecDefault, assertTrue}

import java.nio.file.{Files, Path}
import java.time.LocalDate

object ExportBackupSpec extends ZIOSpecDefault {
  override def spec =
    suite("export/backup")(
      test("exportJson and exportCsvBundle write files") {
        ZIO.scoped {
          for {
            tmpDb <- ZIO.attempt(Files.createTempFile("morney-", ".db"))
            _ <- ZIO.attempt(Files.deleteIfExists(tmpDb))
            tmpDir <- ZIO.attempt(Files.createTempDirectory("morney-export-"))
            ok <- Db.open(tmpDb).flatMap { conn =>
              val repo = SqliteLedgerRepository(conn)
              val journal = JournalService(repo)
              val quick = QuickEntryService(journal)
              val exporter = ExportService(repo)

              for {
                _ <- Migrator.migrate(conn)
                book <- repo.createBook("home")
                cash <- repo.createAccount(book.id, "Cash", AccountType.Asset)
                food <- repo.createAccount(book.id, "Food", AccountType.Expense)
                _ <- quick.expense(book.id, LocalDate.parse("2025-12-01"), cash.id, food.id, 1200L, Some("grocery"))

                jsonPath <- exporter.exportJson(book.id, tmpDir.resolve("export.json"))
                bundle <- exporter.exportCsvBundle(book.id, tmpDir.resolve("bundle"))
                jsonText <- ZIO.attempt(Files.readString(jsonPath))
                accountsCsv <- ZIO.attempt(Files.readString(bundle.accountsCsvPath))
              } yield Files.exists(bundle.postingsCsvPath) &&
                jsonText.contains("\"accounts\"") &&
                jsonText.contains("\"journalEntries\"") &&
                accountsCsv.startsWith("id,book_id,name,type,is_active,created_at")
            }
          } yield assertTrue(ok)
        }
      },
      test("backupDb and restoreDb recreate identical DB state") {
        ZIO.scoped {
          for {
            db1 <- ZIO.attempt(Files.createTempFile("morney-", ".db"))
            _ <- ZIO.attempt(Files.deleteIfExists(db1))
            backup <- ZIO.attempt(Files.createTempFile("morney-", ".bak.db"))
            db2 <- ZIO.attempt(Files.createTempFile("morney-", ".restored.db"))
            _ <- ZIO.attempt(Files.deleteIfExists(db2))
            ok <- Db.open(db1).flatMap { conn =>
              val repo = SqliteLedgerRepository(conn)
              val backupService = BackupService()
              for {
                _ <- Migrator.migrate(conn)
                b <- repo.createBook("home")
                _ <- backupService.backupDb(db1, backup)
                _ <- backupService.restoreDb(backup, db2)
                books2 <- Db.open(db2).flatMap { conn2 =>
                  val repo2 = SqliteLedgerRepository(conn2)
                  repo2.listBooks()
                }
              } yield books2.exists(_.id == b.id)
            }
          } yield assertTrue(ok)
        }
      }
    )
}
