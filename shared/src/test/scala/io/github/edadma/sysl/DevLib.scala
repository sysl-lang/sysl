package io.github.edadma.sysl

import io.github.edadma.cross_platform.*

/** Where the useless development library sits, as seen from wherever the suite was started.
 *
 * `devlib/` is a **library project root** holding the single module `demo`, so a module's name is
 * the path below it exactly as it is for a program (`13 §1`) — which is why the root is what is
 * found here and `devlib/demo` is not.
 *
 * The candidates cover being run from the build root and from a platform's own directory. `None`
 * means the files could not be found, and a test that needs them says so and skips rather than
 * failing: the library is development scaffolding, and not every platform's test run can read it.
 */
object DevLib {

  def root: Option[String] = List("devlib", "../devlib", "../../devlib").find(isDirectory)
}
