package morney

import morney.domain.*
import morney.services.{JournalService, JournalServiceError, PostingDraft}
import morney.storage.{Db, Migrator, SqliteLedgerRepository}
import zio.{Chunk, ZIO}
import zio.test.{ZIOSpecDefault, assertTrue}

import java.nio.file.Files
import java.time.{Instant, LocalDate}

object JournalServiceSpec extends ZIOSpecDefault {
  override def spec =
    suite("journal service")(
      test("rejects unbalanced drafts with actionable errors") {
        ZIO.scoped {
          for {
            tmp <- ZIO.attempt(Files.createTempFile("morney-", ".db"))
            _ <- ZIO.attempt(Files.deleteIfExists(tmp))
            ok <- Db.open(tmp).flatMap { conn =>
              val repo = SqliteLedgerRepository(conn)
              val svc = JournalService(repo)
              for {
                _ <- Migrator.migrate(conn)
                book <- repo.createBook("home")
                cash <- repo.createAccount(book.id, "Cash", AccountType.Asset)
                food <- repo.createAccount(book.id, "Food", AccountType.Expense)
                result <- svc
                  .createEntry(
                    bookId = book.id,
                    date = LocalDate.parse("2025-12-01"),
                    memo = Some("bad"),
                    postings = Chunk(
                      PostingDraft(accountId = food.id, side = PostingSide.Debit, amount = 1000L, description = None),
                      PostingDraft(accountId = cash.id, side = PostingSide.Credit, amount = 900L, description = None)
                    )
                  )
                  .either
              } yield result match {
                case Left(JournalServiceError.Validation(errors)) =>
                  errors.exists {
                    case DomainError.UnbalancedEntry(1000L, 900L) => true
                    case _                                        => false
                  }
                case _ => false
              }
            }
          } yield assertTrue(ok)
        }
      },
      test("creates and updates entries via repository with updatedAt changes") {
        ZIO.scoped {
          val counter = new java.util.concurrent.atomic.AtomicLong(0L)
          def now(): Instant =
            Instant.ofEpochSecond(1_700_000_000L + counter.getAndIncrement())

          for {
            tmp <- ZIO.attempt(Files.createTempFile("morney-", ".db"))
            _ <- ZIO.attempt(Files.deleteIfExists(tmp))
            ok <- Db.open(tmp).flatMap { conn =>
              val repo = SqliteLedgerRepository(conn, now = () => now())
              val svc = JournalService(repo)
              for {
                _ <- Migrator.migrate(conn)
                book <- repo.createBook("home")
                cash <- repo.createAccount(book.id, "Cash", AccountType.Asset)
                food <- repo.createAccount(book.id, "Food", AccountType.Expense)
                entry <- svc.createEntry(
                  bookId = book.id,
                  date = LocalDate.parse("2025-12-01"),
                  memo = Some("grocery"),
                  postings = Chunk(
                    PostingDraft(accountId = food.id, side = PostingSide.Debit, amount = 1200L, description = None),
                    PostingDraft(accountId = cash.id, side = PostingSide.Credit, amount = 1200L, description = None)
                  )
                )
                updated <- svc.updateEntry(
                  entryId = entry.id,
                  bookId = book.id,
                  date = LocalDate.parse("2025-12-02"),
                  memo = Some("grocery+"),
                  postings = Chunk(
                    PostingDraft(accountId = food.id, side = PostingSide.Debit, amount = 1500L, description = Some("split")),
                    PostingDraft(accountId = cash.id, side = PostingSide.Credit, amount = 1500L, description = None)
                  )
                )
              } yield updated.updatedAt.isAfter(entry.updatedAt) &&
                updated.memo.contains("grocery+") &&
                updated.date.toString == "2025-12-02" &&
                updated.postings.map(_.amount).sum == 3000L &&
                updated.postings.size == 2
            }
          } yield assertTrue(ok)
        }
      }
    )
}
