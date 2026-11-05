---
title: The time module
summary: "`sysl.time` — `Instant` and `Duration` kept apart, the proleptic Gregorian calendar, `Date`/`Time`/`DateTime`, `Period`, `Zone` and `Zoned`, the ISO 8601 renderers and the parsers that read them back."
weight: 65
---

**Every declaration in `sysl.time`, with its signature:** [the generated API page](/api/sysl-time/#index). This page is the argument — what the module is for, and how its pieces fit; that one is the list.

`sysl.time` is arithmetic on dates and lengths of time, and almost everything worth saying about it
is a **distinction the type system holds for you** — a point on the timeline is not a length of
one, and a reading on a wall clock is neither. Every library that blurs those has the same class of
bug in it, and the blur is what the types here refuse. There are eight of them, the same eight
slate's `slate:time` has:

| type | what it is | made with |
|---|---|---|
| `Instant` | a point on the timeline, with no calendar | `now()` |
| `Duration` | an exact length — microseconds to days | `5.ms`, `hours(2)`; `monotonic()` answers one |
| `Date` | a calendar date, with no time of day | `date(2026, 9, 2)` |
| `Time` | a time of day, with no date | `time(14, 30, 0)` |
| `DateTime` | a wall reading, with no zone | `date_time(2026, 9, 2, 14, 30, 0)` |
| `Zone` | a zone: a name, and the offset its clocks show at any instant | `utc`; `zone("America/Toronto")` and `local_zone()` in `sysl.posix.time` |
| `Zoned` | a wall reading in a zone | `instant.at(zone)`, `date_time.at(zone)` |
| `Period` | months and days | `months(3)`, `years(1)`, `Period(0, 1)` |

`Offset`, `Weekday`, `Resolution` and `YearMonthDay` (a date's three fields) sit beside them.
`LocalDate`, `LocalTime`, `LocalDateTime`, `Civil`, `date_at`, `time_at` and `datetime_at` are not
names in the module, and neither is the free `resolve(dt, fn)`: a program using one is refused with
the name that replaced it, or with `zone.resolve(dt)` for `resolve`:

```sysl
import sysl.time.*

show(d: LocalDate) = print(d)
```

```error
'LocalDate' was renamed to 'Date' in 0.10.0
```

It requires no capability: every name below is reachable on a target with no operating system.

```sysl
import sysl.time.*

var launch = date(2026, 3, 8)
var meeting = date_time(2026, 3, 8, 9, 30, 0)

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
'+' between Instant and Instant needs 'Add' — it implements 'Add[Duration]'
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
struct YearMonthDay
    year: int
    month: int
    day: int

// Days since 1970-01-01, signed.
struct Date
    day: int

// Microseconds since midnight.
struct Time
    us: long

struct DateTime
    date: Date
    time: Time
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

var d = date(2026, 3, 8)

print(year_of(d), month_of(d), day_of(d), month_name(month_of(d)))
print(day_of_year(d), weekday_name(weekday(d)))
print(days_in_month(2026, 2), days_in_month(2024, 2))
print(leap_year(2000), leap_year(1900), leap_year(2024))
print(date(1970, 1, 1).day, date(1969, 12, 31).day)
print(weekday_name(weekday(date(1970, 1, 1))))
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

**Three numbers the program wrote are a fault; text from outside is a result.** `date` is the
program's own words, so a thirtieth of February written into a program is a defect in it, and it
stops the program at the line that wrote it rather than rolling over into March:

```
print(date(2026, 2, 30))
```

```
panic: there is no such date: 2026-2-30 (main.sysl:3)
```

`time` is the same about an hour past 23 or a minute past 59, and `date_time` about either. Numbers
a program has *read* go through `checked_date`, which answers an `Option`, and text goes through
`parse_date`, which answers a `Result` (below):

```sysl
import sysl.time.*

print(checked_date(2026, 2, 30).is_none(), checked_date(2026, 2, 28).is_some())

val text = checked_date(2026, 2, 29) match
    Some(d) -> date_text(d)
    None -> "no such date"

print(text)
print(days_from_civil(YearMonthDay(2026, 2, 30)))
```

```output
true true
no such date
2026-03-02
```

**A walk that already knows its dates are real uses `days_from_civil`.** A calendar walk —
`plus_months`, a loop over the days of a month, `on_or_after` — has already established that its
dates are real, and a range test per step would be paid for by every one of them to catch nothing.
`days_from_civil` is that walk's arithmetic: pure, total, and the thirtieth of February is a day
count like any other.

The year is unbounded on purpose. The proleptic Gregorian calendar has no first or last one, and
`days_from_civil` is its own inverse over the whole `int` range — so `checked_date` checks the month
and the day and nothing else.

### Adding to a calendar

```sysl
import sysl.time.*

print(date(2026, 1, 31) + months(1))
print(date(2024, 1, 31) + months(1))
print(date(2024, 2, 29) + years(1), date(2024, 2, 29) + Period(0, 1))
print(date(2026, 1, 31) + Period(1, 1))
print(on_or_after(date(2026, 3, 8), Tuesday))
print(date_time(2026, 3, 8, 9, 30, 0) + Period(0, 7))
print(years(1) + months(2) + Period(0, 3), months(1) == Period(0, 30))
```

```output
2026-02-28
2024-02-29
2025-02-28 2024-03-01
2026-03-01
2026-03-10
2026-03-15 09:30
1y 2mo 3d false
```

**A `Period` is a length of calendar — so many months and so many days — and it is the other length
beside `Duration`.** A duration is exact timeline and a period is a step on the calendar, whose
length on the timeline depends on where it is taken: a month is 28 to 31 days, and a day is 23 to 25
hours across a change of a zone's clocks. **Months and days never convert** — `months(1)` holds no
days and is not `Period(0, 30)` — and a year is twelve months, which is exact, so `years` makes
months. Adding one applies the months first and the days after, which is why the 31st of January
plus a month and a day is the 1st of March by way of the 28th of February.

**Months are added by the calendar rather than by counting days, and the day is clamped where the
target month is shorter.** The 31st of January plus one month is the 28th or the 29th of February,
whichever that year has. Every library that offers this makes the choice, and clamping is the one
that keeps "the last day of the month" landing on a last day. `years(1)` is twelve months, which is
what makes the 29th of February behave on a non-leap year without a rule of its own. `plus_months`,
`plus_years` and `plus_days` are the same steps as functions.

**A period on a wall clock reading is not adding a length of time to it.** Seven days later is the
same clock face seven rows down the calendar, whatever the timeline did in between; across a change
of a zone's clocks the two answers differ by an hour ([below](#a-reading-in-a-zone)). Both are right —
they answer different questions, which is why they are spelled differently. And why this is refused:

```sysl
import sysl.time.*

var d = date(2026, 3, 8)

print(d + days(1))
```

```error
'+' between Date and Duration needs 'Add[Duration]' — it implements 'Add[Period]'
```

`on_or_after` is the first date on or after a given one falling on a given weekday, which is what
"every Tuesday" means once a rule has been given a starting point.

### Reading a time of day apart

A `Time` is one number of microseconds since midnight, and the four `*_of` functions read its
parts out of it — which is what a renderer, a comparison against a schedule, and anything printing a
clock face all want:

```sysl
import sysl.time.*

var t = time(13, 45, 30)

print(hour_of(t), minute_of(t), second_of(t), us_of(t))
```

```output
13 45 30 0
```

They are the `odd_` family's counterpart on the civil side, and they truncate nothing: a time of day
is already within one day, so each part is simply read. `us_of` is the sub-second remainder, and it
is zero here because `time` names whole seconds — a time parsed from `13:45:30.25` carries one.

### Rendering

```sysl
import sysl.time.*

var t = time(9, 30, 7)

print(date(2026, 3, 8))
print(time_text(t), time_text(time(9, 30, 0)), time_text(Time(500000)))
print(date_time(2026, 3, 8, 9, 30, 0))
print(offset_text(Offset(330)), offset_text(Offset(-480)), offset_text(Offset(0)))
```

```output
2026-03-08
09:30:07 09:30 00:00:00.500000
2026-03-08 09:30
+05:30 -08:00 Z
```

Every type here implements [`Display`](/library/core/), so a value goes straight into a `print` or an
interpolation; the `*_text` functions are the same renderers reached by name, for a caller that wants
the string.

**Seconds appear only when there are any.** A meeting at half past nine is written `09:30` by every
human being who has ever written one down, and a renderer insisting on `09:30:00` is reporting its
representation rather than its value. The microseconds appear on the same terms — and a renderer that
showed a value to the second while dropping what is below it would be reporting something the value
does not say.

`Z` for a zero offset is a spelling rather than a special case: ISO 8601 writes it that way, and
`parse_offset` reads it back as zero.

A `Duration` renders the way a stopwatch reads, in the largest of seconds, milliseconds and
microseconds it reaches, with the digits below written only as far as there are any — so a timing
goes straight into a line of output. `duration_text` is the same renderer by name:

```sysl
import sysl.time.*

print(seconds(1i64) + micros(234567i64), millis(12i64), micros(1500i64), micros(7i64))
print(-seconds(3i64), micros(0i64), minutes(90i64), s"took ${millis(250i64)}")
print(duration_text(millis(1001i64)))
```

```output
1.234567s 12ms 1.5ms 7µs
-3s 0s 5400s took 250ms
1.001s
```

Seconds are the largest unit because a duration is a measurement rather than a reading of a clock:
`5400s` is what a timer says, and `whole_hours` and `odd_minutes` build `1h30m` where that is wanted.

## Reading text back

```sysl
// Why a parse refused, at the granularity a caller can act on.
enum TimeParseError
    BadShape(at: usize)
    OutOfRange(what: string)
    Trailing(at: usize)
```

`TimeParseError` has `==`, variant by variant and payload by payload.

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

print(parse_timestamp("2026-03-03T09:30:00").unwrap_err().to_string())
print(parse_timestamp("2026-03-03T09:30:00-25:00").unwrap_err().to_string())
print(parse_timestamp("2026-03-03T09:30:00Z ").unwrap_err().to_string())
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

A `Zone` is a name and the offset its clocks show at any instant. **A fixed zone is a number and a
ruled zone is a rule**: `utc` is a `FixedZone`, and a zone whose clocks move is a `RuledZone` holding
its rule as a function. `zone.offset_at(instant)` asks it about a moment, and `zone.resolve(reading)`
says which of the three cases a wall reading is — and the return type is the point of it:

```sysl
import sysl.time.*

// US Eastern in 2023: -04:00 between the two moments its clocks moved, -05:00 outside them. The
// transitions are named in UTC because that is the only reading of them that is not itself
// ambiguous.
eastern_2023(t: Instant) -> Offset =
    if t.us >= 1678604400000000 && t.us < 1699164000000000 then Offset(-240) else Offset(-300)

val eastern = RuledZone("Eastern", eastern_2023)

print(eastern, eastern.offset_at(Instant(0)), utc.offset_at(Instant(0)))
print(eastern.resolve(date_time(2023, 6, 15, 12, 0, 0)))
print(eastern.resolve(date_time(2023, 11, 5, 1, 30, 0)))
print(eastern.resolve(date_time(2023, 3, 12, 2, 30, 0)))
```

```output
Eastern -05:00 Z
2023-06-15 16:00 Z
2023-11-05 05:30 Z or 2023-11-05 06:30 Z
no such time: the clocks went from -05:00 to -04:00
```

**The rule is a function rather than a table**, and that is what keeps `Zone` in capability-free
`sysl.time`: the whole of what resolution needs to know about a zone is what its offset was at a
given instant. A table decoded from a TZif file, the host's own `localtime_r`, a package talking to an
RTC chip and a rule written out by hand all answer that one question — `zone("America/Toronto")` in
`sysl.posix.time` is the first of those, wrapped ([below](#a-zone-by-name)). A **closure** answers as
well as a declaration does:

```sysl
import sysl.time.{Offset, RuledZone, date_time}

val mins = -300

print(RuledZone("fixed", t -> Offset(mins)).resolve(date_time(2023, 11, 5, 1, 30, 0)))
```

```output
2023-11-05 06:30 Z
```

A zone whose offset never varies has no transition to fall either side of, so the reading is
`Unique` and prints as the one instant it names.

`Gap` carries the offsets either side of the transition rather than an instant, because there is no
instant to carry. What to do about a reading that never happened is a policy question — push it
forward by the size of the gap, clamp it to the transition, or refuse it — and `at_earlier` and
`at_later` below take the first, which is the one most libraries choose.

**Nothing above holds a zone database, and `Zone` is not one.** It is the arithmetic that a zone
database would be consulted *by*.

### A reading in a zone

A `Zoned` is a `DateTime`, the `Offset` that was in force, and the `Zone`. The offset is held because
the reading alone is not enough: 01:30 on the 1st of November in Toronto happens twice, an hour apart,
and only the offset says which. `instant.at(zone)` always answers one; `date_time.at(zone)` answers a
`Result`, handing back the `Resolution` where the zone skips or repeats the reading rather than
guessing, and `at_earlier` and `at_later` make the choice explicitly:

```sysl
import sysl.time.*

// Toronto in 2026: the clocks went forward at 02:00 on the 8th of March and back at 02:00 on the
// 1st of November.
val spring = from_offset(date_time(2026, 3, 8, 2, 0, 0), Offset(-300))
val autumn = from_offset(date_time(2026, 11, 1, 2, 0, 0), Offset(-240))
val toronto = RuledZone("America/Toronto",
    t -> if t >= spring && t < autumn then Offset(-240) else Offset(-300))

val t = date_time(2026, 3, 7, 9, 0, 0).at(toronto).unwrap()

print(t + Period(0, 1))
print(t + 24.hours)
print((t + 24.hours) - (t + Period(0, 1)))

val skipped = date_time(2026, 3, 8, 2, 30, 0)

print(skipped.at(toronto).is_err(), skipped.at_earlier(toronto))

val twice = date_time(2026, 11, 1, 1, 30, 0)

print(twice.at_earlier(toronto).offset, twice.at_later(toronto).offset)
print(t.instant(), t.at(utc))
```

```output
2026-03-08T09:00:00-04:00[America/Toronto]
2026-03-08T10:00:00-04:00[America/Toronto]
3600s
true 2026-03-08T03:30:00-04:00[America/Toronto]
-04:00 -05:00
2026-03-07 14:00 Z 2026-03-07T14:00:00Z[UTC]
```

**A `Duration` moves the timeline and a `Period` moves the calendar**, and across a change of the
clocks the two differ: a day after nine on Saturday morning is nine on Sunday morning, 23 hours of
timeline later, and twenty-four hours after it is ten. Both are right, and the program says which. A
period landing in the hour the clocks skip is pushed past it; one landing in the hour they repeat
keeps the offset it had where that is one of the two, and takes the earlier where not — `java.time`'s
rules. An `Instant` takes only a `Duration`, having no calendar to move by.

**`==` on a zoned reading needs the same instant and the same zone, where `<` compares only the
instant.** Nine in the morning in Toronto and ten at night in Tokyo are one instant and two readings,
so they are not equal and neither is before the other.

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
    n = n *% 31 +% long(i)

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
error: 't' of 'sysl.time.instant_text' is Instant, but Duration was given
```

Both require `posix`.

There are two more readings of the same kind, for *how busy* rather than *how long*. `cpu_time`
answers the processor time the whole process has used, summed over every thread, and
`thread_cpu_time` answers the calling thread's own. Both are `Duration`s read as a difference, and
neither moves while the thread sleeps, which is what separates them from `monotonic`. Take both
readings of `thread_cpu_time` on the **same** thread: reading another thread's clock is not offered,
because Linux and macOS spell it differently.

```sysl
import sysl.posix.time.{thread_cpu_time, cpu_time, monotonic}
import sysl.time.{millis}
import sysl.posix.time.sleep

val c0 = thread_cpu_time()
val p0 = cpu_time()

var n: long = 1

for i in 0..<2000000
    n = n *% 31 +% long(i)

val busy = (thread_cpu_time() - c0).us

sleep(millis(50))

val slept = (thread_cpu_time() - c0).us - busy
print(busy > 0)
print(slept < 25000)
print((cpu_time() - p0).us >= busy)
print(n != 0)
```

```output
true
true
true
true
```

### Waiting

`sleep` here is [`sysl.time.sleep`](#the-portable-spelling-sysl-time-now), which waits through a
seam this module answers over `nanosleep` on a host — so the same line waits on a board whose package
answers the seam over its timer. It takes a `Duration`, which is what makes the line read the same in
both places.

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
that depends on it starts looking flaky. The seam answers that remainder and `sleep` asks again for
it until nothing is left, and answers nothing, because by the time it returns there is nothing left
to say.

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

`from_local` is the reverse and answers a `Resolution`, for the reason the section above gives; it
is `local_zone().resolve(reading)`, and `local_zone()` is the same host zone as a `Zone`.
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
the same source between the two is a change to `sysl.hocon` rather than to any line of it.

The mechanism is the general one — [a module may supply another module's
`extern`](/reference/ffi/#a-module-may-supply-another-module-s-extern) — and a supplier is an ordinary
`@export` of `sysl_wall_us` or `sysl_monotonic_us` answering a `long` of microseconds.

**Waiting is the third seam.** `sysl.time.sleep(d)` asks `sysl_sleep_us(us: long) -> long` to wait,
and the answer is the microseconds still owed — zero when the whole wait passed — which `sleep` asks
for again until nothing is left. A host answers it over `nanosleep`; a board or a kernel answers it
over its timer, in the same shape — here a board whose timer is a counter, built as the archive its C
project links:

```sysl build=c target=aarch64-freestanding
import sysl.time.{sleep, millis}

var asked: long = 0

@export("sysl_sleep_us")
board_sleep(us: long) -> long
    asked += us
    0

@export("pause")
pause() = sleep(millis(3))
```

A freestanding program that waits and answers none of it builds too, and its archive leaves
`sysl_sleep_us` for the board's own link to answer, exactly as it leaves the two clocks.

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

`sysl.posix.time.zone` does both and answers a `sysl.time.Zone`, so a named zone is used exactly as
the hand-written rules above are. `"UTC"` is `utc` and an offset such as `"+05:30"` is a `FixedZone`,
neither reading a file; a name the database does not have is a `ZoneError`. `local_zone()` is the
host's own zone, answered by `localtime_r`:

```sysl
import sysl.posix.time.{zone, local_zone, local_offset}
import sysl.time.{date_time, Period, now}

val toronto = zone("America/Toronto").unwrap()
val t = date_time(2026, 3, 7, 9, 0, 0).at(toronto).unwrap()

print(t + Period(0, 1))
print(toronto.resolve(date_time(2026, 11, 1, 1, 30, 0)))
print(t.at(zone("Asia/Tokyo").unwrap()), zone("+05:30").unwrap())
print(zone("Mars/Olympus_Mons").unwrap_err())
print(local_zone().offset_at(now()) == local_offset(now()))
```

```output
2026-03-08T09:00:00-04:00[America/Toronto]
2026-11-01 05:30 Z or 2026-11-01 06:30 Z
2026-03-07T23:00:00+09:00[Asia/Tokyo] +05:30
no zone is named 'Mars/Olympus_Mons'
true
```

**The split is what makes a zone reachable on a bare machine.** A board with a real-time clock carries
one zone's bytes in flash — the zone it was deployed in — and resolves local time with no filesystem
anywhere. Weld the decoder to the file reading and a freestanding target gets none of it. The two
halves by hand, which is also the way to the rest of what the file carries — the abbreviation and the
daylight flag:

```sysl
import sysl.posix.time.zone_data
import sysl.time.tzif.{parse, offset_at, abbrev_at, is_dst_at}
import sysl.time.{Instant, RuledZone, date_time}

val bytes = zone_data("America/Toronto").unwrap()
val table = parse(bytes[..]).unwrap()
val toronto = RuledZone("America/Toronto", t -> offset_at(table, t).unwrap())

val summer = Instant(1686844800000000)

print(offset_at(table, summer).unwrap(), abbrev_at(table, summer).unwrap(), is_dst_at(table, summer).unwrap())
print(toronto.resolve(date_time(2023, 11, 5, 1, 30, 0)))
print(toronto.resolve(date_time(2023, 3, 12, 2, 30, 0)))
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

`wall_us` and `wall_of` are the seam all of this starts from: they read a `DateTime` as a single
count measured from the same origin as an `Instant`, which is what the count would be if the offset
happened to be zero.

**A clock of this module's own.** `sysl.time.now()` and `monotonic()` read symbols this module
declares and something else defines ([above](#the-portable-spelling-sysl-time-now)), because reading
a clock is a capability rather than arithmetic and this module has no `requires` line. On a host
the answer is [`sysl.posix.time`](#reading-a-clock-sysl-posix-time).

---

Next: [`sysl.sync`](/library/sync/) — atomics and the spinlock, which require nothing at all.
