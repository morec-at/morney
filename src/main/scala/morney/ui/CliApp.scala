package morney.ui

import morney.AppConfig
import morney.domain.*
import morney.importers.CsvMapping
import morney.services.*
import morney.storage.{Db, Migrator, SqliteLedgerRepository}
import zio.{Chunk, Console, ExitCode, Scope, ZIO, ZIOAppArgs, ZIOAppDefault}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.time.LocalDate

object CliApp extends ZIOAppDefault {
  override def run: ZIO[ZIOAppArgs & Scope, Any, ExitCode] =
    for {
      rawArgs <- ZIOAppArgs.getArgs.map(_.toList)
      parsed <- ZIO.fromEither(AppConfig.fromArgs(rawArgs)).mapError(new IllegalArgumentException(_))
      (config, rest) = parsed
      exitCode <- dispatch(config, rest)
    } yield exitCode

  private def dispatch(config: AppConfig, rest: List[String]): ZIO[Scope, Throwable, ExitCode] =
    rest match {
      case "doctor" :: Nil =>
        doctor(config).as(ExitCode.success)
      case "book" :: tail =>
        withServices(config) { env =>
          bookCmd(env, tail).as(ExitCode.success)
        }.catchAll(e => Console.printLineError(e.getMessage).as(ExitCode.failure))
      case "accounts" :: tail =>
        withServices(config) { env =>
          accountsCmd(env, tail).as(ExitCode.success)
        }.catchAll(e => Console.printLineError(e.getMessage).as(ExitCode.failure))
      case "entry" :: tail =>
        withServices(config) { env =>
          entryCmd(env, tail).as(ExitCode.success)
        }.catchAll(e => Console.printLineError(e.getMessage).as(ExitCode.failure))
      case "quick" :: tail =>
        withServices(config) { env =>
          quickCmd(env, tail).as(ExitCode.success)
        }.catchAll(e => Console.printLineError(e.getMessage).as(ExitCode.failure))
      case "reports" :: tail =>
        withServices(config) { env =>
          reportsCmd(env, tail).as(ExitCode.success)
        }.catchAll(e => Console.printLineError(e.getMessage).as(ExitCode.failure))
      case "import" :: tail =>
        withServices(config) { env =>
          importCmd(env, tail).as(ExitCode.success)
        }.catchAll(e => Console.printLineError(e.getMessage).as(ExitCode.failure))
      case "export" :: tail =>
        withServices(config) { env =>
          exportCmd(env, tail).as(ExitCode.success)
        }.catchAll(e => Console.printLineError(e.getMessage).as(ExitCode.failure))
      case "backup" :: tail =>
        backupCmd(config, tail).as(ExitCode.success).catchAll(e => Console.printLineError(e.getMessage).as(ExitCode.failure))
      case "restore" :: tail =>
        restoreCmd(config, tail).as(ExitCode.success).catchAll(e => Console.printLineError(e.getMessage).as(ExitCode.failure))
      case "help" :: _ =>
        printHelp.as(ExitCode.success)
      case Nil =>
        printHelp.as(ExitCode.success)
      case other =>
        Console.printLineError(s"Unknown command: ${other.mkString(" ")}") *>
          printHelp.as(ExitCode.failure)
    }

  private def doctor(config: AppConfig): ZIO[Scope, Throwable, Unit] =
    Db.open(config.dbPath).flatMap { conn =>
      Migrator.migrate(conn) *>
        Console.printLine("OK") *>
        Console.printLine(s"dbPath=${config.dbPath.toAbsolutePath}")
    }

  private final case class CliEnv(
    config: AppConfig,
    repo: SqliteLedgerRepository,
    accounts: AccountsService,
    journal: JournalService,
    quick: QuickEntryService,
    reports: ReportsService,
    imports: ImportService,
    exports: ExportService
  )

  private def withServices[A](config: AppConfig)(f: CliEnv => ZIO[Scope, Throwable, A]): ZIO[Scope, Throwable, A] =
    Db.open(config.dbPath).flatMap { conn =>
      for {
        _ <- Migrator.migrate(conn)
        repo = SqliteLedgerRepository(conn)
        journal = JournalService(repo)
        env = CliEnv(
          config = config,
          repo = repo,
          accounts = AccountsService(repo),
          journal = journal,
          quick = QuickEntryService(journal),
          reports = ReportsService(repo),
          imports = ImportService(repo, journal),
          exports = ExportService(repo)
        )
        out <- f(env)
      } yield out
    }

