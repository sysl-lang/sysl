package sh.sysl

/** Where a driver run's stdout goes when no test asked to see it.
 *
 * `emit-llvm` prints a whole module and `run` prints whatever the program printed, so a suite that
 * drives the command line a few dozen times buries its own results — and the suites run in parallel,
 * so the module lands in the middle of some other suite's test names. A test that wants the output
 * captures it (`emitted`, `ran`, `diagnostics`); every other run sends it here.
 *
 * Diagnostics are deliberately *not* sent here. A run that fails still has to be able to say why.
 */
object Discarded extends java.io.OutputStream {
  override def write(b: Int): Unit = ()

  override def write(bytes: Array[Byte], off: Int, len: Int): Unit = ()
}
