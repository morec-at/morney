package morney.domain

import edomata.core.*
import edomata.syntax.all.* // for convenient extension methods
import cats.implicits.*
import cats.data.ValidatedNec

object EdomataGettingStarted extends App {
  val d1 = Decision(1)
  println(d1)
  println(1.asDecision)
  val d2 = Decision.accept("Missile Launched!")
  println(d2)
  println("Missile Launched!".accept)
  val d3 = Decision.reject("No remained missiles to launch!")
  println(d3)
  println("No remained missiles to launch!".reject)

  println("------------------")

  val d4 = d1.map(_ * 2)
  println(d4)
  val d5 = d2 >> d1
  println(d5)
  val d6 = d5 >> d3
  println(d6)

  println("------------------")
  val d7 = for {
    i <- Decision.pure(1)
    _ <- Decision.accept("A")
    _ <- Decision.accept("B", "C")
    j <- Decision.acceptReturn(i * 2)("D", "E")
  } yield i + j
  println(d7)

  println("------------------")
  enum Event {
    case Opened
    case Deposited(amount: BigDecimal)
    case Withdrawn(amount: BigDecimal)
    case Closed
  }

  enum Rejection {
    case ExistingAccount
    case NoSuchAccount
    case InsufficientBalance
    case NotSettled
    case AlreadyClosed
    case BadRequest
  }

  enum Account {
    case New
    case Open(balance: BigDecimal)
    case Close

    def open: Decision[Rejection, Event, Open] = this
      .decide {
        case New             => Decision.accept(Event.Opened)
        case Open(_) | Close => Decision.reject(Rejection.ExistingAccount)
      }
      .validate(_.mustBeOpen)

    def close: Decision[Rejection, Event, Account] =
      this.perform(mustBeOpen.toDecision.flatMap { account =>
        if account.balance == 0
        then Event.Closed.accept
        else Decision.reject(Rejection.NotSettled)
      })

    def withdraw(amount: BigDecimal): Decision[Rejection, Event, Open] =
      this
        .perform(mustBeOpen.toDecision.flatMap { account =>
          if account.balance >= amount && amount > 0
          then Decision.accept(Event.Withdrawn(amount))
          else Decision.reject(Rejection.InsufficientBalance)
        })
        .validate(_.mustBeOpen)

    def deposit(amount: BigDecimal): Decision[Rejection, Event, Open] =
      this
        .perform(mustBeOpen.toDecision.flatMap { account =>
          if amount > 0
          then Decision.accept(Event.Deposited(amount))
          else Decision.reject(Rejection.BadRequest)
        })
        .validate(_.mustBeOpen)

    private def mustBeOpen: ValidatedNec[Rejection, Open] = this match {
      case o @ Open(_) => o.validNec
      case New         => Rejection.NoSuchAccount.invalidNec
      case Close       => Rejection.AlreadyClosed.invalidNec
    }
  }

  object Account extends DomainModel[Account, Event, Rejection] {
    override def initial: Account = New
    override def transition
        : Event => Account => ValidatedNec[Rejection, Account] = {
      case Event.Opened =>
        _ => Open(0).validNec
      case Event.Withdrawn(b) =>
        _.mustBeOpen.map(s => s.copy(balance = s.balance - b))
      case Event.Deposited(b) =>
        _.mustBeOpen.map(s => s.copy(balance = s.balance + b))
      case Event.Closed =>
        _ => Close.validNec
    }
  }

  println(Account.New.open)
  println(Account.Open(10).deposit(2))
  println(Account.Open(5).close)
  println(Account.New.open.flatMap(_.close))

  println("------------------")

  enum Command {
    case Open
    case Deposit(amount: BigDecimal)
    case Withdraw(amount: BigDecimal)
    case Close
  }

  enum Notification {
    case AccountOpened(accountId: String)
    case BalanceUpdated(accountId: String, balance: BigDecimal)
    case AccountClosed(accountId: String)
  }

  object AccountService extends Account.Service[Command, Notification] {
    import cats.Monad

    def apply[F[_]: Monad]: App[F, Unit] = App.router {
      case Command.Open =>
        for {
          _ <- App.state.decide(_.open)
          acc <- App.aggregateId
          _ <- App.publish(Notification.AccountOpened(acc))
        } yield ()
      case Command.Deposit(amount) =>
        for {
          deposited <- App.state.decide(_.deposit(amount))
          accId <- App.aggregateId
          _ <- App.publish(
            Notification.BalanceUpdated(accId, deposited.balance)
          )
        } yield ()
      case Command.Withdraw(amount) =>
        for {
          withdrawn <- App.state.decide(_.withdraw(amount))
          accId <- App.aggregateId
          _ <- App.publish(
            Notification.BalanceUpdated(accId, withdrawn.balance)
          )
        } yield ()
      case Command.Close =>
        App.state.decide(_.close).void
    }
  }

  import java.time.Instant

  val scenario1 = RequestContext(
    command = CommandMessage(
      id = "some random id for request",
      time = Instant.MIN,
      address = "our account id",
      payload = Command.Open
    ),
    state = Account.New
  )

  import cats.Id
  println(AccountService[Id].execute(scenario1))
  println(AccountService[Id].run(scenario1))
}