  // ---------------------------------------------------------------------------
  // book
  // ---------------------------------------------------------------------------
  private def bookCmd(env: CliEnv, args: List[String]): ZIO[Any, Throwable, Unit] =
    args match {
      case "create" :: tail =>
        for {
          parsed <- parseFlagsOrFail(tail)
          (flags, _) = parsed
          name <- ZIO.fromOption(flags.get("name")).orElseFail(new IllegalArgumentException("Missing --name"))
          book <- env.accounts.createBookWithDefaults(name)
          _ <- Console.printLine(s"book.id=${book.id}")
          _ <- Console.printLine(s"book.name=${book.name}")
        } yield ()
      case "list" :: _ =>
        env.repo.listBooks().flatMap { books =>
          Console.printLine(books.map(b => s"${b.id}\t${b.name}").mkString("\n"))
        }
      case "select" :: tail =>
        for {
          parsed <- parseFlagsOrFail(tail)
          (flags, _) = parsed
          bookId <- ZIO.fromOption(flags.get("book")).orElseFail(new IllegalArgumentException("Missing --book"))
          _ <- writeSelectedBookId(env.config.dbPath, bookId)
          _ <- Console.printLine(s"selectedBookId=$bookId")
        } yield ()
      case "current" :: _ =>
        readSelectedBookId(env.config.dbPath).flatMap {
          case Some(id) => Console.printLine(id)
          case None     => Console.printLineError("No selected book. Use: book select --book <id>")
        }
      case _ =>
        Console.printLine(
          """Usage:
            |  book create --name <name>
            |  book list
            |  book select --book <bookId>
            |  book current
            |""".stripMargin
        )
    }

  // ---------------------------------------------------------------------------
  // accounts
  // ---------------------------------------------------------------------------
  private def accountsCmd(env: CliEnv, args: List[String]): ZIO[Any, Throwable, Unit] =
    args match {
      case "list" :: tail =>
        for {
          parsed <- parseFlagsOrFail(tail)
          (flags, _) = parsed
          bookId <- resolveBookId(env.config.dbPath, flags)
          includeInactive = flags.get("include-inactive").contains("true")
          accounts <- env.accounts.listAccounts(bookId, includeInactive = includeInactive)
          _ <- Console.printLine(accounts.map(a => s"${a.id}\t${a.name}\t${encodeAccountType(a.`type`)}\tactive=${a.isActive}").mkString("\n"))
        } yield ()
      case "add" :: tail =>
        for {
          parsed <- parseFlagsOrFail(tail)
          (flags, _) = parsed
          bookId <- resolveBookId(env.config.dbPath, flags)
          name <- ZIO.fromOption(flags.get("name")).orElseFail(new IllegalArgumentException("Missing --name"))
          tpe <- ZIO.fromOption(flags.get("type")).orElseFail(new IllegalArgumentException("Missing --type"))
          accountType <- fromEitherString(parseAccountType(tpe))
          account <- env.accounts.createAccount(bookId, name, accountType)
          _ <- Console.printLine(s"account.id=${account.id}")
        } yield ()
      case "disable" :: tail =>
        for {
          parsed <- parseFlagsOrFail(tail)
          (flags, _) = parsed
          id <- ZIO.fromOption(flags.get("id")).orElseFail(new IllegalArgumentException("Missing --id"))
          _ <- env.accounts.disableAccount(id)
          _ <- Console.printLine("OK")
        } yield ()
      case _ =>
        Console.printLine(
          """Usage:
            |  accounts list [--book <bookId>] [--include-inactive true]
            |  accounts add --name <name> --type <asset|liability|equity|revenue|expense> [--book <bookId>]
            |  accounts disable --id <accountId>
            |""".stripMargin
        )
    }

