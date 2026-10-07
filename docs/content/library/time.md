---
title: The time module
summary: "`sysl.time` — `Instant` and `Duration` kept apart, the proleptic Gregorian calendar, `LocalDate`/`LocalTime`/`LocalDateTime`, the ISO 8601 renderers and the parsers that read them back."
weight: 65
---

**Every declaration in `sysl.time`, with its signature:** [the generated API page](/api/sysl-time/#index). This page is the argument — what the module is for, and how its pieces fit; that one is the list.

`sysl.time` is arithmetic on dates and lengths of time. It is three files and seven types, and almost
everything worth saying about it is a **distinction the type system holds for you** — a point on the
timeline is not a length of one, and a reading on a wall clock is neither. Every library that blurs
those has the same class of bug in it, and the blur is what the types here refuse.

It requires no capability: every name below is reachable on a target with no operating system.

```sysl
import sysl.time.*

var launch = date_at(2026, 3, 8)
var meeting = datetime_at(2026, 3, 8, 9, 30, 0)

print(date_text(launch), weekday_name(weekday(launch)))
print(datetime_text(meeting))
print(day_of_year(launch), leap_year(2026))
```

```output
2026-03-08 Sunday
2026-03-08 09:30
67 false
```

## The two quantities

```sysl
// A point on the timeline, counted from 1970-01-01T00:00:00Z.
struct Instant
    us: long

// A length of timeline, with no place on it.
struct Duration
    us: long

// How far a zone's wall clock is set from UTC, in whole minutes.
struct Offset
    minutes: int
```

**Both are counts of microseconds, and that is the one representation choice worth arguing about.** A
`long` of nanoseconds runs out in 1678–2262 — close enough to today to look fine in every test and
fail on a birth date. A `long` of microseconds reaches ±292,000 years. Nothing here needs to resolve
a nanosecond, and **a range that quietly ends is a worse defect than a precision that never begins**.

`Offset` is minutes rather than hours because India is at +05:30 and Nepal at +05:45, and a library
that assumes whole hours works everywhere its author has lived.

```sysl
import sysl.time.*

var epoch = Instant(0i64)
var later = epoch + hours(50i64)

print(whole_days(since(later, epoch)), odd_hours(since(later, epoch)))
print(whole_hours(since(epoch, later)))
print(epoch < later, later == epoch + days(2i64) + hours(2i64))
print(whole_hours(-hours(3i64)), whole_hours(hours(3i64) * 4i64))
```

```output
2 2
-50
true true
-3 12
```

**Which operations exist is the whole design.** An instant may be compared with an instant,
subtracted from one, and moved by a duration. Two instants may not be added, because the sum of two
points on a timeline is not a point on it — and asking for one says so:

```sysl
import sysl.time.*

var a = Instant(0i64)
var b = Instant(1i64)

print(since(a + b, a))
```

```error
'+' between sysl.time.Instant and sysl.time.Instant needs 'sysl.Add' — it implements 'sysl.Add[sysl.time.Duration]'
```

That diagnostic is exact about what went wrong: `Instant` *does* implement `Add`, at `Duration`, and
this call asked for it at `Instant`.

### The difference of two instants is the operator

```sysl
import sysl.time.*

var a = Instant(0i64)
var b = a + hours(3i64)

print(whole_hours(b - a))
print(whole_hours(a - b))
```

```output
3
-3
```

**Two rows of `Sub` sit on `Instant`, and the type of the right operand is the whole of what selects
between them.** Subtracting a `Duration` lands further along the timeline and answers an `Instant`;
subtracting an `Instant` measures across it and answers a `Duration`. An operator trait carries its
result as `Out` as well as its operand as `Rhs`, which is what lets one type do both — the same
mechanism [`Mul` uses](/reference/expressions/) to give a vector space four products.

This section used to be this module's report against the language: the most frequently written
operation in any date-time library could not be spelled with its own operator, because `Sub`'s result
was fixed to the type on the left. That is what `Out` answered, and the gap it describes is closed.

`since(later, earlier)` is still here and is the same subtraction under a name that says which end is
which — `later - earlier` is right and `earlier - later` is just as easy to write, as the second line
above shows.

### Reading a duration back

```sysl
import sysl.time.*

var d = hours(50i64) + minutes(7i64) + seconds(30i64)

print(whole_days(d), odd_hours(d), odd_minutes(d), odd_seconds(d))
print(whole_hours(d), whole_minutes(d), whole_seconds(d))
print(seconds(90i64) == minutes(1i64) + seconds(30i64))
print(whole_minutes(days(1i64)))
```

```output
2 2 7 30
50 3007 180450
true
1440
```

**Two families, and the difference between them is the whole reason both exist.** `whole_hours` is
the duration *in* hours — fifty of them. `odd_hours` is the hours *left over* after the whole days —
two. A caller stating a length to somebody who has to check it wants the first row; a caller
formatting one wants the second, and getting them confused is how `2 days, 50 hours` gets printed.

Both truncate toward zero, so a negative duration reads as a negative count of each part rather than
borrowing across them.

The `odd_` family runs one step further than a clock face does: `odd_us` is the microseconds left
over after the whole second, which is what a caller formatting a timestamp with sub-second precision
needs and what nothing else answers.

```sysl
import sysl.time.*

var d = seconds(3i64) + millis(250i64)

print(odd_seconds(d), odd_us(d))
```

```output
3 250000
```

**`days(1)` is a length of exactly 24 hours, and it is not "a day".** A day that a zone's clocks move
through is 23 or 25 hours long, so a calendar day is not a fixed length of timeline at all. That
question belongs to the civil types below, which is why `plus_days` is theirs and not a `Duration`'s.

### Below the second

`millis` and `micros` construct, `whole_millis` and `whole_micros` read back. The representation is
microseconds, so those are the shortest lengths this module can name — and they are the two an
embedded program reaches for, since a blink loop wants 120 milliseconds and `seconds(1)` is not it.

```sysl
import sysl.time.*

var d = millis(120i64)

print(whole_millis(d), whole_micros(d), whole_seconds(d))
print(whole_micros(micros(7i64)))
```

```output
120 120000 0
7
```

All three numbers on that first row are true of one length: it is a hundred and twenty milliseconds,
and it is no seconds at all.

Scaling reads both ways round:

```sysl
import sysl.time.*

var d = seconds(3i64)

print(whole_seconds(d * 3))
print(whole_seconds(2 * d))
```

```output
9
6
```

**Each side is a block of its own**, which is worth a moment because it is what a bare literal
costs. `d * 3` reaches `impl Mul[long] for Duration`, and the `3` takes the `long` that block's
parameter names rather than the `int` a literal with nothing to take would fall back to. On the left
there is nothing to take it *from* — `2` is an `int` before the operator is looked at — so the module
writes a second block, over the whole integer family at once:

```sysl
impl[N: Integer] Mul[Duration, Duration] for N
    mul(self, d: Duration) -> Duration = d * long(self)
```

**`Integer` may be a blanket subject where an ordinary trait may not**, and the reason is that its
membership is the compiler's to settle: every `iN` and `uN` is in it — an
[open family](/reference/types/#integers-are-an-open-family) of arbitrary widths — and nothing a
program declares can join. A blanket over an ordinary trait would be a blanket over every type at
once, which is refused.

So a count of any width scales a duration, not only the two an `int` and a `long` reach:

```sysl
import sysl.time.*

var d = seconds(3i64)
var w: u8 = 4

print(whole_seconds(w * d))
```

```output
12
```

### Writing a duration number-first

Every unit is also a property on any integer, so a length can be written the way a datasheet writes
one — the number, then the unit:

| property | the constructor it mirrors |
|---|---|
| `5.us` | `micros(5)` |
| `5.ms` | `millis(5)` |
| `5.s` | `seconds(5)` |
| `5.minutes` | `minutes(5)` |
| `5.hours` | `hours(5)` |
| `5.days` | `days(5)` |

```sysl
import sysl.time.*

print(whole_millis(250.ms), whole_micros(250.ms))
print(whole_hours(2.hours + 90.minutes), odd_minutes(2.hours + 90.minutes))
print(whole_millis(250.ms * 4))
```

```output
250 250000
3 30
1000
```

**The short units are symbols and the long ones are words**, which is a split about where each is
used rather than a compromise. A timeout, a poll interval and a debounce are the sub-second end, they
are written constantly, and `ms` is the spelling every datasheet already uses —
[`sleep(5.ms)`](#waiting) and
`join(ssid, pw, auth, 20.s)` say at a glance what `millis(5)` and `seconds(20)` say a moment later.
At the other end the number is small and the line is not dense, so `30.days` costs nothing and says
more than `30.d` would.

There is deliberately no `5.min`: it would sit beside `sysl.math`'s `min(a, b)` and the type-level
`int::Min`, meaning something different in each position.

**The constructors stay, and the two spellings are not a duplication.** A duration built from a
*computed* value reads better as `millis(n)` than as `n.millis`, and the free functions are what most
code that takes a length as an argument is written against.

The properties are one blanket implementation over the whole integer family, so a receiver narrower
than the representation widens on the way in rather than overflowing at its own width:

```sysl
import sysl.time.*

val n: u8 = 200

print(whole_micros(n.ms))
```

```output
200000
```

**A selective import has to name the trait**, which is the one place `DurationUnits` is written down
by anybody using it — a member is reached through the trait that declares it, so importing `millis`
does not bring `ms` along with it:

```sysl
import sysl.time.{Duration, DurationUnits, whole_micros}
```

`import sysl.time.*` needs nothing said.

There is no matching set for `Instant`. A point on the timeline has no natural `5.<unit>` — five of
what, from when? — so naming one still means saying which epoch it is counted from.

## The calendar

```sysl
// Proleptic Gregorian — the Gregorian rules run backwards through the years before anybody
// agreed to them.
struct Civil
    year: int
    month: int
    day: int

// Days since 1970-01-01, signed.
struct LocalDate
    day: int

// Microseconds since midnight.
struct LocalTime
    us: long

struct LocalDateTime
    date: LocalDate
    time: LocalTime
```

**A day number, not packed civil fields.** Year, month, day, hour and minute as bit fields inside one
word is the design most people reach for, and the arithmetic says no: packing to the nanosecond needs
30 bits for the nanosecond, 17 for the time of day and 9 for the day and month, which is 56 — leaving
8 bits of year, a range of ±128 years, which is not a calendar. A *count* fits more comfortably at
the same width and costs nothing to compute with, because comparing, subtracting and adding a length
of time are one instruction each on a count and an unpack-recompute-repack on packed fields. Only
"what month is it" wants the fields, and that is the rare question — paid for where it is asked.

**Proleptic Gregorian is a decision and not an oversight.** The alternative is a calendar with a
ten-day hole in it whose position depends on which country you ask.

```sysl
import sysl.time.*

var d = date_at(2026, 3, 8)

print(year_of(d), month_of(d), day_of(d), month_name(month_of(d)))
print(day_of_year(d), weekday_name(weekday(d)))
print(days_in_month(2026, 2), days_in_month(2024, 2))
print(leap_year(2000), leap_year(1900), leap_year(2024))
print(date_at(1970, 1, 1).day, date_at(1969, 12, 31).day)
print(weekday_name(weekday(date_at(1970, 1, 1))))
```

```output
2026 3 8 March
67 Sunday
28 29
true false true
0 -1
Thursday
```

**Dates before the epoch are ordinary values rather than a special case.** The day number is signed
and [Hinnant's civil-from-days algorithm](https://howardhinnant.github.io/date_algorithms.html) never
branches on it — it shifts the epoch to the start of a 400-year era, after which every quantity is
non-negative and each leap rule becomes one exact division. `days_from_civil` and `civil_from_days`
are inverse over the whole `int` range, which is the property worth asserting rather than any
particular date.

The weekday falls out of the same count: 1970-01-01 was a Thursday, so shifting by four puts Sunday
at zero. Nothing here needs to know which day a week starts on.

### A date the calendar does not have

`date_at` is `days_from_civil` under another name, so it answers for any three numbers at all. The
thirtieth of February is a day count like any other and comes back as the second of March:

```sysl
import sysl.time.*

print(date_text(date_at(2026, 2, 30)))
print(checked_date(2026, 2, 30).is_none(), checked_date(2026, 2, 28).is_some())

val text = checked_date(2026, 2, 29) match
    Some(d) -> date_text(d)
    None -> "no such date"

print(text)
```

```output
2026-03-02
true true
no such date
```

**Two constructors rather than one that checks.** A calendar walk — `plus_months`, a loop over the
days of a month, `on_or_after` — has already established that its dates are real, and a range test
per step would be paid for by every one of them to catch nothing. `date_at` is that walk's
arithmetic and stays exactly as it is.

What asks the other question is a **reader**: text names three numbers that a person wrote, and a
format saying a date must exist has to be able to refuse them. `parse_date` already answers
`Err(OutOfRange(…))`, so the checking was there; what was missing was reaching it without going
through text, which a reader that has already scanned the digits itself has no way to do.

The year is unbounded on purpose. The proleptic Gregorian calendar has no first or last one, and
`days_from_civil` is its own inverse over the whole `int` range — so `checked_date` checks the month
and the day and nothing else.

### Adding to a calendar

```sysl
import sysl.time.*

print(date_text(plus_months(date_at(2026, 1, 31), 1)))
print(date_text(plus_months(date_at(2024, 1, 31), 1)))
print(date_text(plus_years(date_at(2024, 2, 29), 1)))
print(date_text(on_or_after(date_at(2026, 3, 8), Tuesday)))
print(datetime_text(plus_days(datetime_at(2026, 3, 8, 9, 30, 0), 7)))
```

```output
2026-02-28
2024-02-29
2025-02-28
2026-03-10
2026-03-15 09:30
```

**Months are added by the calendar rather than by counting days, and the day is clamped where the
target month is shorter.** The 31st of January plus one month is the 28th or the 29th of February,
whichever that year has. Every library that offers this makes the choice, and clamping is the one
that keeps "the last day of the month" landing on a last day. `plus_years` is `plus_months` by
twelve, which is what makes the 29th of February behave on a non-leap year without a rule of its own.

**`plus_days` on a wall clock reading is not adding a length of time to it.** Seven days later is the
same clock face seven rows down the calendar, whatever the timeline did in between; across a change
of a zone's clocks the two answers differ by an hour. Both are right — they answer different
questions, which is why they are spelled differently. And why this is refused:

```sysl
import sysl.time.*

var d = date_at(2026, 3, 8)

print(date_text(d + days(1i64)))
```

```error
'+' needs matching types, got sysl.time.LocalDate and sysl.time.Duration
```

`on_or_after` is the first date on or after a given one falling on a given weekday, which is what
"every Tuesday" means once a rule has been given a starting point.

### Reading a time of day apart

A `LocalTime` is one number of microseconds since midnight, and the four `*_of` functions read its
parts out of it — which is what a renderer, a comparison against a schedule, and anything printing a
clock face all want:

```sysl
import sysl.time.*

var t = time_at(13, 45, 30)

print(hour_of(t), minute_of(t), second_of(t), us_of(t))
```

```output
13 45 30 0
```

They are the `odd_` family's counterpart on the civil side, and they truncate nothing: a time of day
is already within one day, so each part is simply read. `us_of` is the sub-second remainder, and it
is zero here because `time_at` names whole seconds — a time parsed from `13:45:30.25` carries one.

### Rendering

```sysl
import sysl.time.*

var t = time_at(9, 30, 7)

print(date_at(2026, 3, 8))
print(time_text(t), time_text(time_at(9, 30, 0)), time_text(LocalTime(500000i64)))
print(datetime_at(2026, 3, 8, 9, 30, 0))
print(offset_text(Offset(330)), offset_text(Offset(-480)), offset_text(Offset(0)))
```

```output
2026-03-08
09:30:07 09:30 00:00:00.500000
2026-03-08 09:30
+05:30 -08:00 Z
```

All five types implement [`Display`](/library/core/), so a value goes straight into a `print` or an
interpolation; the `*_text` functions are the same renderers reached by name, for a caller that wants
the string.

**Seconds appear only when there are any.** A meeting at half past nine is written `09:30` by every
human being who has ever written one down, and a renderer insisting on `09:30:00` is reporting its
representation rather than its value. The microseconds appear on the same terms — and a renderer that
showed a value to the second while dropping what is below it would be reporting something the value
does not say.

`Z` for a zero offset is a spelling rather than a special case: ISO 8601 writes it that way, and
`parse_offset` reads it back as zero.

## Reading text back

```sysl
// Why a parse refused, at the granularity a caller can act on.
enum TimeParseError
    BadShape(at: usize)
    OutOfRange(what: string)
    Trailing(at: usize)
```

```sysl
import sysl.time.*

parse_date("2026-03-08") match
    Ok(d) -> print(d)
    Err(e) -> print("refused:", e)

parse_time("09:30") match
    Ok(t) -> print(t)
    Err(e) -> print("refused:", e)

parse_time("09:30:07.5") match
    Ok(t) -> print(t)
    Err(e) -> print("refused:", e)

parse_datetime("2026-03-08T09:30") match
    Ok(x) -> print(x)
    Err(e) -> print("refused:", e)

parse_datetime("2026-03-08 09:30:07") match
    Ok(x) -> print(x)
    Err(e) -> print("refused:", e)

parse_offset("+05:30") match
    Ok(o) -> print(o)
    Err(e) -> print("refused:", e)
```

```output
2026-03-08
09:30
09:30:07.500000
2026-03-08 09:30
2026-03-08 09:30:07
+05:30
```

**One format, and it is ISO 8601.** Not a pattern language, not a locale, not a list of alternatives
tried in turn — `YYYY-MM-DD`, `HH:MM[:SS[.ffffff]]`, and the two joined by a space or a `T`. That is
the format the renderers above produce, so **the pair round-trips**, and it is the format every
machine-readable timestamp in the world is already written in. A pattern language is a bigger feature
than this module and belongs above it.

Both join characters are read because both are written: ISO 8601 says `T`, and `datetime_text` writes
a space because that is what a person reads.

A fraction of fewer than six digits means what it says — `.5` is half a second, not five
microseconds.

### What a parse refuses

```sysl
import sysl.time.*

parse_date("2026-02-30") match
    Ok(d) -> print(d)
    Err(e) -> print("refused:", e)

parse_date("2026-13-01") match
    Ok(d) -> print(d)
    Err(e) -> print("refused:", e)

parse_date("2026/03/08") match
    Ok(d) -> print(d)
    Err(e) -> print("refused:", e)

parse_date("2026-03-08T") match
    Ok(d) -> print(d)
    Err(e) -> print("refused:", e)

parse_time("24:00") match
    Ok(t) -> print(t)
    Err(e) -> print("refused:", e)
```

```output
refused: day is out of range
refused: month is out of range
refused: not the expected shape at byte 4
refused: unexpected text at byte 10
refused: hour is out of range
```

**A parse checks the calendar, not only the shape**, and the first line is the whole reason to say
so. `2026-02-30` is four digits, a dash, two digits, a dash and two digits — it is exactly the right
shape and it is not a date. Without holding the day against the month's real length, the civil
conversion accepts it silently and hands back the 2nd of March, which is the failure mode a caller
has no way to notice.

The three cases are separated by **what a caller would do about them**. A shape error is a message
about the format; a range error is a message about the value; a trailing error usually means the text
held something more that the caller meant to split off first. `BadShape` and `Trailing` carry a byte
offset for the reason [`sysl.text`](/library/text/)'s `BadDigit` carries one: a message naming where
is worth writing and cannot be reconstructed afterwards.

## A fixed offset, which is not a zone

```sysl
import sysl.time.*

var t = Instant(1772548200000000i64)

print(at_offset(t, Offset(-300)))
print(at_offset(t, Offset(330)))
print(from_offset(at_offset(t, Offset(-300)), Offset(-300)) == t)
print(timestamp_text(t, Offset(-300)))
print(timestamp_text(t, Offset(0)))
print(timestamp_text(Instant(1772548200500000i64), Offset(-300)))
```

```output
2026-03-03 09:30
2026-03-03 20:00
true
2026-03-03T09:30:00-05:00
2026-03-03T14:30:00Z
2026-03-03T09:30:00.500000-05:00
```

**Both directions are total, and that is the whole difference between an offset and a zone.** An
offset is a number; a zone is a rule with a history. Ask a zone what instant a wall clock reading
names and the honest answers are *one*, *none* and *two* — the hour a spring-forward deletes has no
instant in it, and the hour an autumn-back repeats has two. Ask an offset and there is exactly one,
always, which is why this pair is a pair of plain functions and a zone conversion could never be.

**Most programs that handle timestamps need only this.** A timestamp on a wire, in a log line or in
a database column already carries the offset that was in force when it was written: the sender
resolved the zone, and what arrived is the record of that decision. Reading one back needs no table,
no update cadence and no filesystem — which is exactly what makes it the library's business, where
the zone is not.

### The renderer is written for machines, and it is the odd one out

`timestamp_text` breaks two of this page's own rules, on purpose. The seconds are present even at
zero, where [`datetime_text` drops them](#rendering); the date and time are joined by a `T` rather
than a space; and the offset is flush against the time.

That is **RFC 3339**, the profile of ISO 8601 that JSON APIs, logs and databases actually agree on,
and it *requires* the seconds. The rule that a renderer should not report what a value does not say
is the right rule for a person glancing at a meeting time and exactly the wrong one for a parser
with a grammar — so the two renderers are two renderers. The fraction is still omitted when it is
zero, because RFC 3339 makes that part optional and `.000000` on every timestamp is six characters
of noise on the wire.

```sysl
import sysl.time.*

var t = Instant(1772548200000000i64)

print(parse_timestamp("2026-03-03T09:30:00-05:00").unwrap_or(Instant(0i64)) == t)
print(parse_timestamp("2026-03-03 09:30-05:00").unwrap_or(Instant(0i64)) == t)
print(parse_timestamp("2026-03-03T14:30:00Z").unwrap_or(Instant(0i64)) == t)
print(parse_timestamp("2026-03-03T20:00:00+05:30").unwrap_or(Instant(0i64)) == t)
```

```output
true
true
true
true
```

**The parser reads more shapes than the renderer writes.** It takes the space as well as the `T`,
and a time with no seconds as well as one with them, because what arrives was written by somebody
else — while the renderer emits one form, because a wire format with options is a wire format
everybody implements differently. Liberal in what it accepts, strict in what it sends.

All four of those lines name the same point on the timeline, which is the point: the offset is not
decoration on the reading, it is what turns the reading into an instant.

### An offset is not optional

```sysl
import sysl.time.*

print(str(parse_timestamp("2026-03-03T09:30:00").unwrap_err()))
print(str(parse_timestamp("2026-03-03T09:30:00-25:00").unwrap_err()))
print(str(parse_timestamp("2026-03-03T09:30:00Z ").unwrap_err()))
```

```output
not the expected shape at byte 19
offset hour is out of range
unexpected text at byte 20
```

A date and a time with no offset name a **wall clock reading**, not an instant, and
`parse_datetime` is what reads one of those. Defaulting the missing offset to UTC would be inventing
the fact the format exists to carry, and it is the single most common way a timestamp ends up hours
wrong in a system that never notices.

## A zone, which is a rule rather than a number

An offset is a number; a **zone** is a rule with a history. The difference shows up in exactly one
place — converting a wall clock reading *into* an instant. Against a fixed offset that conversion is
total, which is why `from_offset` above is an ordinary function. Against a zone whose clocks move it
is not: the hour a zone skips never happened, and the hour it repeats happened twice.

`resolve` is what says which of the three occurred, and the return type is the point of it:

```sysl
import sysl.time.*

// US Eastern in 2023: -04:00 between the two moments its clocks moved, -05:00 outside them. The
// transitions are named in UTC because that is the only reading of them that is not itself
// ambiguous.
eastern(t: Instant) -> Offset =
    if t.us >= 1678604400000000 && t.us < 1699164000000000 then Offset(-240) else Offset(-300)

print(resolve(datetime_at(2023, 6, 15, 12, 0, 0), eastern))
print(resolve(datetime_at(2023, 11, 5, 1, 30, 0), eastern))
print(resolve(datetime_at(2023, 3, 12, 2, 30, 0), eastern))
```

```output
2023-06-15 16:00 Z
2023-11-05 05:30 Z or 2023-11-05 06:30 Z
no such time: the clocks went from -05:00 to -04:00
```

**The zone arrives as a function rather than as a type**, and that is what keeps this in
capability-free `sysl.time`: the whole of what resolution needs to know about a zone is what its
offset was at a given instant. A host reading its own zone, a package talking to an RTC chip and a
table written out by hand can all answer that one question, and none of them has to be a type this
module knows about.

A function is anything callable, so a **closure** answers as well as a declaration does — which is
what a zone that is one fixed offset, or one read out of a table the closure captured, is written as:

```sysl
import sysl.time.{Instant, Offset, datetime_at, resolve}

val mins = -300

print(resolve(datetime_at(2023, 11, 5, 1, 30, 0), t -> Offset(mins)))
```

```output
2023-11-05 06:30 Z
```

A zone whose offset never varies has no transition to fall either side of, so the reading is
`Unique` and prints as the one instant it names.

`Gap` carries the offsets either side of the transition rather than an instant, because there is no
instant to carry. What to do about a reading that never happened is a policy question — push it
forward by the size of the gap, clamp it to the transition, or refuse it — and `from_offset(ldt,
before)` is the first of those, which is the one most libraries choose.

**Nothing above holds a zone database, and `resolve` is not one.** It is the arithmetic that a zone
database would be consulted *by*.

## Reading a clock — `sysl.posix.time`

Everything above is arithmetic, and arithmetic needs a value to start from. Obtaining one is asking
the environment what time it is, so it is a **module of its own** — the same split
[`sysl.rand`](/library/rand/) and `sysl.posix.rand` make, and for the same mechanical reason: a
capability requirement is module-wide, so a `now()` written beside `Instant` would take the whole
calendar away from every freestanding program that only wanted to add two durations.

There are two clocks, and the return types are the distinction rather than a detail.

```sysl
import sysl.posix.time.{now, monotonic}

// The wall clock: a point on the timeline, comparable with one taken on another machine.
print(now().us > 1577836800000000)

// The monotonic clock: a length of time from an origin nobody specifies.
var t0 = monotonic()
var n: long = 1

for i in 0..<200000
    n = n * 31 + long(i)

var took = monotonic() - t0

print(took.us > 0)
print(n != 0)
```

```output
true
true
true
```

`now` answers an **`Instant`** — a point counted from 1970, which is the one to stamp something with.
It is also the clock a person and an `ntpd` are both allowed to *set*, so two readings of it can
differ by anything at all, including a negative amount.

`monotonic` answers a **`Duration`**, not an `Instant`, and that is the whole of the design. It is
counted from an origin the module deliberately does not name — boot, usually — so a single reading
means nothing and only the difference of two does. It cannot be set and never goes backwards, which
is what makes it the one to measure with. Giving it a type that no calendar function accepts is what
stops a measurement being mistaken for a timestamp:

```sysl
import sysl.posix.time.monotonic
import sysl.time.instant_text

print(instant_text(monotonic()))
```

```error
error: 't' of 'sysl.time.instant_text' is sysl.time.Instant, but sysl.time.Duration was given
```

Both require `posix`.

### Waiting

Asking to be put to sleep is asking the operating system for something, so `sleep` is here for the
same reason `now` is rather than beside `Duration`. It takes a `Duration`, which is what makes the
line read the same on a host and on a board — `sysl-lang/pico2` has a `sleep` of its own over the
SDK's timer, with this signature.

```sysl
import sysl.posix.time.{monotonic, sleep}
import sysl.time.*

val t0 = monotonic()

sleep(50.ms)

print(whole_millis(monotonic() - t0) >= 50)
```

```output
true
```

**A signal cuts a wait short, and `sleep` carries on.** The system call it is built on returns early
when anything arrives, handing back the time still owed — so a wait written as one call is quietly
shorter than it asked for whenever a program has a handler installed, which is exactly when a test
that depends on it starts looking flaky. `sleep` retries with the remainder until nothing is left,
and answers nothing, because by the time it returns there is nothing left to say.

A duration of zero or less returns at once. That is not the same as yielding: `sleep(0.s)` is not a
way to offer the processor to something else.

`nanosleep` is the other half, and it is two differences rather than one. It counts **nanoseconds**,
where a `Duration` counts microseconds and so cannot name a wait shorter than one; and it **answers
what was still owed**, which is what lets a program with a handler tell "the wait finished" from
"something happened". Zero means the whole time passed.

```sysl
import sysl.posix.time.{monotonic, nanosleep}
import sysl.time.*

val t0 = monotonic()

var left = nanosleep(2_000_000)

while left > 0
    left = nanosleep(left)

print(whole_micros(monotonic() - t0) >= 2000)
```

```output
true
```

Reach for `sleep` unless the resolution or the remainder is the point.

### The zone the host is set to

The host already knows its own zone and keeps it up to date, so a program does not have to write an
offset into its source and does not go wrong when the clocks move.

```sysl
import sysl.posix.time.{now, local, local_text, local_offset, from_local}
import sysl.time.{timestamp_text, parse_timestamp, Resolution}

val t = now()

print(local_text(t) == timestamp_text(t, local_offset(t)))
print(parse_timestamp(local_text(t)).unwrap() == t)

// A reading taken *from* an instant is one the clocks did show, so it is never a gap -- but it may
// still be one of the two readings of a repeated hour.
val found = from_local(local(t)) match
    Unique(at) -> at == t
    Ambiguous(earlier, later) -> earlier == t || later == t
    Gap(before, after) -> false

print(found)
```

```output
true
true
true
```

`local_offset` takes an **instant** rather than answering "the current offset", because those are
different questions wherever a zone moves its clocks — and the one a caller almost always wants is
the offset that applied to *the timestamp being rendered*, not the one that applies now. It is
answered from the host's own data, so a date from a year whose rules differed is answered correctly:
`2005-11-01` is `-05:00` in New York under the rules of the day, where today's rules would say
`-04:00`, and Kathmandu is `+05:30` before 1986 and `+05:45` after.

`from_local` is the reverse and answers a `Resolution`, for the reason the section above gives.
**The host will not tell you which of the three happened** — `mktime` answers the second occurrence
of a repeated reading and says nothing, and for one that never happened it rewrites its caller's
fields and answers a time nobody asked for — so the decision is made in sysl out of the offset
lookup rather than handed to the C library.

### The portable spelling — `sysl.time.now`

**A board's clock is a board's decision**: two boards carrying the same chip — so the same *target* —
can count time from a different RTC, which is precisely the case a compile-time switch on the target
cannot express. So the choice is made at the **link** instead. `sysl.time` declares two symbols and
defines neither:

```sysl
import sysl.time.{now, monotonic, since, whole_micros, Instant}

val y2020 = Instant(1577836800000000)

print(whole_micros(since(now(), y2020)) > 0, whole_micros(monotonic()) >= 0)
```

```output
true true
```

That program names no capability, no chip and no package — and `sysl.time` still asks for nothing, so
the calendar goes on running on a bare machine. What supplies the numbers is whatever it is linked
against: `sysl.posix.time` above, on a host, and a package binding an RTC over I²C on a board. Moving
the same source between the two is a change to `package.hocon` rather than to any line of it.

The mechanism is the general one — [a module may supply another module's
`extern`](/reference/ffi/#a-module-may-supply-another-module-s-extern) — and a supplier is an ordinary
`@export` of `sysl_wall_us` or `sysl_monotonic_us` answering a `long` of microseconds.

**Both spellings are right and neither is deprecated.** A program that is only ever going to run on a
host imports `sysl.posix.time` and calls `now` directly, which needs no linker cooperation at all; a
program that wants to move imports `sysl.time` instead. What used to be written here — that no such
shape existed and a program therefore could not compile unchanged against both — is what this
replaces.

**Overriding the clock is something a board does and not something a host does.** On a hosted target
the standard library arrives prebuilt and was compiled whole, so its own supplier is already bound and
a package cannot displace it. On a board there is no such artifact — the library is compiled from
source into the program — and the package's definition is the one that answers.

## A zone by name

`America/New_York` rather than the host's own. There is no portable C call for it — `tzalloc`,
`localtime_rz` and `mktime_z` are a NetBSD extension that neither macOS nor glibc carries, and the
remaining route is setting `TZ` in the environment and calling `tzset`, which mutates process-wide
state. So the database is read instead, and **the reading splits in two**:

| half | what it is | capability |
|---|---|---|
| [`sysl.time.tzif`](#decoding-a-zone) | decoding a TZif file (RFC 8536) | **none** |
| `sysl.posix.time.zone_data` | fetching the bytes off the host | `os` |

**The split is what makes a zone reachable on a bare machine.** A board with a real-time clock carries
one zone's bytes in flash — the zone it was deployed in — and resolves local time with no filesystem
anywhere. Weld the decoder to the file reading and a freestanding target gets none of it.

```sysl
import sysl.posix.time.zone_data
import sysl.time.tzif.{parse, offset_at, abbrev_at, is_dst_at}
import sysl.time.{Instant, Offset, datetime_at, resolve, Resolution}
import sysl.time.tzif.Zone

// Module storage, so that the function below is a top-level one rather than nested in this file's
// body — only a top-level function may be passed as a callable.
static val bytes: []u8 = zone_data("America/Toronto").unwrap()
static val toronto: Zone = parse(bytes[..]).unwrap()

toronto_offset(t: Instant) -> Offset = offset_at(toronto, t).unwrap()

val summer = Instant(1686844800000000)

print(offset_at(toronto, summer).unwrap(), abbrev_at(toronto, summer).unwrap(), is_dst_at(toronto, summer).unwrap())
print(resolve(datetime_at(2023, 11, 5, 1, 30, 0), toronto_offset))
print(resolve(datetime_at(2023, 3, 12, 2, 30, 0), toronto_offset))
```

```output
-04:00 EDT true
2023-11-05 05:30 Z or 2023-11-05 06:30 Z
no such time: the clocks went from -05:00 to -04:00
```

The name is a path under the database root, which is why a region-qualified one has a `/` in it.
`zone_data` looks under **`TZDIR`** where that is set and `/usr/share/zoneinfo` where it is not — the
convention every other reader of the database follows, consulted through
[`sysl.env`](/library/env/). `zone_data_in` takes a root outright, and `local_zone_data` reads
`/etc/localtime`, which is this host's own zone as a file.

### Decoding a zone

**Nothing is copied and nothing is allocated.** A `Zone` is a handful of offsets *into the caller's
bytes*, and every lookup reads the packed arrays where they lie — so the storage is the file the
caller already holds, and a target with no allocator can use one. The cost is the ordinary borrow:
**the bytes must outlive the `Zone`**, which is why they are a named binding above rather than a
temporary.

**Leap seconds are skipped**, because POSIX time ignores them by definition and `Instant` counts the
same way — a table of them describes a timeline this library does not have. The version 2 block is
the one read, its transition times being 8 bytes where version 1's run out in 2038. The footer's
`TZ` string, which extrapolates past the last transition, is not read: a lookup past the end answers
with the last type, which is what the table says.

**A time before the first transition takes the first type that is not daylight saving**, which is
RFC 8536's rule and is not the same as "the first type" — a file whose type 0 is the daylight one
would otherwise be an hour out for every date before its table begins.

## What is not here

**Shipping the database.** What is read above is the host's copy, which the operating system keeps up
to date; the library carries no table of its own, because one changes several times a year and a
standard library that shipped it would either go stale or need a release each time a legislature
moved a clock.

`wall_us` and `wall_of` are the seam all of this starts from: they read a `LocalDateTime` as a single
count measured from the same origin as an `Instant`, which is what the count would be if the offset
happened to be zero.

**The clock**, in *this* module. There is no `sysl.time.now()`, because reading one is a capability
rather than arithmetic — `clock_gettime` is a call into the environment, and this module has no
`requires` line. An `Instant` is a value a freestanding target can compute with; where it comes from
is the caller's to say, and on a host the answer is [`sysl.posix.time`](#reading-a-clock-sysl-posix-time)
above.

---

Next: [`sysl.sync`](/library/sync/) — atomics and the spinlock, which require nothing at all.
