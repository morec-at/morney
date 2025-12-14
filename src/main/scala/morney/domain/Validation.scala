package morney.domain

import zio.Chunk

sealed trait DomainError extends Product with Serializable {
  def message: String
}

object DomainError {
  final case class MissingField(field: String) extends DomainError {
    override val message: String = s"Missing required field: $field"
  }

  final case class InvalidAmount(field: String, value: Long, reason: String) extends DomainError {
    override val message: String = s"Invalid amount for $field ($value): $reason"
  }

  final case class UnbalancedEntry(debitTotal: Long, creditTotal: Long) extends DomainError {
    override val message: String = s"Unbalanced entry: debit=$debitTotal credit=$creditTotal"
  }

  final case class EmptyPostings() extends DomainError {
    override val message: String = "Journal entry must have at least one posting"
  }
}

object Validation {
  def validateJournalEntry(entry: JournalEntry): Either[Chunk[DomainError], JournalEntry] = {
    val errors = Chunk.fromIterable(
      validateRequired("bookId", entry.bookId) ++
        validatePostings(entry.postings)
    )
    if (errors.isEmpty) Right(entry) else Left(errors)
  }

  private def validatePostings(postings: Chunk[Posting]): List[DomainError] =
    if (postings.isEmpty) List(DomainError.EmptyPostings())
    else {
      val postingErrors =
        postings.toList.flatMap(p => validatePosting(p))

      val debitTotal = postings.filter(_.side == PostingSide.Debit).map(_.amount).sum
      val creditTotal = postings.filter(_.side == PostingSide.Credit).map(_.amount).sum
      val balanceErrors =
        if (debitTotal == creditTotal) Nil else List(DomainError.UnbalancedEntry(debitTotal, creditTotal))

      postingErrors ++ balanceErrors
    }

  private def validatePosting(p: Posting): List[DomainError] =
    validateRequired("accountId", p.accountId) ++
      validatePositiveAmount("posting.amount", p.amount)

  private def validateRequired(field: String, value: String): List[DomainError] =
    if (value.trim.nonEmpty) Nil else List(DomainError.MissingField(field))

  private def validatePositiveAmount(field: String, value: Long): List[DomainError] =
    if (value > 0) Nil else List(DomainError.InvalidAmount(field, value, "must be > 0"))
}

