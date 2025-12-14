package morney.storage

import zio.{Task, ZIO}

import java.io.InputStream
import java.sql.{Connection, PreparedStatement, ResultSet, Statement}
import java.time.Instant
import java.time.format.DateTimeFormatter

object Migrator {
  private val Migrations: List[String] =
    List(
      "db/migrations/V001__init.sql"
    )

  def migrate(conn: Connection): Task[Unit] =
    ZIO.attemptBlocking {
      ensureMigrationsTable(conn)
      val applied = loadAppliedVersions(conn)
      val pending = Migrations.filterNot(path => applied.contains(versionOf(path)))
      if (pending.nonEmpty) {
        withTransaction(conn) {
          pending.foreach { path =>
            val sql = readResource(path)
            executeSql(conn, sql)
            recordApplied(conn, versionOf(path))
          }
        }
      }
    }

  private def versionOf(resourcePath: String): String =
    resourcePath.split('/').lastOption.getOrElse(resourcePath).takeWhile(_ != '_')

  private def ensureMigrationsTable(conn: Connection): Unit = {
    val stmt = conn.createStatement()
    try {
      stmt.executeUpdate(
        """CREATE TABLE IF NOT EXISTS schema_migrations (
          |  version    TEXT PRIMARY KEY,
          |  applied_at TEXT NOT NULL
          |);""".stripMargin
      )
    } finally stmt.close()
  }

  private def loadAppliedVersions(conn: Connection): Set[String] = {
    val stmt = conn.createStatement()
    var rs: ResultSet = null
    try {
      rs = stmt.executeQuery("SELECT version FROM schema_migrations")
      val buf = scala.collection.mutable.Set.empty[String]
      while (rs.next()) buf += rs.getString(1)
      buf.toSet
    } finally {
      if (rs != null) rs.close()
      stmt.close()
    }
  }

  private def recordApplied(conn: Connection, version: String): Unit = {
    val sql = "INSERT INTO schema_migrations(version, applied_at) VALUES (?, ?)"
    val ps: PreparedStatement = conn.prepareStatement(sql)
    try {
      ps.setString(1, version)
      ps.setString(2, DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
      ps.executeUpdate()
    } finally ps.close()
  }

  private def withTransaction[A](conn: Connection)(thunk: => A): A = {
    val wasAutoCommit = conn.getAutoCommit
    conn.setAutoCommit(false)
    try {
      val out = thunk
      conn.commit()
      out
    } catch {
      case t: Throwable =>
        conn.rollback()
        throw t
    } finally {
      conn.setAutoCommit(wasAutoCommit)
    }
  }

  private def executeSql(conn: Connection, sql: String): Unit = {
    val statements = splitSqlStatements(sql)
    val stmt: Statement = conn.createStatement()
    try {
      statements.foreach(stmt.execute)
    } finally stmt.close()
  }

  private def readResource(path: String): String = {
    val is: InputStream =
      Option(getClass.getClassLoader.getResourceAsStream(path))
        .getOrElse(throw new IllegalStateException(s"Migration resource not found: $path"))

    try new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
    finally is.close()
  }

  private def splitSqlStatements(sql: String): List[String] =
    sql
      .linesIterator
      .map(_.trim)
      .filterNot(line => line.isEmpty || line.startsWith("--"))
      .mkString("\n")
      .split(';')
      .toList
      .map(_.trim)
      .filter(_.nonEmpty)
}
