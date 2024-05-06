package morney.domain

import morney.domain.Journal.AccountTitle

import java.time.LocalDate

final case class Journal(
    date: LocalDate,
    amount: BigDecimal,
    debit: AccountTitle,
    credit: AccountTitle,
    notes: String
)

object Journal {
  final case class AccountTitle(name: Name, group: Group)

  // FIXME: Do persistence values instead of hard coded
  enum Name:
    case DailyNecessities
    case AccruedLiability

  enum Group:
    case Assets
    case Liabilities
    case NetAssets
    case Expenses
    case Incomes

}
