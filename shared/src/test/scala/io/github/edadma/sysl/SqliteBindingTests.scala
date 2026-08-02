package io.github.edadma.sysl

import io.github.edadma.cross_platform.*

/** The SQLite binding in `bindings/sqlite3`, built as a library and run against the real thing.
 *
 * The claims here are the binding's own: that its two handles are types of their own, that the flag
 * and result-code shims answer what SQLite means by them, and that the two ways a text column can
 * fail to be a `string` are told apart. `BindingSupport` holds why these read the checked-in tree.
 */
class SqliteBindingTests extends BindingSupport {

  protected val binding = "sqlite3"

  /** Whether SQLite is here to be bound at all: the header `consts.c` includes, and the library the
   * `link` directive names. The two are separate packages on a Linux box — the runtime library
   * without its header is the ordinary state of a machine that has never built anything — so
   * neither implies the other and both are probed.
   */
  override protected lazy val available: Boolean = header && linkable

  private def header: Boolean = {
    val src = createTempFile("sysl-sqlite-probe-", ".c")
    val obj = createTempFile("sysl-sqlite-probe-", ".o")

    writeFile(src, "#include <sqlite3.h>\nint sysl_sqlite_probe(void) { return sqlite3_libversion_number(); }\n")
    Toolchain.compileC(src, obj).isRight
  }

  private def linkable: Boolean =
    Toolchain.compileAndRun(
      """link "sqlite3"
        |
        |extern "sqlite3_libversion_number" version() -> int
        |
        |print(version() > 0)
        |""".stripMargin) == Right((0, "true\n"))

  "the checked-in tree" - {

    "builds into an artifact carrying both its sysl and its C" in {
      guard()

      metadata should include("sqlite$open")
      metadata should include("sqlite$Db.prepare")

      carriesObject("sqlite.consts.o") shouldBe true
    }

    "opens a database from a program that says nothing about linking" in {
      // The `link` directive is written once, in the binding's header, and this program neither
      // repeats it nor passes a flag. A directive that did not survive the artifact fails here at
      // the link, naming `sqlite3_open_v2`.
      out(
        """import sqlite.*
          |
          |open(":memory:") match
          |    Ok(db) ->
          |        print("opened")
          |        db.close()
          |
          |    Err(why) -> print(f"open: ${why}")
          |""".stripMargin) shouldBe "opened\n"
    }
  }

