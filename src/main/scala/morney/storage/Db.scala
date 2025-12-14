package morney.storage

import zio.{Scope, Task, ZIO}

import java.nio.file.{Files, Path}
import java.sql.{Connection, DriverManager, Statement}

object Db {
  def open(dbPath: Path): ZIO[Scope, Throwable, Connection] =
    ZIO.acquireRelease(openUnsafe(dbPath))(conn => ZIO.attemptBlocking(conn.close()).ignoreLogged)

  private def openUnsafe(dbPath: Path): Task[Connection] =
    ZIO.attemptBlocking {
      val parent = dbPath.getParent
      if (parent != null) Files.createDirectories(parent)

      val conn = DriverManager.getConnection(s"jdbc:sqlite:${dbPath.toAbsolutePath}")
      enableForeignKeys(conn)
      conn
    }

  private def enableForeignKeys(conn: Connection): Unit = {
    val stmt: Statement = conn.createStatement()
    try stmt.execute("PRAGMA foreign_keys=ON;")
    finally stmt.close()
  }
}