  // ---------------------------------------------------------------------------
  // entry
  // ---------------------------------------------------------------------------
  private def entryCmd(env: CliEnv, args: List[String]): ZIO[Any, Throwable, Unit] =
    args match {
      case "create" :: tail =>
        for {
          parsed <- parseFlagsOrFail(tail)
          (flags, positional) = parsed
          bookId <- resolveBookId(env.config.dbPath, flags)
          dateStr <- ZIO.fromOption(flags.get("date")).orElseFail(new IllegalArgumentException("Missing --date"))
          date = LocalDate.parse(dateStr)
          memo = flags.get("memo")
          postings <- fromEitherString(parsePostingsFromFlags(flags, positional))
          res <- env.journal.createEntry(bookId, date, memo, postings).either
          _ <- res match {
            case Right(entry) => Console.printLine(s"entry.id=${entry.id}")
            case Left(JournalServiceError.Validation(errors)) =>
              Console.printLineError(errors.map(_.message).mkString("\n")) *> ZIO.fail(new RuntimeException("Validation failed"))
            case Left(JournalServiceError.Storage(t)) =>
              ZIO.fail(t)
          }
        } yield ()
      case "list" :: tail =>
        for {
          parsed <- parseFlagsOrFail(tail)
          (flags, _) = parsed
          bookId <- resolveBookId(env.config.dbPath, flags)
          from = flags.get("from").map(LocalDate.parse)
          to = flags.get("to").map(LocalDate.parse)
          entries <- env.repo.listJournalEntries(bookId, morney.storage.DateRange(from, to))
          _ <- Console.printLine(entries.map(e => s"${e.id}\t${e.date}\t${e.memo.getOrElse("")}\tpostings=${e.postings.size}").mkString("\n"))
        } yield ()
      case "show" :: tail =>
        for {
          parsed <- parseFlagsOrFail(tail)
          (flags, _) = parsed
          id <- ZIO.fromOption(flags.get("id")).orElseFail(new IllegalArgumentException("Missing --id"))
          entry <- env.repo.getJournalEntry(id).flatMap(ZIO.fromOption(_)).orElseFail(new NoSuchElementException(s"Entry not found: $id"))
          _ <- Console.printLine(s"id=${entry.id}")
          _ <- Console.printLine(s"date=${entry.date}")
          _ <- Console.printLine(s"memo=${entry.memo.getOrElse("")}")
          _ <- Console.printLine(
            entry.postings
              .map(p => s"${encodePostingSide(p.side)}\t${p.accountId}\t${p.amount}\t${p.description.getOrElse("")}")
              .mkString("\n")
          )
        } yield ()
      case _ =>
        Console.printLine(
          """Usage:
            |  entry create --date <yyyy-mm-dd> [--memo <text>] [--book <bookId>] --debit <accountId> <amount>... --credit <accountId> <amount>...
            |  entry list [--book <bookId>] [--from <yyyy-mm-dd>] [--to <yyyy-mm-dd>]
            |  entry show --id <entryId>
            |""".stripMargin
        )
    }

