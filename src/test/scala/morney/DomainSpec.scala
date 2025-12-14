package morney

import morney.domain.*
import zio.Chunk
import zio.test.{ZIOSpecDefault, assertTrue}

import java.time.{Instant, LocalDate}

object DomainSpec extends ZIOSpecDefault {
  override def spec =
    suite("domain validation")(
      test("rejects empty postings") {
        val entry = JournalEntry(
          id = "e",
          bookId = "b",
          date = LocalDate.parse("2025-12-01"),
          memo = None,
          postings = Chunk.empty,
          createdAt = Instant.EPOCH,
          updatedAt = Instant.EPOCH
        )
        val res = Validation.validateJournalEntry(entry)
        assertTrue(res.isLeft)
      },
      test("rejects unbalanced postings") {
        val entry = JournalEntry(
          id = "e",
          bookId = "b",
          date = LocalDate.parse("2025-12-01"),
          memo = None,
          postings = Chunk(
            Posting("p1", "e", "a1", PostingSide.Debit, 1000L, None),
            Posting("p2", "e", "a2", PostingSide.Credit, 900L, None)
          ),
          createdAt = Instant.EPOCH,
          updatedAt = Instant.EPOCH
        )
        val res = Validation.validateJournalEntry(entry)
        assertTrue(res.left.exists(_.exists {
          case DomainError.UnbalancedEntry(1000L, 900L) => true
          case _                                        => false
        }))
      },
      test("rejects non-positive amounts") {
        val entry = JournalEntry(
          id = "e",
          bookId = "b",
          date = LocalDate.parse("2025-12-01"),
          memo = None,
          postings = Chunk(
            Posting("p1", "e", "a1", PostingSide.Debit, 0L, None),
            Posting("p2", "e", "a2", PostingSide.Credit, 0L, None)
          ),
          createdAt = Instant.EPOCH,
          updatedAt = Instant.EPOCH
        )
        val res = Validation.validateJournalEntry(entry)
        assertTrue(res.left.exists(_.exists {
          case DomainError.InvalidAmount(_, 0L, _) => true
          case _                                  => false
        }))
      }
    )
}

