package morney.services

import morney.domain.*
import morney.storage.LedgerRepository
import zio.{Task, ZIO}

final class AccountsService(repo: LedgerRepository) {
  def createBookWithDefaults(name: String): Task[Book] =
    for {
      book <- repo.createBook(name)
      _ <- seedDefaultAccounts(book.id)
    } yield book

  def createAccount(bookId: Id, name: String, accountType: AccountType): Task[Account] =
    repo.createAccount(bookId, name, accountType)

  def disableAccount(accountId: Id): Task[Unit] =
    repo.setAccountActive(accountId, isActive = false)

  def listAccounts(bookId: Id, includeInactive: Boolean): Task[List[Account]] =
    repo.listAccounts(bookId, includeInactive)

  private def seedDefaultAccounts(bookId: Id): Task[Unit] =
    ZIO.foreachDiscard(defaultChartOfAccounts) { a =>
      repo.createAccount(bookId, a.name, a.accountType).unit
    }

  private final case class DefaultAccount(name: String, accountType: AccountType)

  private val defaultChartOfAccounts: List[DefaultAccount] =
    List(
      DefaultAccount("Cash", AccountType.Asset),
      DefaultAccount("Bank", AccountType.Asset),
      DefaultAccount("Credit Card", AccountType.Liability),
      DefaultAccount("Salary", AccountType.Revenue),
      DefaultAccount("Food", AccountType.Expense),
      DefaultAccount("Rent", AccountType.Expense),
      DefaultAccount("Utilities", AccountType.Expense)
    )
}

