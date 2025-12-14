package morney

import morney.storage.{Db, Migrator}
import zio.{Scope, ZIO}
import zio.test.{ZIOSpecDefault, assertTrue}

import java.nio.file.Files

object MigratorSpec extends ZIOSpecDefault {
  override def spec =
    suite("migrator")(
      test("applies V001 and creates core tables") {
        ZIO.scoped {
          for {
            tmp <- ZIO.attempt(Files.createTempFile("morney-", ".db"))
            _ <- ZIO.attempt(Files.deleteIfExists(tmp))
            ok <- Db.open(tmp).flatMap { conn =>
              for {
                _ <- Migrator.migrate(conn)
                tables <- ZIO.attemptBlocking {
                  val stmt = conn.prepareStatement(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name IN ('books','accounts','journal_entries','postings','imported_transactions','schema_migrations')"
                  )
                  try {
                    val rs = stmt.executeQuery()
                    val buf = scala.collection.mutable.Set.empty[String]
                    while (rs.next()) buf += rs.getString(1)
                    buf.toSet
                  } finally stmt.close()
                }
              } yield tables == Set("books", "accounts", "journal_entries", "postings", "imported_transactions", "schema_migrations")
            }
          } yield assertTrue(ok)
        }
      }
    )
}