  // ---------------------------------------------------------------------------
  // quick
  // ---------------------------------------------------------------------------
  private def quickCmd(env: CliEnv, args: List[String]): ZIO[Any, Throwable, Unit] =
    args match {
      case "expense" :: tail =>
        for {
          parsed <- parseFlagsOrFail(tail)
          (flags, _) = parsed
          bookId <- resolveBookId(env.config.dbPath, flags)
          date = LocalDate.parse(flags.getOrElse("date", throw new IllegalArgumentException("Missing --date")))
          payFrom = flags.getOrElse("pay-from", throw new IllegalArgumentException("Missing --pay-from"))
          expense = flags.getOrElse("expense", throw new IllegalArgumentException("Missing --expense"))
          amount = flags.getOrElse("amount", throw new IllegalArgumentException("Missing --amount")).toLong
          memo = flags.get("memo")
          entry <- env.quick.expense(bookId, date, payFrom, expense, amount, memo).mapError(journalErrorToThrowable)
          _ <- Console.printLine(s"entry.id=${entry.id}")
        } yield ()
      case "income" :: tail =>
        for {
          parsed <- parseFlagsOrFail(tail)
          (flags, _) = parsed
          bookId <- resolveBookId(env.config.dbPath, flags)
          date = LocalDate.parse(flags.getOrElse("date", throw new IllegalArgumentException("Missing --date")))
          depositTo = flags.getOrElse("deposit-to", throw new IllegalArgumentException("Missing --deposit-to"))
          revenue = flags.getOrElse("revenue", throw new IllegalArgumentException("Missing --revenue"))
          amount = flags.getOrElse("amount", throw new IllegalArgumentException("Missing --amount")).toLong
          memo = flags.get("memo")
          entry <- env.quick.income(bookId, date, depositTo, revenue, amount, memo).mapError(journalErrorToThrowable)
          _ <- Console.printLine(s"entry.id=${entry.id}")
        } yield ()
      case "transfer" :: tail =>
        for {
          parsed <- parseFlagsOrFail(tail)
          (flags, _) = parsed
          bookId <- resolveBookId(env.config.dbPath, flags)
          date = LocalDate.parse(flags.getOrElse("date", throw new IllegalArgumentException("Missing --date")))
          fromId = flags.getOrElse("from", throw new IllegalArgumentException("Missing --from"))
          toId = flags.getOrElse("to", throw new IllegalArgumentException("Missing --to"))
          amount = flags.getOrElse("amount", throw new IllegalArgumentException("Missing --amount")).toLong
          memo = flags.get("memo")
          entry <- env.quick.transfer(bookId, date, fromId, toId, amount, memo).mapError(journalErrorToThrowable)
          _ <- Console.printLine(s"entry.id=${entry.id}")
        } yield ()
      case "card-payment" :: tail =>
        for {
          parsed <- parseFlagsOrFail(tail)
          (flags, _) = parsed
          bookId <- resolveBookId(env.config.dbPath, flags)
          date = LocalDate.parse(flags.getOrElse("date", throw new IllegalArgumentException("Missing --date")))
          bank = flags.getOrElse("bank", throw new IllegalArgumentException("Missing --bank"))
          card = flags.getOrElse("card", throw new IllegalArgumentException("Missing --card"))
          amount = flags.getOrElse("amount", throw new IllegalArgumentException("Missing --amount")).toLong
          memo = flags.get("memo")
          entry <- env.quick.cardPayment(bookId, date, bank, card, amount, memo).mapError(journalErrorToThrowable)
          _ <- Console.printLine(s"entry.id=${entry.id}")
        } yield ()
      case "split-expense" :: tail =>
        for {
          parsed <- parseFlagsOrFail(tail)
          (flags, positional) = parsed
          bookId <- resolveBookId(env.config.dbPath, flags)
          date = LocalDate.parse(flags.getOrElse("date", throw new IllegalArgumentException("Missing --date")))
          payFrom = flags.getOrElse("pay-from", throw new IllegalArgumentException("Missing --pay-from"))
          memo = flags.get("memo")
          splits <- fromEitherString(parseSplits(positional))
          entry <- env.quick.splitExpense(bookId, date, payFrom, splits, memo).mapError(journalErrorToThrowable)
          _ <- Console.printLine(s"entry.id=${entry.id}")
        } yield ()
      case _ =>
        Console.printLine(
          """Usage:
            |  quick expense --date <yyyy-mm-dd> --pay-from <accountId> --expense <accountId> --amount <int> [--memo <text>] [--book <bookId>]
            |  quick split-expense --date <yyyy-mm-dd> --pay-from <accountId> [--memo <text>] [--book <bookId>] --split <accountId> <amount> [<desc>]...
            |  quick income --date <yyyy-mm-dd> --deposit-to <accountId> --revenue <accountId> --amount <int> [--memo <text>] [--book <bookId>]
            |  quick transfer --date <yyyy-mm-dd> --from <accountId> --to <accountId> --amount <int> [--memo <text>] [--book <bookId>]
            |  quick card-payment --date <yyyy-mm-dd> --bank <accountId> --card <accountId> --amount <int> [--memo <text>] [--book <bookId>]
            |""".stripMargin
        )
    }

