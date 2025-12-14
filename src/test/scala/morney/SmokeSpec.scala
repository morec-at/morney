package morney

import zio.test.{ZIOSpecDefault, assertTrue}

object SmokeSpec extends ZIOSpecDefault {
  override def spec =
    suite("smoke")(
      test("it runs") {
        assertTrue(1 + 1 == 2)
      }
    )
}

