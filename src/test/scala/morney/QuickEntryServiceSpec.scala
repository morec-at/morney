package morney

import morney.domain.*
import morney.services.{JournalService, QuickEntryService, SplitLine}
import morney.storage.{Db, Migrator, SqliteLedgerRepository}
import zio.{Chunk, ZIO}
import zio.test.{ZIOSpecDefault, assertTrue}

import java.nio.file.Files
import java.time.LocalDate

object QuickEntryServiceSpec extends ZIOSpecDefault {
  override def spec =
    suite("quick entry service")(
      test("expense creates debit expense and credit payment source") {
        ZIO.scoped {
          for {
            tmp <- ZIO.attempt(Files.createTempFile("morney-", ".db"))
            _ <- ZIO.attempt(Files.deleteIfExists(tmp))
            ok <- Db.open(tmp).flatMap { conn =>
              val repo = SqliteLedgerRepository(conn)
              val journal = JournalService(repo)
              val quick = QuickEntryService(journal)
              for {
                _ <- Migrator.migrate(conn)
                book <- repo.createBook("home")
                cash <- repo.createAccount(book.id, "Cash", AccountType.Asset)
                food <- repo.createAccount(book.id, "Food", AccountType.Expense)
                entry <- quick.expense(
                  bookId = book.id,
                  date = LocalDate.parse("2025-12-01"),
                  payFromAccountId = cash.id,
                  expenseAccountId = food.id,
                  amount = 1200L,
                  memo = Some("grocery")
                )
              } yield entry.postings.size == 2 &&
                entry.postings.exists(p => p.accountId == food.id && p.side == PostingSide.Debit && p.amount == 1200L) &&
                entry.postings.exists(p => p.accountId == cash.id && p.side == PostingSide.Credit && p.amount == 1200L)
            }
          } yield assertTrue(ok)
        }
      },
      test("splitExpense creates multiple debits and one credit with sum") {
        ZIO.scoped {
          for {
            tmp <- ZIO.attempt(Files.createTempFile("morney-", ".db"))
            _ <- ZIO.attempt(Files.deleteIfExists(tmp))
            ok <- Db.open(tmp).flatMap { conn =>
              val repo = SqliteLedgerRepository(conn)
              val journal = JournalService(repo)
              val quick = QuickEntryService(journal)
              for {
                _ <- Migrator.migrate(conn)
                book <- repo.createBook("home")
                bank <- repo.createAccount(book.id, "Bank", AccountType.Asset)
                food <- repo.createAccount(book.id, "Food", AccountType.Expense)
                utilities <- repo.createAccount(book.id, "Utilities", AccountType.Expense)
                entry <- quick.splitExpense(
                  bookId = book.id,
                  date = LocalDate.parse("2025-12-05"),
                  payFromAccountId = bank.id,
                  splits = Chunk(
                    SplitLine(food.id, 2000L, Some("grocery")),
                    SplitLine(utilities.id, 3000L, Some("electric"))
                  ),
                  memo = Some("split purchase")
                )
              } yield entry.postings.size == 3 &&
                entry.postings.count(_.side == PostingSide.Debit) == 2 &&
                entry.postings.count(p => p.side == PostingSide.Credit && p.accountId == bank.id && p.amount == 5000L) == 1
            }
          } yield assertTrue(ok)
        }
      },
      test("transfer debits destination and credits source") {
        ZIO.scoped {
          for {
            tmp <- ZIO.attempt(Files.createTempFile("morney-", ".db"))
            _ <- ZIO.attempt(Files.deleteIfExists(tmp))
            ok <- Db.open(tmp).flatMap { conn =>
              val repo = SqliteLedgerRepository(conn)
              val journal = JournalService(repo)
              val quick = QuickEntryService(journal)
              for {
                _ <- Migrator.migrate(conn)
                book <- repo.createBook("home")
                cash <- repo.createAccount(book.id, "Cash", AccountType.Asset)
                bank <- repo.createAccount(book.id, "Bank", AccountType.Asset)
                entry <- quick.transfer(
                  bookId = book.id,
                  date = LocalDate.parse("2025-12-10"),
                  fromAccountId = cash.id,
                  toAccountId = bank.id,
                  amount = 10000L,
                  memo = Some("deposit")
                )
              } yield entry.postings.size == 2 &&
                entry.postings.exists(p => p.accountId == bank.id && p.side == PostingSide.Debit && p.amount == 10000L) &&
                entry.postings.exists(p => p.accountId == cash.id && p.side == PostingSide.Credit && p.amount == 10000L)
            }
          } yield assertTrue(ok)
        }
      },
      test("cardPayment debits card liability and credits bank") {
        ZIO.scoped {
          for {
            tmp <- ZIO.attempt(Files.createTempFile("morney-", ".db"))
            _ <- ZIO.attempt(Files.deleteIfExists(tmp))
            ok <- Db.open(tmp).flatMap { conn =>
              val repo = SqliteLedgerRepository(conn)
              val journal = JournalService(repo)
              val quick = QuickEntryService(journal)
              for {
                _ <- Migrator.migrate(conn)
                book <- repo.createBook("home")
                bank <- repo.createAccount(book.id, "Bank", AccountType.Asset)
                card <- repo.createAccount(book.id, "Credit Card", AccountType.Liability)
                entry <- quick.cardPayment(
                  bookId = book.id,
                  date = LocalDate.parse("2025-12-31"),
                  bankAccountId = bank.id,
                  cardLiabilityAccountId = card.id,
                  amount = 15000L,
                  memo = Some("payment")
                )
              } yield entry.postings.size == 2 &&
                entry.postings.exists(p => p.accountId == card.id && p.side == PostingSide.Debit && p.amount == 15000L) &&
                entry.postings.exists(p => p.accountId == bank.id && p.side == PostingSide.Credit && p.amount == 15000L)
            }
          } yield assertTrue(ok)
        }
      }
    )
}