  // ---------------------------------------------------------------------------
  // reports
  // ---------------------------------------------------------------------------
  private def reportsCmd(env: CliEnv, args: List[String]): ZIO[Any, Throwable, Unit] =
    args match {
      case "balances" :: tail =>
        for {
          parsed <- parseFlagsOrFail(tail)
          (flags, _) = parsed
          bookId <- resolveBookId(env.config.dbPath, flags)
          asOf = LocalDate.parse(flags.getOrElse("asof", throw new IllegalArgumentException("Missing --asof")))
          report <- env.reports.balanceSheet(bookId, asOf)
          _ <- Console.printLine(
            report.lines.map(l => s"${l.accountId}\t${l.accountName}\t${encodeAccountType(l.accountType)}\t${l.balance}").mkString("\n")
          )
          _ <- Console.printLine(s"totals=${report.totalsByType.map { case (k, v) => s"${encodeAccountType(k)}=$v" }.mkString(",")}")
        } yield ()
      case "pnl" :: tail =>
        for {
          parsed <- parseFlagsOrFail(tail)
          (flags, _) = parsed
          bookId <- resolveBookId(env.config.dbPath, flags)
          from = LocalDate.parse(flags.getOrElse("from", throw new IllegalArgumentException("Missing --from")))
          to = LocalDate.parse(flags.getOrElse("to", throw new IllegalArgumentException("Missing --to")))
          report <- env.reports.incomeStatement(bookId, from, to)
          _ <- Console.printLine(s"revenueTotal=${report.revenueTotal}")
          _ <- Console.printLine(s"expenseTotal=${report.expenseTotal}")
          _ <- Console.printLine(s"netIncome=${report.netIncome}")
        } yield ()
      case "ledger" :: tail =>
        for {
          parsed <- parseFlagsOrFail(tail)
          (flags, _) = parsed
          bookId <- resolveBookId(env.config.dbPath, flags)
          accountId = flags.getOrElse("account", throw new IllegalArgumentException("Missing --account"))
          from = flags.get("from").map(LocalDate.parse)
          to = flags.get("to").map(LocalDate.parse)
          report <- env.reports.ledger(bookId, accountId, from, to)
          _ <- Console.printLine(s"openingBalance=${report.openingBalance}")
          _ <- Console.printLine(report.lines.map(l => s"${l.date}\t${l.entryId}\t${l.delta}\t${l.balance}\t${l.memo.getOrElse("")}").mkString("\n"))
          _ <- Console.printLine(s"closingBalance=${report.closingBalance}")
        } yield ()
      case _ =>
        Console.printLine(
          """Usage:
            |  reports balances --asof <yyyy-mm-dd> [--book <bookId>]
            |  reports pnl --from <yyyy-mm-dd> --to <yyyy-mm-dd> [--book <bookId>]
            |  reports ledger --account <accountId> [--from <yyyy-mm-dd>] [--to <yyyy-mm-dd>] [--book <bookId>]
            |""".stripMargin
        )
    }

