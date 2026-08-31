package sh.sysl

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** A `requires { headers { … } }` entry answered by the environment variable the package named.
 *
 * **What is being pinned is a precedence and a fallback, not a lookup.** The lookup is two lines;
 * what a reader would guess wrong is which of a flag and a variable wins, and what happens on a
 * machine where the variable is not set. Both are asserted here rather than described.
 *
 * The environment is passed in rather than read, so these run the same on a machine that happens to
 * have `PICO_SDK_PATH` set as on one that does not — a test whose result depends on the developer's
 * shell is a test that says nothing.
 */
class HeaderEnvTests extends AnyFreeSpec with Matchers {

  private val pico =
    HeaderNeed("this project", "pico_sdk", "the pico-sdk's headers", Some("PICO_SDK_PATH"))

  /** A requirement with no variable named, which is every requirement written before this existed. */
  private val plain = HeaderNeed("this project", "lwip", "lwIP's headers")

  private def env(pairs: (String, String)*): String => Option[String] = pairs.toMap.get

  "a headers requirement answered by an environment variable" - {

    "is resolved when the variable is set" in {
      envHeaders(List(pico), Set.empty, env("PICO_SDK_PATH" -> "/opt/pico-sdk")) shouldBe
        List("pico_sdk" -> "/opt/pico-sdk")
    }

    "is not resolved when it is unset, so the build stops as it did before" in {
      envHeaders(List(pico), Set.empty, env()) shouldBe empty
    }

    // `PICO_SDK_PATH=` in a shell profile is somebody clearing it, not somebody naming the root
    // directory — and an empty `-I` fails somewhere with no connection to the variable.
    "an empty variable is unset rather than a path" in {
      envHeaders(List(pico), Set.empty, env("PICO_SDK_PATH" -> "   ")) shouldBe empty
    }

    /** **The precedence, which is the part a reader would guess wrong.** An explicit flag beats an
     * inherited environment, so a one-off override is possible — and so a machine with the variable
     * set can still be told to build against a different SDK without unsetting anything.
     */
    "an explicit --include-path beats a variable that is set" in {
      envHeaders(List(pico), Set("pico_sdk"), env("PICO_SDK_PATH" -> "/opt/pico-sdk")) shouldBe empty
    }

    "a requirement naming no variable is never resolved from the environment" in {
      envHeaders(List(plain), Set.empty, env("LWIP" -> "/opt/lwip")) shouldBe empty
    }

    // Two packages may need the same headers; the flag would have been given once, so this is once.
    "one name is answered once even when two packages ask for it" in {
      val other = pico.copy(who = "github.com/sysl-lang/pico2")

      envHeaders(List(pico, other), Set.empty, env("PICO_SDK_PATH" -> "/opt/pico-sdk")) shouldBe
        List("pico_sdk" -> "/opt/pico-sdk")
    }
  }

  "the refusal a consumer is shown" - {

    /** Where the package named a variable, setting it is done **once** and answers every build in
     * that tree, while the flag is what somebody types every time. So the variable is named first,
     * and the flag is still named — a reader who has arrived here has neither.
     */
    "names the variable, and the flag as well" in {
      val e = unmetHeaders(PackageConfig(headers =
        Map("pico_sdk" -> HeaderReq("the pico-sdk's headers", Some("PICO_SDK_PATH")))), Nil, Set.empty)

      e.value should include("Set PICO_SDK_PATH")
      e.value should include("--include-path pico_sdk=<dir>")
      e.value should include("the pico-sdk's headers")
    }

    "and where none was named it is the message it always was" in {
      val e = unmetHeaders(PackageConfig(headers = Map("lwip" -> HeaderReq("lwIP's headers"))), Nil, Set.empty)

      e.value should include("--include-path lwip=<dir>")
      e.value should not include "Set "
    }

    "nothing is refused once the name is supplied, whichever answered it" in {
      unmetHeaders(PackageConfig(headers =
        Map("pico_sdk" -> HeaderReq("why", Some("PICO_SDK_PATH")))), Nil, Set("pico_sdk")) shouldBe None
    }
  }

  extension (o: Option[String]) private def value: String = o.getOrElse(fail("expected a refusal"))
}
