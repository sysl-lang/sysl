---
title: sysl.time
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.time
summary: "Points on the timeline, lengths of it, and the calendar that turns one into a date."
---

`Instant` and `Duration` are the two quantities everything else here is written in terms of, with
`5.ms` and `5.hours` available on any integer. Above them sit the civil types — `LocalDate`,
`LocalTime`, `LocalDateTime` and `Offset` — the fixed-offset conversions, `resolve` for a zone
whose clocks move, and the ISO 8601 renderers and parsers.

**It reads no clock itself.** `now` and `monotonic` are declared here and *supplied by the
linker*, so a program on a board provides its own and a hosted one gets `sysl.posix.time`'s. That
is what keeps a calendar usable on a target with no operating system to ask, and it is why the two
are separate modules.

## Index

[`us_per_day`](#us_per_day) [`us_per_hour`](#us_per_hour) [`us_per_milli`](#us_per_milli) [`us_per_minute`](#us_per_minute) [`us_per_second`](#us_per_second) [`at_offset`](#at_offset) [`checked_date`](#checked_date) [`civil_from_days`](#civil_from_days) [`date_at`](#date_at) [`date_text`](#date_text) [`datetime_at`](#datetime_at) [`datetime_text`](#datetime_text) [`day_of`](#day_of) [`day_of_year`](#day_of_year) [`days`](#days) [`days_from_civil`](#days_from_civil) [`days_in_month`](#days_in_month) [`from_offset`](#from_offset) [`hour_of`](#hour_of) [`hours`](#hours) [`instant_text`](#instant_text) [`leap_year`](#leap_year) [`micros`](#micros) [`millis`](#millis) [`minute_of`](#minute_of) [`minutes`](#minutes) [`monotonic`](#monotonic) [`month_name`](#month_name) [`month_of`](#month_of) [`now`](#now) [`odd_hours`](#odd_hours) [`odd_minutes`](#odd_minutes) [`odd_seconds`](#odd_seconds) [`odd_us`](#odd_us) [`offset_text`](#offset_text) [`on_or_after`](#on_or_after) [`parse_date`](#parse_date) [`parse_datetime`](#parse_datetime) [`parse_offset`](#parse_offset) [`parse_time`](#parse_time) [`parse_timestamp`](#parse_timestamp) [`plus_days`](#plus_days) [`plus_months`](#plus_months) [`plus_years`](#plus_years) [`resolve`](#resolve) [`second_of`](#second_of) [`seconds`](#seconds) [`since`](#since) [`time_at`](#time_at) [`time_text`](#time_text) [`timestamp_text`](#timestamp_text) [`us_of`](#us_of) [`wall_of`](#wall_of) [`wall_us`](#wall_us) [`weekday`](#weekday) [`weekday_name`](#weekday_name) [`whole_days`](#whole_days) [`whole_hours`](#whole_hours) [`whole_micros`](#whole_micros) [`whole_millis`](#whole_millis) [`whole_minutes`](#whole_minutes) [`whole_seconds`](#whole_seconds) [`year_of`](#year_of) [`Civil`](#civil) [`Duration`](#duration) [`Instant`](#instant) [`LocalDate`](#localdate) [`LocalDateTime`](#localdatetime) [`LocalTime`](#localtime) [`Offset`](#offset) [`Resolution`](#resolution) [`TimeParseError`](#timeparseerror) [`Weekday`](#weekday-1) [`DurationUnits`](#durationunits) [Add for Duration](#add-for-duration) [Add for Instant](#add-for-instant) [Display for Instant](#display-for-instant) [Display for LocalDate](#display-for-localdate) [Display for LocalDateTime](#display-for-localdatetime) [Display for LocalTime](#display-for-localtime) [Display for Offset](#display-for-offset) [Display for Resolution](#display-for-resolution) [Display for TimeParseError](#display-for-timeparseerror) [DurationUnits for T](#durationunits-for-t) [Eq for Duration](#eq-for-duration) [Eq for Instant](#eq-for-instant) [Eq for LocalDate](#eq-for-localdate) [Eq for LocalDateTime](#eq-for-localdatetime) [Eq for LocalTime](#eq-for-localtime) [Eq for Offset](#eq-for-offset) [Mul for Duration](#mul-for-duration) [Mul for N](#mul-for-n) [Neg for Duration](#neg-for-duration) [Ord for Duration](#ord-for-duration) [Ord for Instant](#ord-for-instant) [Ord for LocalDate](#ord-for-localdate) [Ord for LocalDateTime](#ord-for-localdatetime) [Ord for LocalTime](#ord-for-localtime) [Ord for Offset](#ord-for-offset) [Sub for Duration](#sub-for-duration) [Sub for Instant](#sub-for-instant) [Sub for Instant](#sub-for-instant-1)

## Constants

### `us_per_day`

```sysl
const us_per_day: long = 86400000000
```

### `us_per_hour`

```sysl
const us_per_hour: long = 3600000000
```

### `us_per_milli`

```sysl
const us_per_milli: long = 1000
```

### `us_per_minute`

```sysl
const us_per_minute: long = 60000000
```

### `us_per_second`

```sysl
const us_per_second: long = 1000000
```

## Functions

### `at_offset`

```sysl
at_offset(t: Instant, o: Offset) -> LocalDateTime
```

An instant read against a wall set a fixed distance from UTC, and back. **Both directions are
total**, which is what separates a fixed offset from a zone: a zone whose clocks move answers the
second question with none or two, and this pair never does, because an offset is a number rather
than a rule with a history.

That is also why they are here and a zone is not. A timestamp on a wire, in a log or in a database
column carries the offset that was in force when it was written — the sender resolved the zone
already, and what arrived is the record of that decision. Reading one back needs no table, no
update cadence and no filesystem, which is the whole of what makes it the library's business.

### `checked_date`

```sysl
checked_date(y: int, m: int, d: int) -> Option[LocalDate]
```

A date only if the calendar has one, which is the question `date_at` deliberately does not ask.

`date_at` is `days_from_civil` under another name, and that conversion is pure arithmetic: a
thirtieth of February is a day count like any other, and it comes back as the second of March.
That is what a calendar walk wants -- `plus_days`, a loop over the months -- because such a walk
has already established that its dates are real and would be paying for a range test per step. So
the check is a **second constructor** rather than a change to the first, and the two answer
different questions.

What asks this one is a reader: text names three numbers that a person wrote, and a format that
says a date must exist has to be able to refuse `2026-02-30`. `parse_date` already answers
`Err(OutOfRange(...))` for exactly that, so the checking existed and could not be reached without
going through text -- which a reader that has already scanned the digits itself has no way to do.

The year is unbounded on purpose: the proleptic Gregorian calendar is, and `days_from_civil` is
its own inverse over the whole `int` range.

### `civil_from_days`

```sysl
civil_from_days(d: LocalDate) -> Civil
```

### `date_at`

```sysl
date_at(y: int, m: int, d: int) -> LocalDate
```

### `date_text`

```sysl
date_text(d: LocalDate) -> string
```

### `datetime_at`

```sysl
datetime_at(y: int, mo: int, d: int, h: int, mi: int, s: int) -> LocalDateTime
```

### `datetime_text`

```sysl
datetime_text(ldt: LocalDateTime) -> string
```

### `day_of`

```sysl
day_of(d: LocalDate) -> int
```

### `day_of_year`

```sysl
day_of_year(d: LocalDate) -> int
```

The day of the year, from 1 on the first of January. It is a subtraction rather than a sum over
the months, because the day numbers are already what the subtraction needs.

### `days`

```sysl
days(n: long) -> Duration
```

### `days_from_civil`

```sysl
days_from_civil(c: Civil) -> LocalDate
```

Howard Hinnant's civil-from-days pair, which is the standard way to do this and is worth using
rather than rediscovering. It works by shifting the epoch to the start of a 400-year era, after
which every quantity in it is non-negative and the leap rules become one exact division apiece.

`days_from_civil` is its own inverse over the whole `int` range, which is the property to assert
rather than any particular date.

### `days_in_month`

```sysl
days_in_month(y: int, m: int) -> int
```

How many days a month has, which is the one calendar question that cannot be answered by counting.

### `from_offset`

```sysl
from_offset(ldt: LocalDateTime, o: Offset) -> Instant
```

### `hour_of`

```sysl
hour_of(t: LocalTime) -> int
```

### `hours`

```sysl
hours(n: long) -> Duration
```

### `instant_text`

```sysl
instant_text(t: Instant) -> string
```

An instant written down, which is the one rendering here that needs no zone: an offset of zero is
what the count is measured from rather than a conversion applied to it, so this reports the value
rather than interpreting it. `Z` says which, in the spelling ISO 8601 gives that offset.

It is the module's because coherence makes it the module's alone. `Display` belongs to the library
and so does `Instant`, so no program naming neither of them may write an `impl` for the pair — and
a type nothing may render is a type that renders nowhere at all.

### `leap_year`

```sysl
leap_year(y: int) -> bool
```

Whether a year has a 29th of February, by the rule the proleptic Gregorian calendar states: every
fourth year, except every hundredth, except every four-hundredth.

### `micros`

```sysl
micros(n: long) -> Duration
```

The constructors, from the shortest length this representation can name to the longest it should.

`micros` is the identity, and is here anyway: the representation being microseconds is a fact of
this module rather than of the caller, and a program writing `Duration(n)` has reached past the
constructors to depend on it. The two short ones are also the only two an embedded program asks
for -- a blink loop wants 120 milliseconds, and there is no way to spell that in `seconds`.

### `millis`

```sysl
millis(n: long) -> Duration
```

### `minute_of`

```sysl
minute_of(t: LocalTime) -> int
```

### `minutes`

```sysl
minutes(n: long) -> Duration
```

### `monotonic`

```sysl
monotonic() -> Duration
```

A monotonic counter, as a length of time from an origin this deliberately does not name.

One reading answers nothing. **Two readings subtracted answer how long something took**, and that
is the only question it can be asked. It never goes backwards and nothing can set it, which is the
difference from `now` and the reason a measurement wants this one.

### `month_name`

```sysl
month_name(m: int) -> string
```

### `month_of`

```sysl
month_of(d: LocalDate) -> int
```

### `now`

```sysl
now() -> Instant
```

The wall clock: where whatever is keeping time thinks it is on the timeline.

**Two readings of this may differ by anything at all**, including a negative amount, because it is
the clock a person and an `ntpd` are both allowed to set. Use it to stamp something, and use
`monotonic` to measure something.

`sysl.posix.time.now` is the same reading on a host and needs no seam, so a program that is only
ever going to run on one is free to import that instead. What this one buys is that the same source
compiles against a board's clock as well.

### `odd_hours`

```sysl
odd_hours(d: Duration) -> long
```

…and the remainders, which are what the parts above leave. `odd_minutes` of three and a half hours
is thirty, not two hundred and ten.

### `odd_minutes`

```sysl
odd_minutes(d: Duration) -> long
```

### `odd_seconds`

```sysl
odd_seconds(d: Duration) -> long
```

### `odd_us`

```sysl
odd_us(d: Duration) -> long
```

### `offset_text`

```sysl
offset_text(o: Offset) -> string
```

`+05:30`, the way an offset is written everywhere one is written down — a sign, then a width the
reader can rely on, which is what makes a rendered timestamp sortable as text. `Z` is UTC, and it
is a spelling rather than a special case: an offset of zero is written that way by ISO 8601 and
read back as zero by `offset_of` below.

### `on_or_after`

```sysl
on_or_after(d: LocalDate, w: Weekday) -> LocalDate
```

The first date on or after `d` falling on the given weekday, which is what "every Tuesday" means
once a rule has been given a starting point.

### `parse_date`

```sysl
parse_date(s: string) -> Result[LocalDate, TimeParseError]
```

`YYYY-MM-DD`. The year is four digits, which is what ISO 8601 says and what `date_text` writes;
a year outside 0..9999 has no ISO spelling without a sign prefix, and that extension is not here.

### `parse_datetime`

```sysl
parse_datetime(s: string) -> Result[LocalDateTime, TimeParseError]
```

A date and a time, joined by a space or a `T`. Both spellings are read because both are written:
ISO 8601 says `T`, and `datetime_text` writes a space because that is what a person reads.

### `parse_offset`

```sysl
parse_offset(s: string) -> Result[Offset, TimeParseError]
```

`Z`, `+05:30`, `-08:00`. The `Z` is zero, which is the spelling `offset_text` writes it back as.

### `parse_time`

```sysl
parse_time(s: string) -> Result[LocalTime, TimeParseError]
```

`HH:MM`, `HH:MM:SS`, or `HH:MM:SS.ffffff` — the seconds and the fraction each optional, on the
same terms `time_text` writes them.

### `parse_timestamp`

```sysl
parse_timestamp(s: string) -> Result[Instant, TimeParseError]
```

A whole timestamp, straight to the point on the timeline it names — which is the thing a program
reading a log line, a wire format or a database column actually wants, and the one conversion in
this direction that needs no zone table.

**It reads more shapes than `timestamp_text` writes**, deliberately. The renderer emits one form
because a wire format with options is a wire format everybody implements differently; the parser
takes the space as well as the `T`, and a time with no seconds as well as one with them, because
what arrives was written by somebody else. Liberal in what it accepts, strict in what it sends.

### `plus_days`

```sysl
plus_days(ldt: LocalDateTime, n: int) -> LocalDateTime
```

Adding days to a wall clock reading is **not** adding a length of time to it. Seven days later is
the same clock face seven rows down the calendar, whatever the timeline did in between — and
across a change of a zone's clocks the two answers differ by an hour. Both are right; they answer
different questions, which is why they are spelled differently.

### `plus_months`

```sysl
plus_months(d: LocalDate, n: int) -> LocalDate
```

Months are added by the calendar rather than by counting days, so the day of the month is clamped
where the target month is shorter: the 31st of January plus one month is the 28th or 29th of
February. Every library that offers this makes that choice, and clamping is the one that keeps
"the last day of the month" landing on a last day.

### `plus_years`

```sysl
plus_years(d: LocalDate, n: int) -> LocalDate
```

### `resolve`

```sysl
resolve(ldt: LocalDateTime, offset_at: Instant -> Offset) -> Resolution
```

A wall clock reading resolved against a zone, where a zone is anything able to say what its offset
was at a given instant.

**The zone arrives as a function rather than as a type**, which is what keeps this file free of a
capability. The whole of what resolution needs to know about a zone is that one question, and a
host reading `localtime_r`, a package talking to an RTC chip and a table written out by hand can
all answer it. `sysl.posix.time.from_local` is the host's answer wired to this.

## How it decides, and the one assumption in it

A reading taken as though the offset were zero is within fourteen hours of the instant it names,
because that is the widest any zone is ever set from UTC. So the offsets a day either side of that
guess bracket every transition that could bear on the answer, and there are at most two of them to
try.

Each candidate is applied and then **checked against itself**: an offset that is still in force at
the instant it produces is a real answer, and one that is not is the reading being read with an
offset nobody's clock was showing. One survivor is `Unique`, two is `Ambiguous`, and none is `Gap`
-- the reading fell in the hour the clocks skipped, so no offset can produce it.

**The assumption is that a zone does not change its offset twice inside forty-eight hours.** No
zone in the IANA database ever has. One that did would be resolved against the wrong pair of
offsets rather than diagnosed, which is worth writing down because nothing here could detect it.

### `second_of`

```sysl
second_of(t: LocalTime) -> int
```

### `seconds`

```sysl
seconds(n: long) -> Duration
```

### `since`

```sysl
since(later: Instant, earlier: Instant) -> Duration
```

The same difference under a name that says which end is which. `later - earlier` is right and
`earlier - later` is just as easy to write, so the named form stays for the call that wants to be
read rather than worked out.

### `time_at`

```sysl
time_at(h: int, m: int, s: int) -> LocalTime
```

### `time_text`

```sysl
time_text(t: LocalTime) -> string
```

Seconds appear only when there are any. A meeting at half past nine is written `09:30` by every
human being who has ever written one down, and a renderer insisting on `09:30:00` is reporting its
representation rather than its value. The microseconds appear on the same terms, because a
renderer that shows a value to the second and drops what is below it is reporting something the
value does not say.

### `timestamp_text`

```sysl
timestamp_text(t: Instant, o: Offset) -> string
```

A timestamp as a machine reads it: `2026-03-08T09:30:00-05:00`, or `…Z` where the offset is zero.

**This one is written for RFC 3339 and the renderers above are written for people**, which is why
the shapes differ where it would have been easy to share one. The seconds are always present, even
at zero, because RFC 3339 requires them — so `datetime_text`'s rule of dropping what a value does
not say is exactly wrong here, where the reader is a parser with a grammar rather than somebody
glancing at a meeting time. The date and the time are joined by `T` rather than a space for the
same reason, and the offset is written flush against the time rather than after a space.

The fraction still appears only when there is one, because RFC 3339 makes it optional and a
`.000000` on every timestamp is six characters of noise on the wire.

### `us_of`

```sysl
us_of(t: LocalTime) -> long
```

### `wall_of`

```sysl
wall_of(us: long) -> LocalDateTime
```

### `wall_us`

```sysl
wall_us(ldt: LocalDateTime) -> long
```

A wall clock reading as a single count, measured from the same origin as an `Instant` but **not**
an instant: it is what the count would be if the zone's offset happened to be zero. A conversion
through a zone starts here and then asks what the offset actually is.

### `weekday`

```sysl
weekday(d: LocalDate) -> Weekday
```

1970-01-01 was a Thursday, so shifting by four puts Sunday at zero. Nothing here needs to know
which day a week starts on; it only needs the seven in the order the world uses them.

A weekday is a **simple** enum, so it converts to its number and back, and both directions are
used: the arithmetic wants a number to take a remainder of, and everything else wants a name. The
remainder is in range by construction, which is what makes the conversion back total rather than
something that has to be checked.

### `weekday_name`

```sysl
weekday_name(w: Weekday) -> string
```

The day's name, which is the variant's own name and so is not written twice. `Weekday::Image`
answers a simple enum's variant with the word it is spelled with (`reference/attributes.md § A
simple enum`), so this function is the library's name for the attribute rather than a table that
could drift from the declaration above it. `month_name` below is the other kind and stays a
table: a month is an `int` here, and "January" is a word nothing in the source is already
holding.

### `whole_days`

```sysl
whole_days(d: Duration) -> long
```

The parts a duration is read back in, each truncated toward zero. They are how a length of time is
stated to somebody who has to check it: "167 hours" is a sentence a reader can hold against a
calendar, and 601200000000 is not.

### `whole_hours`

```sysl
whole_hours(d: Duration) -> long
```

### `whole_micros`

```sysl
whole_micros(d: Duration) -> long
```

### `whole_millis`

```sysl
whole_millis(d: Duration) -> long
```

### `whole_minutes`

```sysl
whole_minutes(d: Duration) -> long
```

### `whole_seconds`

```sysl
whole_seconds(d: Duration) -> long
```

### `year_of`

```sysl
year_of(d: LocalDate) -> int
```

## Types

### `Civil`

```sysl
struct Civil
    year: int
    month: int
    day: int
```

Proleptic Gregorian, which means the Gregorian rules run backwards through the years before
anybody agreed to them. That is a decision and not an oversight: the alternative is a calendar
with a ten-day hole in it whose position depends on which country you ask.

### `Duration`

```sysl
struct Duration
    us: long
```

A length of timeline. It is **not** a number of days, because a day is not a fixed length of
timeline — a day that a zone's clocks move through is 23 or 25 hours long.

### `Instant`

```sysl
struct Instant
    us: long
```

A point on the timeline, counted from 1970-01-01T00:00:00Z. It names no place and no calendar:
the same `Instant` is a different wall clock reading in every zone, which is the entire reason it
is a separate type from the civil ones.

### `LocalDate`

```sysl
struct LocalDate
    day: int
```

Days since 1970-01-01. Signed, so dates before the epoch are ordinary values rather than a special
case — the algorithm below never branches on the sign.

### `LocalDateTime`

```sysl
struct LocalDateTime
    date: LocalDate
    time: LocalTime
```

A wall clock reading. Two independent counts rather than one, because they are added to and
compared far more often than they are combined.

### `LocalTime`

```sysl
struct LocalTime
    us: long
```

Microseconds since midnight, from zero up to but not including a whole day.

### `Offset`

```sysl
struct Offset
    minutes: int
```

How far a zone's wall clock is set from UTC, in whole minutes. Minutes rather than hours because
India is at +05:30 and Nepal at +05:45, and a library that assumes whole hours works everywhere
its author has lived.

### `Resolution`

```sysl
enum Resolution
    Unique(at: Instant)
    Ambiguous(earlier: Instant, later: Instant)
    Gap(before: Offset, after: Offset)
```

What a wall clock reading turns out to name, once a zone has been asked.

**A reading is usually one instant, sometimes none, and sometimes two.** That is a property of
zones rather than a shortcoming here: where a zone sets its clocks forward, the hour it skips never
happens, and where it sets them back, the hour it repeats happens twice. Every date-time library
either says so in a return type or picks one silently, and the host's own answer is the silent
kind -- `mktime` given the repeated reading answers the second occurrence and reports nothing, and
given the skipped one it rewrites the caller's fields and answers a time nobody asked for.

`Gap` carries the offsets either side of the transition rather than an instant, because there is no
instant to carry. What to do about it is a policy question -- push the reading forward by the size
of the gap, clamp it to the transition, or refuse it -- and `from_offset(ldt, before)` is the first
of those, which is the one most libraries choose.

### `TimeParseError`

```sysl
enum TimeParseError
    BadShape(at: usize)
    OutOfRange(what: string)
    Trailing(at: usize)
```

Why a parse refused, at the granularity a caller can act on.

The three are separated by what a caller would *do*: a shape error is a message about the format,
a range error is a message about the value, and a trailing error usually means the text held
something more that the caller meant to split off first.

### `Weekday`

```sysl
enum Weekday
    Sunday
    Monday
    Tuesday
    Wednesday
    Thursday
    Friday
    Saturday
```

## Traits

### `DurationUnits`

```sysl
trait DurationUnits
    us -> Duration
    ms -> Duration
    s -> Duration
    minutes -> Duration
    hours -> Duration
    days -> Duration
```

Durations written the way a datasheet writes them: the number first and the unit after it, so
`sleep(5.ms)` and `join(ssid, pw, auth, 20.s)` say at a glance what `millis(5)` and `seconds(20)`
say after a moment's reading.

**The short units are symbols and the long ones are words**, which is a split about where each is
used rather than a compromise between two styles. A timeout, a poll interval and a debounce are
the sub-second end, they are written constantly, and `ms` is the spelling every datasheet already
uses. At the other end the number is small and the line is not dense, so `30.days` costs nothing
and says more than `30.d` would. `5.min` is deliberately absent: it would sit beside `min(a, b)`
and `int::Min` meaning something else entirely in each position.

| property | the constructor it mirrors |
|---|---|
| `5.us` | `micros(5)` |
| `5.ms` | `millis(5)` |
| `5.s` | `seconds(5)` |
| `5.minutes` | `minutes(5)` |
| `5.hours` | `hours(5)` |
| `5.days` | `days(5)` |

**The free constructors stay**, and the two spellings are not a duplication to be tidied away: a
duration built from a *computed* value reads better as `millis(n)` than as `n.millis`, and the
constructors are what `library/` and `sysl-lang/pico2` are written against.

There is no matching set for `Instant`. A point on the timeline has no natural `5.<unit>` — five
of what, from when? — so the only way to name one is still to say which epoch it is counted from.

| Member | Signature | Description |
|---|---|---|
| `us` | `us -> Duration` |  |
| `ms` | `ms -> Duration` |  |
| `s` | `s -> Duration` |  |
| `minutes` | `minutes -> Duration` |  |
| `hours` | `hours -> Duration` |  |
| `days` | `days -> Duration` |  |

## Implementations

### Add for Duration

```sysl
impl Add for Duration
```

### Add for Instant

```sysl
impl Add[Duration] for Instant
```

An instant moved by a length of time. This is the **absolute** answer: it lands exactly that many
microseconds along the timeline whatever any calendar does in between.

### Display for Instant

```sysl
impl Display for Instant
```

### Display for LocalDate

```sysl
impl Display for LocalDate
```

### Display for LocalDateTime

```sysl
impl Display for LocalDateTime
```

### Display for LocalTime

```sysl
impl Display for LocalTime
```

### Display for Offset

```sysl
impl Display for Offset
```

### Display for Resolution

```sysl
impl Display for Resolution
```

The rendering, so that a resolution can be reported without matching on it.

### Display for TimeParseError

```sysl
impl Display for TimeParseError
```

### DurationUnits for T

```sysl
impl[T: Integer] DurationUnits for T
```

One block over the whole integer family, which is what `Display` and `Hash` already do for the
same reason: `u12` and `i5` are types a program may name, so no finite list of blocks could cover
them, and a bare literal's `int` is only one member of the family it has to cover.

The widening to `long` is where the representation is met — a `Duration` counts microseconds in a
`long` — and it is what lets a `u8` receiver work at all.

### Eq for Duration

```sysl
impl Eq for Duration
```

### Eq for Instant

```sysl
impl Eq for Instant
```

### Eq for LocalDate

```sysl
impl Eq for LocalDate
```

### Eq for LocalDateTime

```sysl
impl Eq for LocalDateTime
```

### Eq for LocalTime

```sysl
impl Eq for LocalTime
```

### Eq for Offset

```sysl
impl Eq for Offset
```

### Mul for Duration

```sysl
impl Mul[long] for Duration
```

Scaling, which reads both ways round. `d * 2` is the block below and `2 * d` is the one after it:
an operator's implementation used to be findable only from the type on its **left**, so a count on
the left had nowhere to be written, and coherence now counts the trait's arguments as well as its
subject -- a block naming `Duration` belongs in the module declaring `Duration`, whichever side of
the operator the count sits on.

### Mul for N

```sysl
impl[N: Integer] Mul[Duration, Duration] for N
```

The count on the left, forwarding to the block above -- and **one block over the whole integer
family**, so a count of any width scales a duration. `Integer` is a bound the compiler closes:
every `iN` and `uN` is in it and nothing a program declares can join, which is what makes one
block able to stand for all of them.

**The member this gives each width is `mul.2`, not `mul`**, since the compiler already provides an
integer's own multiplication under the plain name. That is the same suffixing a block written out
for one type takes, and it is what keeps `5.mul(2)` reaching the multiplication it always did.

The two sides are not the same block because they do not type a literal the same way. `d * 3`
reads its `3` off the parameter it is passed to, so a count on the right arrives as a `long`
whatever the reader wrote; `3 * d` has nothing to read it off, and a bare literal is an `int`
before the operator is looked at.

### Neg for Duration

```sysl
impl Neg for Duration
```

### Ord for Duration

```sysl
impl Ord for Duration
```

### Ord for Instant

```sysl
impl Ord for Instant
```

### Ord for LocalDate

```sysl
impl Ord for LocalDate
```

### Ord for LocalDateTime

```sysl
impl Ord for LocalDateTime
```

### Ord for LocalTime

```sysl
impl Ord for LocalTime
```

### Ord for Offset

```sysl
impl Ord for Offset
```

### Sub for Duration

```sysl
impl Sub for Duration
```

### Sub for Instant

```sysl
impl Sub[Duration] for Instant
```

### Sub for Instant

```sysl
impl Sub[Instant, Duration] for Instant
```

The difference of two instants is a duration, and it is the operator. An implementation carries
its result as `Out` (`reference/expressions.md § The operand and the result are both trait
arguments`), so one type may subtract two different things and answer with a third: the row above
lands further along the timeline, and this one measures the gap between two points on it. What
tells them apart is the type of the right operand and nothing else, which is what makes both of
them ordinary uses of `-`.
