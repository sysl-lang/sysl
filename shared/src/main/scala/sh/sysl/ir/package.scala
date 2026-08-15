package sh.sysl

/** **The compiler's intermediate representation, as data.**
 *
 * Codegen lowers the typed tree to the values in this package, and `Printer` writes them down as
 * LLVM's textual form. Nothing else in the compiler concatenates a type, an operand or an
 * instruction: `LType.render`, `Val.render` and `Inst.render` are the only places any of that syntax
 * is written, which is what makes the text one function over the model rather than the model's only
 * form.
 *
 * **It exists so that a back end that is not LLVM has something to read.** Until this the IR was
 * characters — `emit(s"$r = add ${ty.llvm} $a, $b")`, six hundred times over — so a second back end
 * would have had to parse the compiler's own output back into the shapes the compiler had just
 * finished deciding. That parser is the thing this package removes, and every design choice here is
 * about not putting it back one layer in: operands are values rather than names, types are an LLVM
 * ADT rather than rendered strings, and basic blocks are *captured* as the emitters build them
 * rather than reconstructed from labels and terminators by whoever consumes them.
 *
 * **What is here is LLVM's model, not sysl's.** `LType` has no `Constrained`, no `Volatile` and no
 * type parameter, because lowering is precisely the step that erases all of it — handing a consumer
 * `sh.sysl.Type` would give back the information the compiler had just discarded and leave every
 * back end to redo an erasure that had already been performed correctly once.
 *
 * There is no `phi`, and that is a fact about the lowering rather than an omission: codegen keeps
 * every local in a stack slot and reaches it with `load` and `store`, so what a consumer receives is
 * memory form and it may promote or not as it likes.
 *
 * ## Stability: pinned and bumped, with no promise before 0.1.0
 *
 * This package is **published** — it is a sibling of `sh.sysl.api` rather than part of it, because
 * `api`'s own documented claim is that it mentions no tree, and an IR is a tree. A back end can
 * therefore live outside this repository, against the released artifact, off this repository's gate.
 *
 * What it gets in exchange for that is a version to pin rather than a compatibility guarantee.
 * **Before 0.1.0 anything here may change in any release**, including the shape of a case and the
 * meaning of a field; a consumer pins a compiler version and moves when it chooses to. That is said
 * here rather than assumed, because it is the only thing a downstream can plan around.
 *
 * The one commitment that does hold, and that the whole model is checked against: **the text this
 * prints is the text the compiler has always printed.** The codegen test tier asserts on emitted IR
 * including its indentation, so a printer that drifted would be caught by several hundred assertions
 * that were written before any of this existed.
 */
package object ir
