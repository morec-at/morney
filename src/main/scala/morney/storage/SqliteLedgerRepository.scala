package morney.storage

import morney.domain.*
import zio.{Chunk, Task, ZIO}

import java.sql.{Connection, PreparedStatement, ResultSet}
import java.time.format.DateTimeFormatter
import java.time.{Instant, LocalDate}
import java.util.UUID

final class SqliteLedgerRepository(
  conn: Connection,
  now: () => Instant = () => Instant.now()
) extends LedgerRepository {
  override def createBook(name: String): Task[Book] =
    ZIO.attemptBlocking {
      val id = UUID.randomUUID().toString
      val createdAt = now()
      val ps = conn.prepareStatement("INSERT INTO books(id, name, created_at) VALUES (?, ?, ?)")
      try {
        ps.setString(1, id)
        ps.setString(2, name)
        ps.setString(3, fmtInstant(createdAt))
        ps.executeUpdate()
      } finally ps.close()
      Book(id = id, name = name, createdAt = createdAt)
    }

  override def listBooks(): Task[List[Book]] =
    ZIO.attemptBlocking {
      val ps = conn.prepareStatement("SELECT id, name, created_at FROM books ORDER BY created_at ASC")
      var rs: ResultSet = null
      try {
        rs = ps.executeQuery()
        val buf = List.newBuilder[Book]
        while (rs.next()) {
          buf += Book(
            id = rs.getString(1),
            name = rs.getString(2),
            createdAt = Instant.parse(rs.getString(3))
          )
        }
        buf.result()
      } finally {
        if (rs != null) rs.close()
        ps.close()
      }
    }

  override def createAccount(bookId: Id, name: String, accountType: AccountType): Task[Account] =
    ZIO.attemptBlocking {
      val id = UUID.randomUUID().toString
      val createdAt = now()
      val ps =
        conn.prepareStatement("INSERT INTO accounts(id, book_id, name, type, is_active, created_at) VALUES (?, ?, ?, ?, ?, ?)")
      try {
        ps.setString(1, id)
        ps.setString(2, bookId)
        ps.setString(3, name)
        ps.setString(4, encodeAccountType(accountType))
        ps.setInt(5, 1)
        ps.setString(6, fmtInstant(createdAt))
        ps.executeUpdate()
      } finally ps.close()
      Account(id = id, bookId = bookId, name = name, `type` = accountType, isActive = true, createdAt = createdAt)
    }

  override def setAccountActive(accountId: Id, isActive: Boolean): Task[Unit] =
    ZIO.attemptBlocking {
      val ps = conn.prepareStatement("UPDATE accounts SET is_active=? WHERE id=?")
      try {
        ps.setInt(1, if (isActive) 1 else 0)
        ps.setString(2, accountId)
        ps.executeUpdate()
      } finally ps.close()
      ()
    }

  override def listAccounts(bookId: Id, includeInactive: Boolean): Task[List[Account]] =
    ZIO.attemptBlocking {
      val sql =
        if (includeInactive)
          "SELECT id, book_id, name, type, is_active, created_at FROM accounts WHERE book_id=? ORDER BY created_at ASC"
        else
          "SELECT id, book_id, name, type, is_active, created_at FROM accounts WHERE book_id=? AND is_active=1 ORDER BY created_at ASC"

      val ps = conn.prepareStatement(sql)
      var rs: ResultSet = null
      try {
        ps.setString(1, bookId)
        rs = ps.executeQuery()
        val buf = List.newBuilder[Account]
        while (rs.next()) {
          buf += Account(
            id = rs.getString(1),
            bookId = rs.getString(2),
            name = rs.getString(3),
            `type` = decodeAccountType(rs.getString(4)),
            isActive = rs.getInt(5) == 1,
            createdAt = Instant.parse(rs.getString(6))
          )
        }
        buf.result()
      } finally {
        if (rs != null) rs.close()
        ps.close()
      }
    }

  override def createJournalEntry(
    bookId: Id,
    date: LocalDate,
    memo: Option[String],
    postings: Chunk[Posting]
  ): Task[JournalEntry] =
    ZIO.attemptBlocking {
      val entryId = UUID.randomUUID().toString
      val timestamp = now()
      withTransaction(conn) {
        insertEntry(entryId, bookId, date, memo, timestamp)
        postings.foreach { posting =>
          val postingId = UUID.randomUUID().toString
          insertPosting(
            Posting(
              id = postingId,
              entryId = entryId,
              accountId = posting.accountId,
              side = posting.side,
              amount = posting.amount,
              description = posting.description
            )
          )
        }
      }

      val persistedPostings = postings.map { p =>
        p.copy(entryId = entryId)
      }
      JournalEntry(
        id = entryId,
        bookId = bookId,
        date = date,
        memo = memo,
        postings = persistedPostings,
        createdAt = timestamp,
        updatedAt = timestamp
      )
    }

  override def updateJournalEntry(
    entryId: Id,
    date: LocalDate,
    memo: Option[String],
    postings: Chunk[Posting]
  ): Task[JournalEntry] =
    ZIO.attemptBlocking {
      val timestamp = now()
      withTransaction(conn) {
        val updatedRows = updateEntry(entryId, date, memo, timestamp)
        if (updatedRows == 0) throw new NoSuchElementException(s"Journal entry not found: $entryId")

        deletePostingsForEntry(entryId)
        postings.foreach { posting =>
          val postingId = UUID.randomUUID().toString
          insertPosting(
            Posting(
              id = postingId,
              entryId = entryId,
              accountId = posting.accountId,
              side = posting.side,
              amount = posting.amount,
              description = posting.description
            )
          )
        }
      }

      getJournalEntryUnsafe(entryId)
    }

  override def getJournalEntry(entryId: Id): Task[Option[JournalEntry]] =
    ZIO.attemptBlocking {
      val ps = conn.prepareStatement(
        "SELECT id, book_id, entry_date, memo, created_at, updated_at FROM journal_entries WHERE id=?"
      )
      var rs: ResultSet = null
      try {
        ps.setString(1, entryId)
        rs = ps.executeQuery()
        if (!rs.next()) None
        else {
          val entry = JournalEntry(
            id = rs.getString(1),
            bookId = rs.getString(2),
            date = LocalDate.parse(rs.getString(3)),
            memo = Option(rs.getString(4)),
            postings = Chunk.empty,
            createdAt = Instant.parse(rs.getString(5)),
            updatedAt = Instant.parse(rs.getString(6))
          )
          Some(entry.copy(postings = loadPostingsForEntry(entry.id)))
        }
      } finally {
        if (rs != null) rs.close()
        ps.close()
      }
    }

  override def listJournalEntries(bookId: Id, range: DateRange): Task[List[JournalEntry]] =
    ZIO.attemptBlocking {
      val (sql, bind) = buildListEntriesSql(bookId, range)
      val ps = conn.prepareStatement(sql)
      var rs: ResultSet = null
      try {
        bind(ps)
        rs = ps.executeQuery()
        val entries = List.newBuilder[JournalEntry]
        while (rs.next()) {
          entries += JournalEntry(
            id = rs.getString(1),
            bookId = rs.getString(2),
            date = LocalDate.parse(rs.getString(3)),
            memo = Option(rs.getString(4)),
            postings = Chunk.empty,
            createdAt = Instant.parse(rs.getString(5)),
            updatedAt = Instant.parse(rs.getString(6))
          )
        }

        val base = entries.result()
        val postingsByEntry = loadPostingsForEntries(base.map(_.id))
        base.map(e => e.copy(postings = postingsByEntry.getOrElse(e.id, Chunk.empty)))
      } finally {
        if (rs != null) rs.close()
        ps.close()
      }
    }

  override def insertImportedTransaction(txn: ImportedTransaction): Task[Unit] =
    ZIO.attemptBlocking {
      val ps = conn.prepareStatement(
        """INSERT INTO imported_transactions(
          |  id, book_id, source, txn_date, amount, payee_or_memo, raw_json, dedup_key, status, linked_entry_id
          |) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".stripMargin
      )
      try {
        ps.setString(1, txn.id)
        ps.setString(2, txn.bookId)
        ps.setString(3, txn.source)
        ps.setString(4, txn.date.toString)
        ps.setLong(5, txn.amount)
        txn.payeeOrMemo match {
          case Some(v) => ps.setString(6, v)
          case None    => ps.setNull(6, java.sql.Types.VARCHAR)
        }
        ps.setString(7, txn.raw)
        ps.setString(8, txn.dedupKey)
        ps.setString(9, encodeImportStatus(txn.status))
        txn.linkedEntryId match {
          case Some(v) => ps.setString(10, v)
          case None    => ps.setNull(10, java.sql.Types.VARCHAR)
        }
        ps.executeUpdate()
      } finally ps.close()
      ()
    }.catchSome { case e: org.sqlite.SQLiteException if isUniqueConstraintViolation(e) =>
      ZIO.fail(new DuplicateImportedTransaction(txn.bookId, txn.source, txn.dedupKey, e))
    }

  override def updateImportedTransactionByDedupKey(
    bookId: Id,
    source: String,
    dedupKey: String,
    date: LocalDate,
    amount: Long,
    payeeOrMemo: Option[String],
    raw: String
  ): Task[Unit] =
    ZIO.attemptBlocking {
      val ps = conn.prepareStatement(
        """UPDATE imported_transactions
          |SET txn_date=?, amount=?, payee_or_memo=?, raw_json=?
          |WHERE book_id=? AND source=? AND dedup_key=?""".stripMargin
      )
      try {
        ps.setString(1, date.toString)
        ps.setLong(2, amount)
        payeeOrMemo match {
          case Some(v) => ps.setString(3, v)
          case None    => ps.setNull(3, java.sql.Types.VARCHAR)
        }
        ps.setString(4, raw)
        ps.setString(5, bookId)
        ps.setString(6, source)
        ps.setString(7, dedupKey)
        ps.executeUpdate()
      } finally ps.close()
      ()
    }

  override def getImportedTransaction(id: Id): Task[Option[ImportedTransaction]] =
    ZIO.attemptBlocking {
      val ps = conn.prepareStatement(
        """SELECT id, book_id, source, txn_date, amount, payee_or_memo, raw_json, dedup_key, status, linked_entry_id
          |FROM imported_transactions
          |WHERE id=?""".stripMargin
      )
      var rs: ResultSet = null
      try {
        ps.setString(1, id)
        rs = ps.executeQuery()
        if (!rs.next()) None
        else {
          Some(
            ImportedTransaction(
              id = rs.getString(1),
              bookId = rs.getString(2),
              source = rs.getString(3),
              date = LocalDate.parse(rs.getString(4)),
              amount = rs.getLong(5),
              payeeOrMemo = Option(rs.getString(6)),
              raw = rs.getString(7),
              dedupKey = rs.getString(8),
              status = decodeImportStatus(rs.getString(9)),
              linkedEntryId = Option(rs.getString(10))
            )
          )
        }
      } finally {
        if (rs != null) rs.close()
        ps.close()
      }
    }

  override def listImportedTransactions(bookId: Id, source: Option[String], status: Option[ImportStatus]): Task[List[ImportedTransaction]] =
    ZIO.attemptBlocking {
      val base =
        new StringBuilder(
          """SELECT id, book_id, source, txn_date, amount, payee_or_memo, raw_json, dedup_key, status, linked_entry_id
            |FROM imported_transactions
            |WHERE book_id=?""".stripMargin
        )
      val binds = scala.collection.mutable.ListBuffer.empty[PreparedStatement => Unit]
      binds += ((ps: PreparedStatement) => ps.setString(1, bookId))
      var idx = 2

      source.foreach { s =>
        base.append(" AND source=?")
        val i = idx
        binds += ((ps: PreparedStatement) => ps.setString(i, s))
        idx += 1
      }
      status.foreach { st =>
        base.append(" AND status=?")
        val i = idx
        binds += ((ps: PreparedStatement) => ps.setString(i, encodeImportStatus(st)))
        idx += 1
      }
      base.append(" ORDER BY txn_date ASC, rowid ASC")

      val ps = conn.prepareStatement(base.toString)
      var rs: ResultSet = null
      try {
        binds.foreach(_(ps))
        rs = ps.executeQuery()
        val buf = List.newBuilder[ImportedTransaction]
        while (rs.next()) {
          buf += ImportedTransaction(
            id = rs.getString(1),
            bookId = rs.getString(2),
            source = rs.getString(3),
            date = LocalDate.parse(rs.getString(4)),
            amount = rs.getLong(5),
            payeeOrMemo = Option(rs.getString(6)),
            raw = rs.getString(7),
            dedupKey = rs.getString(8),
            status = decodeImportStatus(rs.getString(9)),
            linkedEntryId = Option(rs.getString(10))
          )
        }
        buf.result()
      } finally {
        if (rs != null) rs.close()
        ps.close()
      }
    }

  override def setImportedTransactionStatus(id: Id, status: ImportStatus, linkedEntryId: Option[Id]): Task[Unit] =
    ZIO.attemptBlocking {
      val ps = conn.prepareStatement(
        "UPDATE imported_transactions SET status=?, linked_entry_id=? WHERE id=?"
      )
      try {
        ps.setString(1, encodeImportStatus(status))
        linkedEntryId match {
          case Some(v) => ps.setString(2, v)
          case None    => ps.setNull(2, java.sql.Types.VARCHAR)
        }
        ps.setString(3, id)
        ps.executeUpdate()
      } finally ps.close()
      ()
    }

  private def insertEntry(
    entryId: Id,
    bookId: Id,
    date: LocalDate,
    memo: Option[String],
    timestamp: Instant
  ): Unit = {
    val ps = conn.prepareStatement(
      "INSERT INTO journal_entries(id, book_id, entry_date, memo, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)"
    )
    try {
      ps.setString(1, entryId)
      ps.setString(2, bookId)
      ps.setString(3, date.toString)
      memo match {
        case Some(value) => ps.setString(4, value)
        case None        => ps.setNull(4, java.sql.Types.VARCHAR)
      }
      ps.setString(5, fmtInstant(timestamp))
      ps.setString(6, fmtInstant(timestamp))
      ps.executeUpdate()
    } finally ps.close()
  }

  private def updateEntry(
    entryId: Id,
    date: LocalDate,
    memo: Option[String],
    updatedAt: Instant
  ): Int = {
    val ps = conn.prepareStatement(
      "UPDATE journal_entries SET entry_date=?, memo=?, updated_at=? WHERE id=?"
    )
    try {
      ps.setString(1, date.toString)
      memo match {
        case Some(value) => ps.setString(2, value)
        case None        => ps.setNull(2, java.sql.Types.VARCHAR)
      }
      ps.setString(3, fmtInstant(updatedAt))
      ps.setString(4, entryId)
      ps.executeUpdate()
    } finally ps.close()
  }

  private def deletePostingsForEntry(entryId: Id): Unit = {
    val ps = conn.prepareStatement("DELETE FROM postings WHERE entry_id=?")
    try {
      ps.setString(1, entryId)
      ps.executeUpdate()
    } finally ps.close()
  }

  private def insertPosting(p: Posting): Unit = {
    val ps =
      conn.prepareStatement("INSERT INTO postings(id, entry_id, account_id, side, amount, description) VALUES (?, ?, ?, ?, ?, ?)")
    try {
      ps.setString(1, p.id)
      ps.setString(2, p.entryId)
      ps.setString(3, p.accountId)
      ps.setString(4, encodePostingSide(p.side))
      ps.setLong(5, p.amount)
      p.description match {
        case Some(value) => ps.setString(6, value)
        case None        => ps.setNull(6, java.sql.Types.VARCHAR)
      }
      ps.executeUpdate()
    } finally ps.close()
  }

  private def getJournalEntryUnsafe(entryId: Id): JournalEntry = {
    val ps = conn.prepareStatement(
      "SELECT id, book_id, entry_date, memo, created_at, updated_at FROM journal_entries WHERE id=?"
    )
    var rs: ResultSet = null
    try {
      ps.setString(1, entryId)
      rs = ps.executeQuery()
      if (!rs.next()) throw new NoSuchElementException(s"Journal entry not found: $entryId")
      val entry = JournalEntry(
        id = rs.getString(1),
        bookId = rs.getString(2),
        date = LocalDate.parse(rs.getString(3)),
        memo = Option(rs.getString(4)),
        postings = Chunk.empty,
        createdAt = Instant.parse(rs.getString(5)),
        updatedAt = Instant.parse(rs.getString(6))
      )
      entry.copy(postings = loadPostingsForEntry(entry.id))
    } finally {
      if (rs != null) rs.close()
      ps.close()
    }
  }

  private def loadPostingsForEntry(entryId: Id): Chunk[Posting] =
    loadPostingsForEntries(List(entryId)).getOrElse(entryId, Chunk.empty)

  private def loadPostingsForEntries(entryIds: List[Id]): Map[Id, Chunk[Posting]] =
    if (entryIds.isEmpty) Map.empty
    else {
      val placeholders = entryIds.map(_ => "?").mkString(",")
      val sql =
        s"SELECT id, entry_id, account_id, side, amount, description FROM postings WHERE entry_id IN ($placeholders) ORDER BY rowid ASC"
      val ps = conn.prepareStatement(sql)
      var rs: ResultSet = null
      try {
        entryIds.zipWithIndex.foreach { case (id, idx) => ps.setString(idx + 1, id) }
        rs = ps.executeQuery()
        val buf = scala.collection.mutable.Map.empty[Id, Chunk[Posting]].withDefaultValue(Chunk.empty)
        while (rs.next()) {
          val posting = Posting(
            id = rs.getString(1),
            entryId = rs.getString(2),
            accountId = rs.getString(3),
            side = decodePostingSide(rs.getString(4)),
            amount = rs.getLong(5),
            description = Option(rs.getString(6))
          )
          buf.update(posting.entryId, buf(posting.entryId) :+ posting)
        }
        buf.toMap
      } finally {
        if (rs != null) rs.close()
        ps.close()
      }
    }

  private def buildListEntriesSql(
    bookId: Id,
    range: DateRange
  ): (String, PreparedStatement => Unit) = {
    val base = new StringBuilder(
      "SELECT id, book_id, entry_date, memo, created_at, updated_at FROM journal_entries WHERE book_id=?"
    )
    val binds = scala.collection.mutable.ListBuffer.empty[PreparedStatement => Unit]
    binds += ((ps: PreparedStatement) => ps.setString(1, bookId))
    var nextIdx = 2

    range.from.foreach { from =>
      base.append(" AND entry_date >= ?")
      val idx = nextIdx
      binds += ((ps: PreparedStatement) => ps.setString(idx, from.toString))
      nextIdx += 1
    }
    range.to.foreach { to =>
      base.append(" AND entry_date <= ?")
      val idx = nextIdx
      binds += ((ps: PreparedStatement) => ps.setString(idx, to.toString))
      nextIdx += 1
    }
    base.append(" ORDER BY entry_date ASC, created_at ASC")

    (base.toString, (ps: PreparedStatement) => binds.foreach(_(ps)))
  }

  private def fmtInstant(i: Instant): String =
    DateTimeFormatter.ISO_INSTANT.format(i)

  private def encodeAccountType(t: AccountType): String =
    t match {
      case AccountType.Asset     => "asset"
      case AccountType.Liability => "liability"
      case AccountType.Equity    => "equity"
      case AccountType.Revenue   => "revenue"
      case AccountType.Expense   => "expense"
    }

  private def decodeAccountType(s: String): AccountType =
    s match {
      case "asset"     => AccountType.Asset
      case "liability" => AccountType.Liability
      case "equity"    => AccountType.Equity
      case "revenue"   => AccountType.Revenue
      case "expense"   => AccountType.Expense
      case other       => throw new IllegalArgumentException(s"Unknown account type: $other")
    }

  private def encodePostingSide(s: PostingSide): String =
    s match {
      case PostingSide.Debit  => "debit"
      case PostingSide.Credit => "credit"
    }

  private def decodePostingSide(s: String): PostingSide =
    s match {
      case "debit"  => PostingSide.Debit
      case "credit" => PostingSide.Credit
      case other    => throw new IllegalArgumentException(s"Unknown posting side: $other")
    }

  private def encodeImportStatus(s: ImportStatus): String =
    s match {
      case ImportStatus.Pending => "pending"
      case ImportStatus.Linked  => "linked"
      case ImportStatus.Ignored => "ignored"
    }

  private def decodeImportStatus(s: String): ImportStatus =
    s match {
      case "pending" => ImportStatus.Pending
      case "linked"  => ImportStatus.Linked
      case "ignored" => ImportStatus.Ignored
      case other     => throw new IllegalArgumentException(s"Unknown import status: $other")
    }

  private def isUniqueConstraintViolation(e: org.sqlite.SQLiteException): Boolean =
    e.getMessage != null && e.getMessage.contains("UNIQUE constraint failed")

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
}

final class DuplicateImportedTransaction(
  val bookId: Id,
  val source: String,
  val dedupKey: String,
  cause: Throwable
) extends RuntimeException(s"Duplicate imported transaction (bookId=$bookId source=$source dedupKey=$dedupKey)", cause)
