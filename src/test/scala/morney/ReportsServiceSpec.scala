package morney

import morney.domain.*
import morney.services.{JournalService, QuickEntryService, ReportsService, SplitLine}
import morney.storage.{Db, Migrator, SqliteLedgerRepository}
import zio.{Chunk, ZIO}
import zio.test.{ZIOSpecDefault, assertTrue}

import java.nio.file.Files
import java.time.LocalDate

object ReportsServiceSpec extends ZIOSpecDefault {
  override def spec =
    suite("reports service")(
      test("balanceSheet and incomeStatement compute correct totals") {
        ZIO.scoped {
          for {
            tmp <- ZIO.attempt(Files.createTempFile("morney-", ".db"))
            _ <- ZIO.attempt(Files.deleteIfExists(tmp))
            ok <- Db.open(tmp).flatMap { conn =>
              val repo = SqliteLedgerRepository(conn)
              val journal = JournalService(repo)
              val quick = QuickEntryService(journal)
              val reports = ReportsService(repo)

              for {
                _ <- Migrator.migrate(conn)
                book <- repo.createBook("home")
                cash <- repo.createAccount(book.id, "Cash", AccountType.Asset)
                bank <- repo.createAccount(book.id, "Bank", AccountType.Asset)
                salary <- repo.createAccount(book.id, "Salary", AccountType.Revenue)
                food <- repo.createAccount(book.id, "Food", AccountType.Expense)

                _ <- quick.income(book.id, LocalDate.parse("2025-12-01"), bank.id, salary.id, 100000L, Some("salary"))
                _ <- quick.expense(book.id, LocalDate.parse("2025-12-02"), cash.id, food.id, 1200L, Some("grocery"))
                _ <- quick.transfer(book.id, LocalDate.parse("2025-12-03"), bank.id, cash.id, 20000L, Some("withdraw"))

                bs <- reports.balanceSheet(book.id, LocalDate.parse("2025-12-31"))
                pnl <- reports.incomeStatement(book.id, LocalDate.parse("2025-12-01"), LocalDate.parse("2025-12-31"))
              } yield {
                val assetsTotal = bs.totalsByType.getOrElse(AccountType.Asset, 0L)
                val revenueTotal = pnl.revenueTotal
                val expenseTotal = pnl.expenseTotal

                assetsTotal == (100000L - 1200L) &&
                revenueTotal == 100000L &&
                expenseTotal == 1200L &&
                pnl.netIncome == 98800L
              }
            }
          } yield assertTrue(ok)
        }
      },
      test("ledger produces running balance with opening balance") {
        ZIO.scoped {
          for {
            tmp <- ZIO.attempt(Files.createTempFile("morney-", ".db"))
            _ <- ZIO.attempt(Files.deleteIfExists(tmp))
            ok <- Db.open(tmp).flatMap { conn =>
              val repo = SqliteLedgerRepository(conn)
              val journal = JournalService(repo)
              val quick = QuickEntryService(journal)
              val reports = ReportsService(repo)

              for {
                _ <- Migrator.migrate(conn)
                book <- repo.createBook("home")
                cash <- repo.createAccount(book.id, "Cash", AccountType.Asset)
                food <- repo.createAccount(book.id, "Food", AccountType.Expense)

                _ <- quick.expense(book.id, LocalDate.parse("2025-12-01"), cash.id, food.id, 500L, Some("snack")) // cash -500
                _ <- quick.expense(book.id, LocalDate.parse("2025-12-10"), cash.id, food.id, 700L, Some("lunch")) // cash -700

                led <- reports.ledger(
                  bookId = book.id,
                  accountId = cash.id,
                  from = Some(LocalDate.parse("2025-12-05")),
                  to = Some(LocalDate.parse("2025-12-31"))
                )
              } yield {
                led.openingBalance == -500L &&
                led.lines.size == 1 &&
                led.lines.head.delta == -700L &&
                led.closingBalance == -1200L
              }
            }
          } yield assertTrue(ok)
        }
      }
    )
}

