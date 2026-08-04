---
title: The time module
summary: "`sysl.time` — `Instant` and `Duration` kept apart, the proleptic Gregorian calendar, `LocalDate`/`LocalTime`/`LocalDateTime`, the ISO 8601 renderers and the parsers that read them back."
weight: 65
---

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

### The difference of two instants is a named function, and it should not be

```sysl
import sysl.time.*

var a = Instant(0i64)
var b = Instant(1i64)

print(whole_seconds(b - a))
```

```error
'-' between sysl.time.Instant and sysl.time.Instant needs 'sysl.Sub' — it implements 'sysl.Sub[sysl.time.Duration]'
```

`since(later, earlier)` is what that operation is called here, and **it is a named function because
the operator cannot express it.** `Sub`'s method is `sub(self, rhs: Rhs) -> Self`: the result type is
fixed to the type on the left, so an `impl Sub[Instant] for Instant` may only produce another
`Instant` — which is the one thing the difference of two points on a timeline is not. The operator is
available and the meaning is not.

This is the module's report against the language, and it is worth stating plainly: the most
frequently written operation in any date-time library cannot be spelled with its own operator, and
what would fix it is an `Out` parameter on `Sub` of the kind [`Mul` already
carries](/reference/expressions/). Until then the argument order is the one that reads as a
subtraction — `since(later, earlier)`, not `since(earlier, later)` — and it answers a negative
duration when it is given the pair the other way round, as the second line above shows.

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

**`days(1)` is a length of exactly 24 hours, and it is not "a day".** A day that a zone's clocks move
through is 23 or 25 hours long, so a calendar day is not a fixed length of timeline at all. That
question belongs to the civil types below, which is why `plus_days` is theirs and not a `Duration`'s.

Scaling reads one way round only:

```sysl
import sysl.time.*

var d = seconds(3i64)

print(whole_seconds(2i64 * d))
```

```error
'*' needs matching types, got long and sysl.time.Duration
```

`d * 2i64` is what is written instead. An operator's implementation is written for the type on its
**left**, and nothing may be written for `long` — so the commutativity a reader expects of
multiplication is not something this module could have supplied.

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

## What is not here

**The zone.** A wall clock reading becomes an instant only once somebody says where the wall is, and
answering that needs the IANA time zone database — a table that changes several times a year, which a
standard library either ships and lets go stale or reads from the host and thereby needs a
filesystem. Both are decisions with costs, and neither belongs in a module whose whole claim is that
it is arithmetic. The [date and time guide](/guides/datetime/) builds a fixed-offset zone over this
module and shows what the real thing would take.

`wall_us` and `wall_of` are the seam a zone conversion starts from: they read a `LocalDateTime` as a
single count measured from the same origin as an `Instant`, which is what the count would be if the
offset happened to be zero.

**The clock.** There is no `now()`, because reading one is a capability rather than arithmetic —
`clock_gettime` is a call into the environment, and this module has no `requires` line. An `Instant`
is a value a freestanding target can compute with; where it comes from is the caller's to say.

---

Next: [`sysl.sync`](/library/sync/) — atomics and the spinlock, which require nothing at all.