  "a table written and read back" - {

    "keeps what was bound, in the order the SQL asked for" in {
      // Discriminating in three ways at once. The rows come out in an order nothing inserted them
      // in, so a binding that ignored `order by` fails. One name holds an apostrophe, which would
      // end the string early if a parameter were being pasted into the SQL rather than bound. And
      // the middle row rebinds only its integer, so its name is there only because `reset` kept the
      // binding — a `reset` that cleared them would give NULL, which prints as `(none)` below.
      out(
        """import sqlite.*
          |
          |go()
          |
          |go()
          |    val db = open(":memory:").expect("open")
          |
          |    db.exec("create table t (name text, n integer)").expect("create")
          |
          |    val q = db.prepare("insert into t (name, n) values (?, ?)").expect("prepare")
          |
          |    q.bind_text(1, "O'Hara").expect("bind name")
          |    q.bind_int(2, 5).expect("bind n")
          |    q.step().expect("first row")
          |    q.reset().expect("first reset")
          |
          |    q.bind_int(2, 3).expect("rebind n")
          |    q.step().expect("second row")
          |    q.reset().expect("second reset")
          |
          |    q.bind_text(1, "zeta").expect("rebind name")
          |    q.bind_int(2, 1).expect("third n")
          |    q.step().expect("third row")
          |    q.finalize()
          |
          |    val rows = db.prepare("select name, n from t order by n").expect("select")
          |
          |    var going = true
          |
          |    while going
          |        rows.step() match
          |            Err(why) ->
          |                print(f"step: ${why}")
          |                going = false
          |
          |            Ok(false) -> going = false
          |            Ok(true) ->
          |                val n = rows.int_at(1)
          |
          |                rows.text_at(0) match
          |                    Ok(Some(name)) -> print(f"${n} ${name}")
          |                    Ok(None)       -> print(f"${n} (none)")
          |                    Err(why)       -> print(f"${n} ${why}")
          |
          |    rows.finalize()
          |    db.close()
          |end go
          |""".stripMargin) shouldBe "1 zeta\n3 O'Hara\n5 O'Hara\n"
    }

    "tells SQL NULL apart from bytes that are not text" in {
      // The two failures `text_at` exists to distinguish, in one row so that neither can be reached
      // by accident. `sqlite3_column_text` signals NULL with a null pointer, and `from_cstring`
      // walks for a terminator without testing what it was given — so the first column below is a
      // read of address zero in any binding that decodes before it checks.
      //
      // The third is a byte no UTF-8 sequence may start with, stored through a cast so that SQLite
      // hands back a pointer to it rather than to nothing.
      out(
        """import sqlite.*
          |
          |go()
          |
          |go()
          |    val db = open(":memory:").expect("open")
          |
          |    db.exec("create table t (a text, b text, c text)").expect("create")
          |    db.exec("insert into t values (null, 'here', cast(x'ff' as text))").expect("insert")
          |
          |    val q = db.prepare("select a, b, c from t").expect("select")
          |
          |    q.step().expect("row")
          |
          |    show(q, 0)
          |    show(q, 1)
          |    show(q, 2)
          |
          |    q.finalize()
          |    db.close()
          |end go
          |
          |show(q: Query, col: int)
          |    q.text_at(col) match
          |        Ok(None)    -> print("null")
          |        Ok(Some(s)) -> print(f"text ${s}")
          |        Err(why)    -> print(f"error ${why}")
          |end show
          |""".stripMargin) shouldBe
        "null\ntext here\nerror a text column held bytes that are not valid UTF-8\n"
    }

    "gives the columns a statement declares, and nothing for one it does not have" in {
      // `sqlite3_column_name` answers a null pointer for an index that is not a column, which is
      // the same signal a NULL value uses — so the `None` here is the edge of the row rather than
      // an empty name, and a binding that dereferenced it would crash instead of printing.
      out(
        """import sqlite.*
          |
          |go()
          |
          |go()
          |    val db = open(":memory:").expect("open")
          |    val q  = db.prepare("select 1 as alpha, 2 as beta").expect("prepare")
          |
          |    print(q.columns())
          |    print(q.name_at(0).unwrap_or("?"))
          |    print(q.name_at(1).unwrap_or("?"))
          |    print(q.name_at(7).unwrap_or("?"))
          |
          |    q.finalize()
          |    db.close()
          |end go
          |""".stripMargin) shouldBe "2\nalpha\nbeta\n?\n"
    }

    "answers false once the rows have run out" in {
      out(
        """import sqlite.*
          |
          |go()
          |
          |go()
          |    val db = open(":memory:").expect("open")
          |
          |    db.exec("create table t (n integer)").expect("create")
          |    db.exec("insert into t values (1)").expect("insert")
          |
          |    val q = db.prepare("select n from t").expect("select")
          |
          |    print(q.step().expect("first"))
          |    print(q.step().expect("second"))
          |
          |    q.finalize()
          |    db.close()
          |end go
          |""".stripMargin) shouldBe "true\nfalse\n"
    }
  }

