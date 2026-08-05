package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `sysl.time` — the calendar, the two quantities, and reading either back out of text.
 *
 * The module's boundary is the thing to state first, because it is a decision rather than an
 * absence: **there are no zones here**. A wall clock reading becomes a point on the timeline only
 * once somebody says where the wall is, and answering that needs the IANA database — a table that
 * changes several times a year, which a standard library either ships and lets go stale or reads
 * from the host and thereby needs a filesystem. What is here is the half that is arithmetic, and
 * arithmetic is checkable against a calendar.
 *
 * So every expected value below is one a reader can verify without running anything: a date whose
 * weekday is known, a leap year that is and one that is not, a round trip that has to be the
 * identity. Nothing asserts a number the implementation produced.
 */
class TimeTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  /** Nothing arrives unasked-for: `sysl.time` is a submodule, so a program says so. */
  private val imp = "import sysl.time.*\n"

  "the calendar" - {
    // The epoch and its neighbours, which is where a signed day number either works or does not.
    "the epoch is day zero and the day before it is minus one" in {
      run(imp + """print(date_at(1970, 1, 1).day, date_at(1969, 12, 31).day)""") shouldBe "0 -1\n"
    }

    "a date renders as ISO 8601, before the epoch as readily as after" in {
      run(imp + """print(date_text(LocalDate(20520)), date_text(LocalDate(-1)))""") shouldBe
        "2026-03-08 1969-12-31\n"
    }

    /** The property the algorithm has, rather than any particular date it produces: `days_from_civil`
      * is its own inverse. Run over a spread that crosses the epoch, two century boundaries and a
      * 400-year one, so a sign bug or an era bug has nowhere to hide.
      */
    "civil and day-number conversion round-trips across four centuries" in {
      run(
        imp +
          """var bad = 0
            |
            |for y in 1600..2400
            |    for m in 1..12
            |        var last = days_in_month(y, m)
            |        for d in [1, last / 2, last]
            |            var c = civil_from_days(date_at(y, m, d))
            |            if c.year != y || c.month != m || c.day != d then bad = bad + 1
            |
            |print(bad)""".stripMargin) shouldBe "0\n"
    }

    // The two rules that are not "every fourth year", each with the year that proves it.
    "the century rule and the four-century exception" in {
      run(
        imp +
          """print(str(leap_year(2000)), str(leap_year(1900)), str(leap_year(2024)), str(leap_year(2023)))
            |print(days_in_month(2000, 2), days_in_month(1900, 2))""".stripMargin) shouldBe
        "true false true false\n29 28\n"
    }

    // …and the same fact reached through the calendar rather than through the predicate, which is
    // what a program actually does with it.
    "which the day numbers agree with" in {
      run(
        imp +
          """print(date_at(1900, 3, 1).day - date_at(1900, 2, 28).day)
            |print(date_at(2000, 3, 1).day - date_at(2000, 2, 28).day)""".stripMargin) shouldBe "1\n2\n"
    }

    "the epoch was a Thursday, and the day before it a Wednesday" in {
      run(
        imp +
          """print(weekday_name(weekday(LocalDate(0))), weekday_name(weekday(LocalDate(-1))))""") shouldBe
        "Thursday Wednesday\n"
    }

    // `on_or_after` is what "every Tuesday" means once a rule has a starting point, and the two
    // cases that matter are landing on the day itself and landing six days later.
    "the next given weekday on or after a date" in {
      run(
        imp +
          """print(date_text(on_or_after(date_at(2026, 3, 3), Tuesday)))
            |print(date_text(on_or_after(date_at(2026, 3, 4), Tuesday)))""".stripMargin) shouldBe
        "2026-03-03\n2026-03-10\n"
    }

    "the day of the year counts from one" in {
      run(
        imp +
          """print(day_of_year(date_at(2026, 1, 1)), day_of_year(date_at(2026, 3, 1)))
            |print(day_of_year(date_at(2024, 3, 1)), day_of_year(date_at(2026, 12, 31)))""".stripMargin) shouldBe
        "1 60\n61 365\n"
    }
  }

  "adding to a calendar" - {
    /** Adding months clamps the day where the target month is shorter, which is the choice every
      * library that offers this has to make. The 31st of January is the case that shows it, and the
      * leap year beside it is what says the clamp reads the real length rather than a constant.
      */
    "a month added to the 31st lands on the last day of a shorter one" in {
      run(
        imp +
          """print(date_text(plus_months(date_at(2026, 1, 31), 1)))
            |print(date_text(plus_months(date_at(2024, 1, 31), 1)))""".stripMargin) shouldBe
        "2026-02-28\n2024-02-29\n"
    }

    // Backwards over a year boundary, which is where a `%` that truncates toward zero would put the
    // month in the wrong year.
    "months subtract across a year boundary" in {
      run(
        imp +
          """print(date_text(plus_months(date_at(2026, 2, 15), -3)))
            |print(date_text(plus_months(date_at(2026, 2, 15), -14)))""".stripMargin) shouldBe
        "2025-11-15\n2024-12-15\n"
    }

    "a year added to a leap day clamps, and four years lands back on one" in {
      run(
        imp +
          """print(date_text(plus_years(date_at(2024, 2, 29), 1)))
            |print(date_text(plus_years(date_at(2024, 2, 29), 4)))""".stripMargin) shouldBe
        "2025-02-28\n2028-02-29\n"
    }

    /** Adding **days to a wall clock reading** is not adding a length of time to it, and this is the
      * distinction the whole module is arranged around. Seven days later is the same clock face
      * seven rows down the calendar; the timeline may have done anything in between.
      */
    "a week of calendar keeps the clock face" in {
      run(
        imp +
          """var m = datetime_at(2026, 3, 3, 9, 30, 0)
            |print(datetime_text(plus_days(m, 7)))""".stripMargin) shouldBe "2026-03-10 09:30\n"
    }
  }

  "a time of day" - {
    // Seconds appear only where there are any, and the fraction on the same terms — a renderer that
    // insists on `09:30:00` is reporting its representation rather than its value.
    "renders to the precision it actually has" in {
      run(
        imp +
          """print(time_text(time_at(9, 30, 0)), time_text(time_at(9, 30, 7)))
            |print(time_text(LocalTime(500000i64)), time_text(time_at(0, 0, 0)))""".stripMargin) shouldBe
        "09:30 09:30:07\n00:00:00.500000 00:00\n"
    }

    "the fields come back out" in {
      run(
        imp +
          """var t = time_at(23, 59, 58)
            |print(hour_of(t), minute_of(t), second_of(t))""".stripMargin) shouldBe "23 59 58\n"
    }

    // A wall clock reading as one count and back, which is the conversion a zone would sit on top
    // of — and it has to hold before the epoch, where a truncating division puts the day out by one.
    "a wall clock reading round-trips through its count, before the epoch too" in {
      run(
        imp +
          """print(datetime_text(wall_of(wall_us(datetime_at(2026, 3, 8, 2, 30, 0)))))
            |print(datetime_text(wall_of(wall_us(datetime_at(1969, 12, 31, 23, 59, 0)))))""".stripMargin) shouldBe
        "2026-03-08 02:30\n1969-12-31 23:59\n"
    }
  }

  "instants and durations" - {
    "an instant moves by a duration and back" in {
      run(
        imp +
          """var t = Instant(1000000i64)
            |print((t + hours(2i64)).us, (t + hours(2i64) - hours(2i64)).us)""".stripMargin) shouldBe
        "7201000000 1000000\n"
    }

    /** The difference of two instants is the operation the module exists for and the one that
      * **cannot** be spelled `-`: `Sub`'s result is fixed to the type on the left, and the difference
      * of two points on a timeline is not a point on it. So it is a named function, and this is what
      * pins that it stayed one.
      */
    "the difference of two instants is a duration, by name" in {
      run(
        imp +
          """var t = Instant(1000000i64)
            |print(whole_hours(since(t + hours(3i64), t)))""".stripMargin) shouldBe "3\n"
    }

    "a duration is added, negated and scaled" in {
      run(
        imp +
          """var d = hours(2i64) + minutes(30i64)
            |print(whole_hours(d), odd_minutes(d))
            |print(whole_minutes(d * 2i64), whole_minutes(-d))""".stripMargin) shouldBe "2 30\n300 -150\n"
    }

    // The parts and the remainders are different questions and the test says so: three and a half
    // hours is 210 whole minutes and 30 odd ones.
    "the whole parts and the odd ones are different questions" in {
      run(
        imp +
          """var d = hours(3i64) + minutes(30i64)
            |print(whole_minutes(d), odd_minutes(d))
            |print(whole_days(hours(50i64)), odd_hours(hours(50i64)))""".stripMargin) shouldBe
        "210 30\n2 2\n"
    }

    "instants and durations compare" in {
      run(
        imp +
          """print(str(Instant(1i64) < Instant(2i64)), str(hours(1i64) > minutes(59i64)))
            |print(str(Offset(330) == Offset(330)), str(Offset(-300) < Offset(0)))""".stripMargin) shouldBe
        "true true\ntrue true\n"
    }

    // Minutes rather than hours, because India is +05:30 — a library that assumes whole hours works
    // everywhere its author has lived.
    "an offset renders with its sign, and zero is Z" in {
      run(
        imp +
          """print(offset_text(Offset(330)), offset_text(Offset(-300)), offset_text(Offset(0)))""") shouldBe
        "+05:30 -05:00 Z\n"
    }

    /** An instant renders without a zone being supplied, which is the one rendering here that could
      * be mistaken for a conversion and is not: an offset of zero is what the count is measured
      * from. It has to be the module's, because `Display` and `Instant` both belong to the library
      * and coherence leaves no module outside it able to write the block — so a hole here would be a
      * type nothing may ever render.
      */
    "an instant renders as its own UTC reading, and through Display" in {
      run(
        imp +
          """var t = Instant(1772548200000000i64)
            |
            |print(instant_text(t))
            |print(t, Instant(0i64))
            |print(f"$t%25s|")""".stripMargin) shouldBe
        "2026-03-03 14:30 Z\n2026-03-03 14:30 Z 1970-01-01 00:00 Z\n       2026-03-03 14:30 Z|\n"
    }
  }

  "reading text back" - {
    /** The claim that makes the parsers worth having: what the module **writes** is what it
      * **reads**. Asserted as a round trip rather than against a literal, so neither direction can
      * drift without the other noticing.
      */
    "rendering and parsing are inverse, over a spread of dates" in {
      run(
        imp +
          """var bad = 0
            |
            |for n in -30000..30000
            |    var d = LocalDate(n)
            |    parse_date(date_text(d)) match
            |        Ok(back) -> if back != d then bad = bad + 1
            |        Err(_) -> bad = bad + 1
            |
            |print(bad)""".stripMargin) shouldBe "0\n"
    }

    "and for a time, at each precision it renders" in {
      run(
        imp +
          """var ts = [time_at(0, 0, 0), time_at(9, 30, 0), time_at(23, 59, 58), LocalTime(500000i64),
            |          LocalTime(1i64), LocalTime(86399999999i64)]
            |var bad = 0
            |
            |for t in ts
            |    parse_time(time_text(t)) match
            |        Ok(back) -> if back != t then bad = bad + 1
            |        Err(_) -> bad = bad + 1
            |
            |print(bad)""".stripMargin) shouldBe "0\n"
    }

    "a date-time round-trips through the space its renderer writes" in {
      run(
        imp +
          """var x = datetime_at(2026, 3, 8, 2, 30, 0)
            |parse_datetime(datetime_text(x)) match
            |    Ok(back) -> print(str(back == x))
            |    Err(e) -> print("err", str(e))""".stripMargin) shouldBe "true\n"
    }

    // …and the ISO spelling with a `T`, which the module does not write and every machine does.
    "and through the 'T' it does not write" in {
      run(
        imp +
          """parse_datetime("2026-03-08T02:30:07") match
            |    Ok(x) -> print(datetime_text(x))
            |    Err(e) -> print("err", str(e))""".stripMargin) shouldBe "2026-03-08 02:30:07\n"
    }

    "an offset round-trips, including Z" in {
      run(
        imp +
          """var os = [Offset(0), Offset(330), Offset(-300), Offset(825)]
            |var bad = 0
            |
            |for o in os
            |    parse_offset(offset_text(o)) match
            |        Ok(back) -> if back != o then bad = bad + 1
            |        Err(_) -> bad = bad + 1
            |
            |print(bad)""".stripMargin) shouldBe "0\n"
    }

    /** A fraction shorter than six digits means what it says — `.5` is half a second, not five
      * microseconds. That is the one place a parser can be subtly wrong and still look right.
      */
    "a short fraction is scaled, not padded" in {
      run(
        imp +
          """print(time_text(parse_time("00:00:00.5").unwrap()))
            |print(time_text(parse_time("00:00:00.05").unwrap()))
            |print(time_text(parse_time("00:00:00.000001").unwrap()))""".stripMargin) shouldBe
        "00:00:00.500000\n00:00:00.050000\n00:00:00.000001\n"
    }
  }

  "what a parse refuses" - {
    /** **The calendar is checked, not only the shape.** `2026-02-30` has the right digits in the
      * right places and is not a date; without this check the civil conversion accepts it silently
      * and hands back the 2nd of March, which a caller has no way to notice.
      */
    "a day the month does not have" in {
      run(
        imp +
          """print(str(parse_date("2026-02-30").unwrap_err()))
            |print(str(parse_date("2024-02-30").unwrap_err()))""".stripMargin) shouldBe
        "day is out of range\nday is out of range\n"
    }

    // …and the leap day itself is accepted in the year that has one and refused in the year that
    // does not, which is what says the check reads the real length.
    "while the leap day is a date exactly when it is one" in {
      run(
        imp +
          """print(str(parse_date("2024-02-29").is_ok()), str(parse_date("2026-02-29").is_ok()))""") shouldBe
        "true false\n"
    }

    "a month, hour, minute or second out of range" in {
      run(
        imp +
          """print(str(parse_date("2026-13-01").unwrap_err()))
            |print(str(parse_time("24:00").unwrap_err()))
            |print(str(parse_time("12:60").unwrap_err()))
            |print(str(parse_time("12:00:60").unwrap_err()))""".stripMargin) shouldBe
        "month is out of range\nhour is out of range\nminute is out of range\nsecond is out of range\n"
    }

    "a shape that is not the format" in {
      run(
        imp +
          """print(str(parse_date("2026/03/08").unwrap_err()))
            |print(str(parse_date("26-03-08").unwrap_err()))
            |print(str(parse_time("9:30").unwrap_err()))
            |print(str(parse_time("09:30:07.").unwrap_err()))""".stripMargin) shouldBe
        "not the expected shape at byte 4\nnot the expected shape at byte 2\n" +
          "not the expected shape at byte 1\nnot the expected shape at byte 9\n"
    }

    // Text left over is its own answer, because it usually means the caller meant to split
    // something off first rather than that the format was wrong.
    "text left over after a good one" in {
      run(
        imp +
          """print(str(parse_date("2026-03-08 extra").unwrap_err()))
            |print(str(parse_time("09:30 ").unwrap_err()))""".stripMargin) shouldBe
        "unexpected text at byte 10\nunexpected text at byte 5\n"
    }

    "an empty string, and one that runs out mid-field" in {
      run(
        imp +
          """print(str(parse_date("").unwrap_err()))
            |print(str(parse_date("2026-03-0").unwrap_err()))""".stripMargin) shouldBe
        "not the expected shape at byte 0\nnot the expected shape at byte 8\n"
    }

    "an offset that is neither Z nor a signed pair" in {
      // The second names byte 3, which is where the colon is missing rather than where the offset
      // began. Reading the field left to right is what a timestamp needs anyway — one of these sits
      // in the middle of a longer string — and it is the more useful of the two positions.
      run(
        imp +
          """print(str(parse_offset("05:30").unwrap_err()))
            |print(str(parse_offset("+0530").unwrap_err()))""".stripMargin) shouldBe
        "not the expected shape at byte 0\nnot the expected shape at byte 3\n"
    }
  }

  /** The fixed-offset conversions, which are the pair a zone is *not* needed for. Kept in a section
    * of their own because what makes them the library's rather than a program's is that **both
    * directions are total** — a zone whose clocks move answers the second question with none or two,
    * and an offset is a number rather than a rule with a history, so it never can.
    */
  "a fixed offset" - {
    "an instant reads against a wall set a fixed distance from UTC" in {
      run(
        imp +
          """var t = Instant(1772548200000000i64)
            |
            |print(at_offset(t, Offset(-300)))
            |print(at_offset(t, Offset(330)))
            |print(at_offset(t, Offset(0)))""".stripMargin) shouldBe
        "2026-03-03 09:30\n2026-03-03 20:00\n2026-03-03 14:30\n"
    }

    // The property rather than a number: the pair is inverse at every offset, including the two
    // that cross a day boundary in opposite directions.
    "and the two directions are inverse, at every offset a zone uses" in {
      run(
        imp +
          """var t = Instant(1772548200000000i64)
            |var bad = 0
            |
            |for m in -720..840
            |    var o = Offset(m)
            |    if from_offset(at_offset(t, o), o) != t then bad = bad + 1
            |
            |print(bad)""".stripMargin) shouldBe "0\n"
    }

    /** `timestamp_text` is written for RFC 3339 where `datetime_text` is written for people, so the
      * seconds are present at zero and the join is a `T`. Pinned because the temptation to share one
      * renderer between them is exactly what would break a consumer with a grammar.
      */
    "a timestamp renders in the machine-readable shape, seconds and all" in {
      run(
        imp +
          """var t = Instant(1772548200000000i64)
            |
            |print(timestamp_text(t, Offset(-300)))
            |print(timestamp_text(t, Offset(330)))
            |print(timestamp_text(t, Offset(0)))
            |print(timestamp_text(Instant(1772548200500000i64), Offset(-300)))""".stripMargin) shouldBe
        "2026-03-03T09:30:00-05:00\n2026-03-03T20:00:00+05:30\n2026-03-03T14:30:00Z\n" +
          "2026-03-03T09:30:00.500000-05:00\n"
    }

    "and what it writes is read back as the same instant" in {
      run(
        imp +
          """var offs = [Offset(0), Offset(-300), Offset(330), Offset(-720), Offset(840)]
            |var bad = 0
            |
            |for n in 0..2000
            |    var t = Instant(long(n) * 3719000000i64 - 1000000000000i64)
            |    for o in offs
            |        parse_timestamp(timestamp_text(t, o)) match
            |            Ok(back) -> if back != t then bad = bad + 1
            |            Err(_) -> bad = bad + 1
            |
            |print(bad)""".stripMargin) shouldBe "0\n"
    }

    /** The parser takes more shapes than the renderer writes, on purpose — what arrives was written
      * by somebody else. All four of these name the same point on the timeline.
      */
    "a parse is liberal in what it accepts, and lands on one instant" in {
      run(
        imp +
          """var t = Instant(1772548200000000i64)
            |
            |print(parse_timestamp("2026-03-03T09:30:00-05:00").unwrap_or(Instant(0i64)) == t)
            |print(parse_timestamp("2026-03-03 09:30-05:00").unwrap_or(Instant(0i64)) == t)
            |print(parse_timestamp("2026-03-03T14:30:00Z").unwrap_or(Instant(0i64)) == t)
            |print(parse_timestamp("2026-03-03T20:00:00+05:30").unwrap_or(Instant(0i64)) == t)""".stripMargin) shouldBe
        "true\ntrue\ntrue\ntrue\n"
    }

    // An offset is not optional: a timestamp with no offset names no instant, and reading one as
    // UTC would be inventing the fact the format exists to carry.
    "a timestamp with no offset is refused rather than assumed to be UTC" in {
      run(
        imp +
          """print(str(parse_timestamp("2026-03-03T09:30:00").unwrap_err()))
            |print(str(parse_timestamp("2026-03-03T09:30:00-25:00").unwrap_err()))
            |print(str(parse_timestamp("2026-03-03T09:30:00Z ").unwrap_err()))""".stripMargin) shouldBe
        "not the expected shape at byte 19\noffset hour is out of range\nunexpected text at byte 20\n"
    }
  }

  "the module's boundary" - {
    /** Nothing here is auto-imported: `sysl.time` is a submodule, so a program that does not ask for
      * it does not have it. Pinned because the whole tier argument rests on it.
      */
    "the names are not in scope unasked-for" in {
      err("print(date_at(2026, 1, 1).day)") should include("date_at")
    }

    // And the module allocates — it renders to `string` — so it is not in the `no alloc` tier. Said
    // here rather than discovered, since the auto-imported root deliberately is.
    "it is a module a 'no alloc' program may not render with" in {
      err("module m\n@no_alloc\n\nimport sysl.time.*\n\nfn() -> string = date_text(LocalDate(0))\n") should
        not be empty
    }
  }
}