  // ---------------------------------------------------------------------------
  // import
  // ---------------------------------------------------------------------------
  private def importCmd(env: CliEnv, args: List[String]): ZIO[Any, Throwable, Unit] =
    args match {
      case "csv" :: tail =>
        for {
          parsed <- parseFlagsOrFail(tail)
          (flags, _) = parsed
          bookId <- resolveBookId(env.config.dbPath, flags)
          source = flags.getOrElse("source", throw new IllegalArgumentException("Missing --source"))
          file = flags.getOrElse("file", throw new IllegalArgumentException("Missing --file"))
          dateCol = flags.getOrElse("date-col", throw new IllegalArgumentException("Missing --date-col"))
          amountCol = flags.getOrElse("amount-col", throw new IllegalArgumentException("Missing --amount-col"))
          memoCol = flags.get("memo-col")
          dedupCol = flags.get("dedup-col")
          onDup = flags.get("duplicate").map(parseDuplicatePolicy).getOrElse(Right(DuplicatePolicy.Skip))
          policy <- fromEitherString(onDup)
          csv <- ZIO.attemptBlocking(Files.readString(Paths.get(file), StandardCharsets.UTF_8))
          summary <- env.imports.importCsv(bookId, source, csv, CsvMapping(dateCol, amountCol, memoCol, dedupCol), policy).mapError(importErrorToThrowable)
          _ <- Console.printLine(s"inserted=${summary.inserted} skipped=${summary.duplicatesSkipped} overwritten=${summary.duplicatesOverwritten}")
        } yield ()
      case "pending" :: tail =>
        for {
          parsed <- parseFlagsOrFail(tail)
          (flags, _) = parsed
          bookId <- resolveBookId(env.config.dbPath, flags)
          source = flags.get("source")
          pending <- env.imports.listPending(bookId, source).mapError(importErrorToThrowable)
          _ <- Console.printLine(pending.map(t => s"${t.id}\t${t.date}\t${t.amount}\t${t.payeeOrMemo.getOrElse("")}\t${t.source}").mkString("\n"))
        } yield ()
      case "ignore" :: tail =>
        for {
          parsed <- parseFlagsOrFail(tail)
          (flags, _) = parsed
          id = flags.getOrElse("id", throw new IllegalArgumentException("Missing --id"))
          _ <- env.imports.ignore(id).mapError(importErrorToThrowable)
          _ <- Console.printLine("OK")
        } yield ()
      case "confirm" :: tail =>
        for {
          parsed <- parseFlagsOrFail(tail)
          (flags, positional) = parsed
          bookId <- resolveBookId(env.config.dbPath, flags)
          importedId = flags.getOrElse("id", throw new IllegalArgumentException("Missing --id"))
          memo = flags.get("memo")
          postings <- fromEitherString(parsePostingsFromFlags(flags, positional))
          entry <- env.imports.confirmAsEntry(importedId, bookId, memo, postings).mapError(importErrorToThrowable)
          _ <- Console.printLine(s"entry.id=${entry.id}")
        } yield ()
      case _ =>
        Console.printLine(
          """Usage:
            |  import csv --source <source> --file <path> --date-col <name> --amount-col <name> [--memo-col <name>] [--dedup-col <name>] [--duplicate <skip|overwrite>] [--book <bookId>]
            |  import pending [--source <source>] [--book <bookId>]
            |  import ignore --id <importedId>
            |  import confirm --id <importedId> [--memo <text>] [--book <bookId>] --debit <accountId> <amount>... --credit <accountId> <amount>...
            |""".stripMargin
        )
    }

  // ---------------------------------------------------------------------------
  // export
  // ---------------------------------------------------------------------------
  private def exportCmd(env: CliEnv, args: List[String]): ZIO[Any, Throwable, Unit] =
    args match {
      case "json" :: tail =>
        for {
          parsed <- parseFlagsOrFail(tail)
          (flags, _) = parsed
          bookId <- resolveBookId(env.config.dbPath, flags)
          out = flags.getOrElse("out", throw new IllegalArgumentException("Missing --out"))
          path <- env.exports.exportJson(bookId, Paths.get(out))
          _ <- Console.printLine(path.toAbsolutePath.toString)
        } yield ()
      case "csv" :: tail =>
        for {
          parsed <- parseFlagsOrFail(tail)
          (flags, _) = parsed
          bookId <- resolveBookId(env.config.dbPath, flags)
          dir = flags.getOrElse("dir", throw new IllegalArgumentException("Missing --dir"))
          paths <- env.exports.exportCsvBundle(bookId, Paths.get(dir))
          _ <- Console.printLine(paths.jsonPath.toAbsolutePath.toString)
        } yield ()
      case _ =>
        Console.printLine(
          """Usage:
            |  export json --out <path> [--book <bookId>]
            |  export csv --dir <directory> [--book <bookId>]
            |""".stripMargin
        )
    }

  // ---------------------------------------------------------------------------
  // backup/restore
  // ---------------------------------------------------------------------------
  private def backupCmd(config: AppConfig, args: List[String]): ZIO[Any, Throwable, Unit] =
    for {
      parsed <- parseFlagsOrFail(args)
      (flags, _) = parsed
      out = flags.getOrElse("out", throw new IllegalArgumentException("Missing --out"))
      svc = BackupService()
      dest <- svc.backupDb(config.dbPath, Paths.get(out))
      _ <- Console.printLine(dest.toAbsolutePath.toString)
    } yield ()

