package morney

import morney.domain.*
import morney.storage.{DateRange, Db, Migrator, SqliteLedgerRepository}
import zio.{Chunk, ZIO}
import zio.test.{ZIOSpecDefault, assertTrue}

import java.nio.file.Files
import java.time.LocalDate

object RepositorySpec extends ZIOSpecDefault {
  override def spec =
    suite("repository")(
      test("can create and list books/accounts") {
        ZIO.scoped {
          for {
            tmp <- ZIO.attempt(Files.createTempFile("morney-", ".db"))
            _ <- ZIO.attempt(Files.deleteIfExists(tmp))
            ok <- Db.open(tmp).flatMap { conn =>
              val repo = SqliteLedgerRepository(conn)
              for {
                _ <- Migrator.migrate(conn)
                b1 <- repo.createBook("home")
                _ <- repo.createAccount(b1.id, "Cash", AccountType.Asset)
                books <- repo.listBooks()
                accounts <- repo.listAccounts(b1.id, includeInactive = true)
              } yield books.exists(_.id == b1.id) && accounts.exists(_.name == "Cash")
            }
          } yield assertTrue(ok)
        }
      },
      test("can create and fetch journal entry with postings") {
        ZIO.scoped {
          for {
            tmp <- ZIO.attempt(Files.createTempFile("morney-", ".db"))
            _ <- ZIO.attempt(Files.deleteIfExists(tmp))
            ok <- Db.open(tmp).flatMap { conn =>
              val repo = SqliteLedgerRepository(conn)
              for {
                _ <- Migrator.migrate(conn)
                book <- repo.createBook("home")
                cash <- repo.createAccount(book.id, "Cash", AccountType.Asset)
                food <- repo.createAccount(book.id, "Food", AccountType.Expense)
                entry <- repo.createJournalEntry(
                  bookId = book.id,
                  date = LocalDate.parse("2025-12-01"),
                  memo = Some("grocery"),
                  postings = Chunk(
                    Posting(id = "ignored", entryId = "ignored", accountId = food.id, side = PostingSide.Debit, amount = 1200L, description = None),
                    Posting(id = "ignored", entryId = "ignored", accountId = cash.id, side = PostingSide.Credit, amount = 1200L, description = None)
                  )
                )
                fetched <- repo.getJournalEntry(entry.id)
                listed <- repo.listJournalEntries(book.id, DateRange(from = Some(LocalDate.parse("2025-12-01")), to = Some(LocalDate.parse("2025-12-31"))))
              } yield fetched.exists(_.postings.size == 2) && listed.exists(_.id == entry.id)
            }
          } yield assertTrue(ok)
        }
      }
    )
}

