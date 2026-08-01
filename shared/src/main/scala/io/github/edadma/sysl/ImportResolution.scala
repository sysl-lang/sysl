package io.github.edadma.sysl

import scala.collection.mutable

/** Turns a file's — or a block's — `import` statements into the bindings `resolveName` consults
 * (`13 §3`).
 *
 * An import never grants access: everything it names is already reachable by its full path, so
 * nothing here can make a program legal that was not. What it does is decide, once per file,
 * which shorter spellings mean what — and refuse the spellings that would mean two things.
 *
 * The work splits at hoisting. **Which module a path names is answerable straight away**, because
 * a file's header is the whole of what says which module it is in and every header is read before
 * any declaration is registered. **Whether that module declares the name** is not: the tables are
 * still being filled while the imports of the files filling them are read. So a selector's target
 * is checked in a second pass, once every declaration exists — and immediately after that, since a
 * block's imports are read while a body is being analyzed, long after.
 */
trait ImportResolution extends TraitLookup {

  /** Selectors whose module is known but whose target cannot be looked up yet: the key each one
   * stands for, the path as written, where to point if nothing answers to it, and the terms the
   * import was written in — which is what says whether what it names is visible from there.
   */
  private val importChecks = mutable.ListBuffer.empty[(String, String, Option[Pos], Scope)]

  /** Whether every declaration has been registered, and so whether an import's target can be
   * looked up as it is read rather than queued.
   */
  private var declsRegistered = false

  /** Every `import` in `stmts`, folded into the bindings they add to `base`.
   *
   * The statements are read in order, so a later import sees what an earlier one bound and can be
   * told it collides. Each is its own recovery region: a path that names nothing costs that one
   * import and leaves the rest of the file's in place.
   */
  protected def gatherImports(stmts: List[Stmt], base: Imports): Imports =
    stmts.foldLeft(base) {
      case (acc, i: ImportDecl) => at(i.pos)(recover(acc)(bind(i, acc)))
      case (acc, _)             => acc
    }

  /** One `import` written **inside a block**, which binds for the rest of that block and no
   * further (`13 §3`). It is added to the innermost open scope, so it is unwound with the block's
   * local bindings and shadows whatever the file imported under the same name.
   */
  protected def importInBlock(decl: ImportDecl): Unit =
    importHere(gatherImports(List(decl), importStack.head))

  /** Checks every queued selector against the declarations, and puts later imports on the direct
   * path. Called once, after hoisting.
   */
  protected def checkImportTargets(): Unit = {
    for (key, written, pos, scope) <- importChecks.toList do
      at(pos)(inScope(scope)(recover(())(checkDeclared(key, written))))

    importChecks.clear()
    declsRegistered = true
  }

  // --- one import ------------------------------------------------------------------------

  private def bind(decl: ImportDecl, acc: Imports): Imports = {
    val path = decl.show

    // A wildcard is over a module's members, so there has to be a module to have them. A selector
    // list may instead reach a sub-module of a directory that holds no source of its own, which is
    // a module's parent without being one — so it asks only that the path lead somewhere.
    if decl.wildcard then
      if !moduleNames(path) then err(s"no module is called '$path'")
      dependsOn(path)
      acc.copy(wildcards = acc.wildcards :+ path)
    else if decl.selectors.nonEmpty then
      if !namesModule(path) then err(s"no module is called '$path'")
      decl.selectors.foldLeft(acc)((a, s) => at(s.pos.orElse(decl.pos))(recover(a)(select(path, s, a))))
    else
      // The longest prefix that names a module wins, exactly as a qualified reference is read by
      // (`13 §3`): `import a.b` is the module `a.b` where there is one, and `a`'s member `b`
      // otherwise. That is what makes the module form and the member form one piece of syntax.
      if moduleNames(path) then
        bindModule(decl.bound, path, acc)
      else
        val module = decl.path.init.mkString(".")

        if decl.path.length == 1 || !moduleNames(module) then
          err(s"no module is called '$path', and nothing declares it")

        bindName(decl.bound, module, decl.path.last, acc)
  }

  /** One `{…}` selector, which may name a member of the module or a module beneath it. A
   * sub-module is reachable by the same path shape as a member, so listing one among the selectors
   * is the same import `import a.b.c` would have made.
   */
  private def select(module: String, sel: ImportSelector, acc: Imports): Imports =
    if moduleNames(s"$module.${sel.name}") then bindModule(sel.bound, s"$module.${sel.name}", acc)
    else bindName(sel.bound, module, sel.name, acc)

  /** A module bound to a shorter name. Binding it to the name it already answers to — `import geom`
   * where `geom` is a top-level module — asks for what is already true, so it is left alone rather
   * than reported as the collision with itself that it is.
   */
  private def bindModule(bound: String, module: String, acc: Imports): Imports = {
    dependsOn(module)

    if bound == module then acc
    else {
      checkImportName(bound, acc)
      acc.copy(modules = acc.modules + (bound -> module))
    }
  }

  /** One name brought in from a module. Importing it is already a dependency on that module
   * (`13 §6`), whether or not the file goes on to write the shorter spelling it bought — a file's
   * imports are meant to be readable as what it needs, and a dependency that came and went with a
   * use would not be.
   */
  private def bindName(bound: String, module: String, name: String, acc: Imports): Imports = {
    val key = Modules.qualify(module, name)

    dependsOn(module)
    checkImportName(bound, acc)

    if declsRegistered then checkDeclared(key, s"$module.$name")
    else importChecks += ((key, s"$module.$name", currentPos, currentScope))

    acc.copy(names = acc.names + (bound -> key))
  }

  /** What an import may not be called here.
   *
   * A name that already begins a module path is refused outright rather than resolved by a rule:
   * `modulePath` reads the head of a dotted reference as an import where it is not a module, so a
   * binding that is both would make `fs.read` mean one thing in a file that imported `fs` and
   * another in the file beside it. Refusing costs nothing — the import can be given any other
   * name with `as` — and it is what lets the two lookups be tried in either order.
   *
   * Binding one name twice is the plainer mistake, and is reported at the second import rather
   * than at whichever use first found two answers.
   */
  private def checkImportName(bound: String, acc: Imports): Unit = {
    if namesModule(bound) then
      err(s"'$bound' is a module, so importing something else under that name would hide it — " +
        s"import it as another name with 'as'")
    if acc.binds(bound) then err(s"'$bound' is already imported")
  }

  /** Whether the module named declares what the selector asked for, and whether the importing file
   * may name it (`13 §2`). Naming something deliberately is reported where it was named: being told
   * a helper is private is a more useful answer at the import than an undefined name at every use
   * of the shorter spelling it would have bound.
   */
  private def checkDeclared(key: String, written: String): Unit =
    if !declaresAnything(key) then
      err(s"'${Modules.moduleOf(key)}' declares no '${Modules.split(key)._2}' — there is no '$written'")
    else if !visible(key) then err(s"'$written' is ${restriction(key)}")

  /** Whether anything at all is declared under a key. An import binds a *name*, and which of the
   * tables answers to it is the use site's question — the same spelling may be a type in one module
   * and a function in another, and one import serves whichever the reader meant.
   */
  private def declaresAnything(key: String): Boolean =
    structDecls.contains(key) || enumDecls.contains(key) || traitDecls.contains(key) ||
      funcDecls.contains(key) || variantOwner.contains(key) || constDecls.contains(key) ||
      valDecls.contains(key) || externVarDecls.contains(key)
}
