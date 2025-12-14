package morney.services

import morney.domain.*
import morney.storage.{DateRange, LedgerRepository}
import zio.{Chunk, Task, ZIO}

import java.time.LocalDate

final case class BalanceLine(
  accountId: Id,
  accountName: String,
  accountType: AccountType,
  balance: Long
)

final case class BalanceReport(
  asOf: LocalDate,
  lines: List[BalanceLine],
  totalsByType: Map[AccountType, Long]
)

final case class IncomeStatementReport(
  from: LocalDate,
  to: LocalDate,
  revenueLines: List[BalanceLine],
  expenseLines: List[BalanceLine],
  revenueTotal: Long,
  expenseTotal: Long,
  netIncome: Long
)

final case class LedgerLine(
  date: LocalDate,
  entryId: Id,
  memo: Option[String],
  delta: Long,
  balance: Long
)

final case class LedgerReport(
  accountId: Id,
  accountName: String,
  accountType: AccountType,
  from: Option[LocalDate],
  to: Option[LocalDate],
  openingBalance: Long,
  lines: List[LedgerLine],
  closingBalance: Long
)

final class ReportsService(repo: LedgerRepository) {
  def balanceSheet(bookId: Id, asOf: LocalDate): Task[BalanceReport] =
    for {
      accounts <- repo.listAccounts(bookId, includeInactive = true)
      entries <- repo.listJournalEntries(bookId, DateRange(from = None, to = Some(asOf)))
      balances = computeBalances(entries, accounts)
      lines = accounts
        .map(a => BalanceLine(a.id, a.name, a.`type`, balances.getOrElse(a.id, 0L)))
        .filterNot(_.balance == 0L)
      totals = lines.groupBy(_.accountType).view.mapValues(_.map(_.balance).sum).toMap
    } yield BalanceReport(asOf = asOf, lines = lines, totalsByType = totals)

  def incomeStatement(bookId: Id, from: LocalDate, to: LocalDate): Task[IncomeStatementReport] =
    for {
      accounts <- repo.listAccounts(bookId, includeInactive = true)
      entries <- repo.listJournalEntries(bookId, DateRange(from = Some(from), to = Some(to)))
      balances = computeBalances(entries, accounts)
      revenueLines = accounts
        .filter(_.`type` == AccountType.Revenue)
        .map(a => BalanceLine(a.id, a.name, a.`type`, balances.getOrElse(a.id, 0L)))
        .filterNot(_.balance == 0L)
      expenseLines = accounts
        .filter(_.`type` == AccountType.Expense)
        .map(a => BalanceLine(a.id, a.name, a.`type`, balances.getOrElse(a.id, 0L)))
        .filterNot(_.balance == 0L)
      revenueTotal = revenueLines.map(_.balance).sum
      expenseTotal = expenseLines.map(_.balance).sum
    } yield IncomeStatementReport(
      from = from,
      to = to,
      revenueLines = revenueLines,
      expenseLines = expenseLines,
      revenueTotal = revenueTotal,
      expenseTotal = expenseTotal,
      netIncome = revenueTotal - expenseTotal
    )

  def ledger(
    bookId: Id,
    accountId: Id,
    from: Option[LocalDate],
    to: Option[LocalDate]
  ): Task[LedgerReport] =
    for {
      accounts <- repo.listAccounts(bookId, includeInactive = true)
      account <- ZIO
        .fromOption(accounts.find(_.id == accountId))
        .orElseFail(new NoSuchElementException(s"Account not found: $accountId"))
      openingBalance <- computeOpeningBalance(bookId, account, from)
      entries <- repo.listJournalEntries(bookId, DateRange(from = from, to = to))
      relevant = entries.flatMap { e =>
        val deltas = e.postings.filter(_.accountId == accountId).map(p => postingDelta(account.`type`, p))
        val delta = deltas.sum
        if (delta == 0L) None else Some((e, delta))
      }
      lines = {
        var running = openingBalance
        relevant.map { case (e, delta) =>
          running += delta
          LedgerLine(date = e.date, entryId = e.id, memo = e.memo, delta = delta, balance = running)
        }
      }
      closing = lines.lastOption.map(_.balance).getOrElse(openingBalance)
    } yield LedgerReport(
      accountId = account.id,
      accountName = account.name,
      accountType = account.`type`,
      from = from,
      to = to,
      openingBalance = openingBalance,
      lines = lines,
      closingBalance = closing
    )

  private def computeOpeningBalance(bookId: Id, account: Account, from: Option[LocalDate]): Task[Long] =
    from match {
      case None => ZIO.succeed(0L)
      case Some(f) =>
        val cutoff = f.minusDays(1)
        repo
          .listJournalEntries(bookId, DateRange(from = None, to = Some(cutoff)))
          .map { entries =>
            entries.flatMap(_.postings).filter(_.accountId == account.id).map(p => postingDelta(account.`type`, p)).sum
          }
    }

  private def computeBalances(entries: List[JournalEntry], accounts: List[Account]): Map[Id, Long] = {
    val accountTypeById = accounts.map(a => a.id -> a.`type`).toMap
    val allPostings = entries.flatMap(_.postings)
    allPostings.foldLeft(Map.empty[Id, Long]) { case (acc, p) =>
      val accountType = accountTypeById.getOrElse(p.accountId, AccountType.Asset)
      val delta = postingDelta(accountType, p)
      acc.updatedWith(p.accountId) {
        case Some(existing) => Some(existing + delta)
        case None           => Some(delta)
      }
    }
  }

  private def postingDelta(accountType: AccountType, posting: Posting): Long = {
    val normalSide = normalBalanceSide(accountType)
    if (posting.side == normalSide) posting.amount else -posting.amount
  }

  private def normalBalanceSide(accountType: AccountType): PostingSide =
    accountType match {
      case AccountType.Asset     => PostingSide.Debit
      case AccountType.Expense   => PostingSide.Debit
      case AccountType.Liability => PostingSide.Credit
      case AccountType.Equity    => PostingSide.Credit
      case AccountType.Revenue   => PostingSide.Credit
    }
}