  private def restoreCmd(config: AppConfig, args: List[String]): ZIO[Any, Throwable, Unit] =
    for {
      parsed <- parseFlagsOrFail(args)
      (flags, _) = parsed
      from = flags.getOrElse("from", throw new IllegalArgumentException("Missing --from"))
      svc = BackupService()
      dest <- svc.restoreDb(Paths.get(from), config.dbPath)
      _ <- Console.printLine(dest.toAbsolutePath.toString)
    } yield ()

  // ---------------------------------------------------------------------------
  // parsing helpers
  // ---------------------------------------------------------------------------
  private def parseFlags(args: List[String]): Either[String, (Map[String, String], List[String])] = {
    val flags = scala.collection.mutable.Map.empty[String, String]
    val positional = scala.collection.mutable.ListBuffer.empty[String]

    var i = 0
    while (i < args.length) {
      val a = args(i)
      if (a.startsWith("--")) {
        val eq = a.indexOf('=')
        if (eq > 0) {
          val k = a.drop(2).take(eq - 2)
          val v = a.drop(eq + 1)
          flags.update(k, v)
          i += 1
        } else {
          val k = a.drop(2)
          k match {
            case "debit" | "credit" =>
              if (i + 2 >= args.length) return Left(s"Flag --$k expects: --$k <accountId> <amount>")
              positional += a
              positional += args(i + 1)
              positional += args(i + 2)
              i += 3
            case "split" =>
              if (i + 2 >= args.length) return Left("Flag --split expects: --split <accountId> <amount> [desc]")
              positional += a
              positional += args(i + 1)
              positional += args(i + 2)
              val hasDesc = i + 3 < args.length && !args(i + 3).startsWith("--")
              if (hasDesc) {
                positional += args(i + 3)
                i += 4
              } else {
                i += 3
              }
            case _ =>
              val hasNext = i + 1 < args.length && !args(i + 1).startsWith("--")
              if (hasNext) {
                flags.update(k, args(i + 1))
                i += 2
              } else {
                flags.update(k, "true")
                i += 1
              }
          }
        }
      } else {
        positional += a
        i += 1
      }
    }
    Right((flags.toMap, positional.toList))
  }

  private def parseFlagsOrFail(args: List[String]): ZIO[Any, Throwable, (Map[String, String], List[String])] =
    ZIO.fromEither(parseFlags(args)).mapError(new IllegalArgumentException(_))

  private def fromEitherString[A](either: Either[String, A]): ZIO[Any, Throwable, A] =
    ZIO.fromEither(either).mapError(new IllegalArgumentException(_))

  private def parseAccountType(s: String): Either[String, AccountType] =
    s match {
      case "asset"     => Right(AccountType.Asset)
      case "liability" => Right(AccountType.Liability)
      case "equity"    => Right(AccountType.Equity)
      case "revenue"   => Right(AccountType.Revenue)
      case "expense"   => Right(AccountType.Expense)
      case other       => Left(s"Unknown account type: $other")
    }

  private def parseDuplicatePolicy(s: String): Either[String, DuplicatePolicy] =
    s match {
      case "skip"      => Right(DuplicatePolicy.Skip)
      case "overwrite" => Right(DuplicatePolicy.Overwrite)
      case other       => Left(s"Unknown duplicate policy: $other")
    }

  private def resolveBookId(dbPath: Path, flags: Map[String, String]): ZIO[Any, Throwable, Id] =
    flags.get("book") match {
      case Some(id) => ZIO.succeed(id)
      case None =>
        readSelectedBookId(dbPath).flatMap {
          case Some(id) => ZIO.succeed(id)
          case None     => ZIO.fail(new IllegalArgumentException("Missing --book and no selected book. Use: book select --book <id>"))
        }
    }

  private def parsePostingsFromFlags(flags: Map[String, String], positional: List[String]): Either[String, Chunk[PostingDraft]] = {
    val tokens = positional
    val postings = scala.collection.mutable.ArrayBuffer.empty[PostingDraft]

    def loop(rem: List[String]): Either[String, Unit] =
      rem match {
        case "--debit" :: accountId :: amountStr :: tail =>
          postings += PostingDraft(accountId, PostingSide.Debit, amountStr.toLong, None)
          loop(tail)
        case "--credit" :: accountId :: amountStr :: tail =>
          postings += PostingDraft(accountId, PostingSide.Credit, amountStr.toLong, None)
          loop(tail)
        case head :: tail =>
          Left(s"Unexpected posting args: ${(head :: tail).take(4).mkString(" ")}")
        case Nil =>
          Right(())
      }

    loop(tokens).map(_ => Chunk.fromIterable(postings))
  }

