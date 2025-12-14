package morney

import morney.domain.AccountType
import morney.services.AccountsService
import morney.storage.{Db, Migrator, SqliteLedgerRepository}
import zio.ZIO
import zio.test.{ZIOSpecDefault, assertTrue}

import java.nio.file.Files

object AccountsServiceSpec extends ZIOSpecDefault {
  override def spec =
    suite("accounts service")(
      test("createBookWithDefaults seeds default accounts") {
        ZIO.scoped {
          for {
            tmp <- ZIO.attempt(Files.createTempFile("morney-", ".db"))
            _ <- ZIO.attempt(Files.deleteIfExists(tmp))
            ok <- Db.open(tmp).flatMap { conn =>
              val repo = SqliteLedgerRepository(conn)
              val svc = AccountsService(repo)
              for {
                _ <- Migrator.migrate(conn)
                book <- svc.createBookWithDefaults("home")
                accounts <- svc.listAccounts(book.id, includeInactive = true)
              } yield accounts.exists(a => a.name == "Cash" && a.`type` == AccountType.Asset) &&
                accounts.exists(a => a.name == "Credit Card" && a.`type` == AccountType.Liability)
            }
          } yield assertTrue(ok)
        }
      }
    )
}