  "the failing paths" - {

    "report SQL that will not compile in SQLite's own words" in {
      // The message and not the number: `1` is what SQLite returns for every kind of malformed
      // statement, and a caller can act on the sentence.
      out(
        """import sqlite.*
          |
          |go()
          |
          |go()
          |    val db = open(":memory:").expect("open")
          |
          |    db.prepare("select x from nosuchtable") match
          |        Ok(_)    -> print("prepared, and should not have")
          |        Err(why) -> print(why)
          |
          |    db.close()
          |end go
          |""".stripMargin).trim should include("no such table: nosuchtable")
    }

    "refuse SQL that holds no statement, rather than answering a handle that is not one" in {
      // The sharp one, because SQLite calls it success. `sqlite3_prepare_v2` returns `SQLITE_OK`
      // for text with no statement in it and writes a **null** handle — it compiled everything
      // there was to compile. A binding that looked only at the return code would answer `Ok` with
      // a `Query` whose every method is a call through a null pointer.
      //
      // All three spellings of nothing, since a check written against the empty string alone would
      // pass the first and let the other two through.
      out(
        """import sqlite.*
          |
          |go()
          |
          |go()
          |    val db = open(":memory:").expect("open")
          |
          |    db.prepare("-- nothing here") match
          |        Ok(_)    -> print("prepared a comment")
          |        Err(why) -> print(why)
          |
          |    db.prepare("") match
          |        Ok(_)    -> print("prepared an empty string")
          |        Err(why) -> print(why)
          |
          |    db.exec("   ") match
          |        Ok(_)    -> print("ran blank SQL")
          |        Err(why) -> print(why)
          |
          |    db.close()
          |end go
          |""".stripMargin) shouldBe
        "the SQL holds no statement\nthe SQL holds no statement\nthe SQL holds no statement\n"
    }

    "report a parameter index the statement does not have" in {
      // SQLite numbers parameters from 1 and the binding keeps that numbering rather than
      // correcting it, so an index off the end is a mistake a caller can make and the message has
      // to be SQLite's own rather than a code.
      out(
        """import sqlite.*
          |
          |go()
          |
          |go()
          |    val db = open(":memory:").expect("open")
          |    val q  = db.prepare("select ?, ?").expect("prepare")
          |
          |    q.bind_int(9, 1) match
          |        Ok(_)    -> print("bound an index that is not a parameter")
          |        Err(why) -> print(why)
          |
          |    q.bind_text(9, "x") match
          |        Ok(_)    -> print("bound text at an index that is not a parameter")
          |        Err(why) -> print(why)
          |
          |    q.finalize()
          |    db.close()
          |end go
          |""".stripMargin) shouldBe "column index out of range\ncolumn index out of range\n"
    }

    "refuse a path there is no database to make, and read the message off the handle anyway" in {
      // SQLite hands back a usable connection even when the open failed — which is why `open` can
      // answer a sentence at all — and the binding closes it rather than leaking it. Nothing here
      // can observe the close directly; what it observes is that the message arrived.
      out(
        """import sqlite.*
          |
          |open("/sysl-no-such-directory/x.db") match
          |    Ok(_)    -> print("opened a database under a directory that does not exist")
          |    Err(why) -> print(why)
          |""".stripMargin).trim should include("unable to open")
    }

    "keep a read-only handle from writing, and from creating" in {
      // The one test that shows the two flag shims are different values rather than the same
      // constant twice. `open` creates the file; `open_readonly` refused the same path a moment
      // earlier and refuses to write to it a moment later.
      val path = s"${createTempDirectory("sysl-sqlite-db-")}/data.db"

      out(
        s"""import sqlite.*
           |
           |go()
           |
           |go()
           |    open_readonly("$path") match
           |        Ok(_)  -> print("read-only opened a file that is not there")
           |        Err(_) -> print("absent")
           |
           |    val db = open("$path").expect("open")
           |
           |    db.exec("create table t (n integer)").expect("create")
           |    db.close()
           |
           |    val ro = open_readonly("$path").expect("read-only open")
           |
           |    ro.exec("insert into t values (1)") match
           |        Ok(_)    -> print("wrote through a read-only handle")
           |        Err(why) -> print(why)
           |
           |    ro.close()
           |end go
           |""".stripMargin).linesIterator.toList match
        case first :: second :: Nil =>
          first shouldBe "absent"
          second should include("readonly database")
        case other => fail(s"expected two lines, got $other")
    }
  }

  "the two handles are types of their own (`15 §9`)" - {

    "so a statement cannot be passed where a connection is wanted" in {
      // The reason `opaque struct` earns its place here rather than `*u8`. Both handles are
      // pointers to storage SQLite never describes, and with no types on them this program would
      // compile and hand `sqlite3_errmsg` a `sqlite3_stmt *`.
      refused(
        """import sqlite.*
          |
          |go()
          |
          |go()
          |    val db = open(":memory:").expect("open")
          |    val q  = db.prepare("select 1").expect("prepare")
          |
          |    print(message(q.handle))
          |end go
          |""".stripMargin) should include("Stmt")
    }

    "and neither may be given a body it was never told" in {
      // `15 §9`: only `*Sqlite3` may be spoken. A value of one has no size, so there is nothing to
      // allocate and nothing to copy.
      refused(
        """import sqlite.*
          |
          |var s: Sqlite3
          |
          |print(1)
          |""".stripMargin) should include("Sqlite3")
    }
  }
}