  private def parseSplits(positional: List[String]): Either[String, Chunk[SplitLine]] = {
    val out = scala.collection.mutable.ArrayBuffer.empty[SplitLine]
    def loop(rem: List[String]): Either[String, Unit] =
      rem match {
        case "--split" :: accountId :: amountStr :: desc :: tail if !desc.startsWith("--") =>
          out += SplitLine(accountId, amountStr.toLong, Some(desc))
          loop(tail)
        case "--split" :: accountId :: amountStr :: tail =>
          out += SplitLine(accountId, amountStr.toLong, None)
          loop(tail)
        case head :: tail =>
          Left(s"Unexpected split args: ${(head :: tail).take(4).mkString(" ")}")
        case Nil =>
          Right(())
      }
    loop(positional).map(_ => Chunk.fromIterable(out))
  }

  private def encodeAccountType(t: AccountType): String =
    t match {
      case AccountType.Asset     => "asset"
      case AccountType.Liability => "liability"
      case AccountType.Equity    => "equity"
      case AccountType.Revenue   => "revenue"
      case AccountType.Expense   => "expense"
    }

  private def encodePostingSide(s: PostingSide): String =
    s match {
      case PostingSide.Debit  => "debit"
      case PostingSide.Credit => "credit"
    }

  private def selectedBookFile(dbPath: Path): Path =
    Paths.get(dbPath.toAbsolutePath.toString + ".current-book")

  private def writeSelectedBookId(dbPath: Path, bookId: Id): ZIO[Any, Throwable, Unit] =
    ZIO.attemptBlocking {
      val path = selectedBookFile(dbPath)
      val parent = path.getParent
      if (parent != null) Files.createDirectories(parent)
      Files.writeString(path, bookId + "\n", StandardCharsets.UTF_8)
    }.unit

  private def readSelectedBookId(dbPath: Path): ZIO[Any, Throwable, Option[Id]] =
    ZIO.attemptBlocking {
      val path = selectedBookFile(dbPath)
      if (Files.exists(path)) Some(Files.readString(path, StandardCharsets.UTF_8).trim).filter(_.nonEmpty)
      else None
    }

  private def journalErrorToThrowable(e: JournalServiceError): Throwable =
    e match {
      case JournalServiceError.Validation(errors) =>
        new IllegalArgumentException(errors.map(_.message).mkString("; "))
      case JournalServiceError.Storage(cause) =>
        cause
    }

  private def importErrorToThrowable(e: ImportServiceError): Throwable =
    e match {
      case ImportServiceError.Parse(message) => new IllegalArgumentException(message)
      case ImportServiceError.NotFound(id)   => new NoSuchElementException(s"Imported transaction not found: $id")
      case ImportServiceError.InvalidState(id, status) =>
        new IllegalStateException(s"Imported transaction $id has invalid status: $status")
      case ImportServiceError.Storage(cause) => cause
      case ImportServiceError.Journal(cause) => journalErrorToThrowable(cause)
    }

  private def printHelp: ZIO[Any, Nothing, Unit] =
    Console.printLine(
      """morney (double-entry household ledger)
        |
        |Usage:
        |  sbt "run -- [--db PATH] <command>"
        |
        |Options:
        |  --db PATH       SQLite DB path (or env MORNEY_DB_PATH)
        |
        |Commands:
        |  doctor          Validate environment and DB connectivity
        |  book            Manage books (create/list/select/current)
        |  accounts        Manage accounts
        |  entry           Create/list/show journal entries
        |  quick           Quick entry helpers (expense/income/transfer/card-payment/split-expense)
        |  reports         Reports (balances/pnl/ledger)
        |  import          Import CSV and manage pending transactions
        |  export          Export JSON/CSV bundle
        |  backup          Backup SQLite DB file
        |  restore         Restore SQLite DB file
        |  help            Show this help
        |""".stripMargin
    ).orDie
}
