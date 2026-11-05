---
title: sysl.time
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.time
summary: "Points on the timeline, lengths of it, and the calendar that turns one into a date."
---

`Instant` and `Duration` are the two quantities everything else here is written in terms of, with
`5.ms` and `5.hours` available on any integer. Above them sit the civil types — `Date`, `Time`,
`DateTime` and `Offset` — with `Period` for a length of calendar, `Zone` and its `resolve` for a
zone whose clocks move, `Zoned` for a reading in one, and the ISO 8601 renderers and parsers.
The makers are the lower-case words: `date(2026, 3, 8)`, `time(9, 30, 0)`, `date_time(…)`, `utc`.

**It reads no clock itself.** `now` and `monotonic` are declared here and *supplied by the
linker*, so a program on a board provides its own and a hosted one gets `sysl.posix.time`'s. That
is what keeps a calendar usable on a target with no operating system to ask, and it is why the two
are separate modules.

## Index

[`us_per_day`](#us_per_day) [`us_per_hour`](#us_per_hour) [`us_per_milli`](#us_per_milli) [`us_per_minute`](#us_per_minute) [`us_per_second`](#us_per_second) [`utc`](#utc) [`at_offset`](#at_offset) [`checked_date`](#checked_date) [`civil_from_days`](#civil_from_days) [`date`](#date) [`date_text`](#date_text) [`date_time`](#date_time) [`datetime_text`](#datetime_text) [`day_of`](#day_of) [`day_of_year`](#day_of_year) [`days`](#days) [`days_from_civil`](#days_from_civil) [`days_in_month`](#days_in_month) [`duration_text`](#duration_text) [`from_offset`](#from_offset) [`hour_of`](#hour_of) [`hours`](#hours) [`instant_text`](#instant_text) [`leap_year`](#leap_year) [`micros`](#micros) [`millis`](#millis) [`minute_of`](#minute_of) [`minutes`](#minutes) [`monotonic`](#monotonic) [`month_name`](#month_name) [`month_of`](#month_of) [`months`](#months) [`now`](#now) [`odd_hours`](#odd_hours) [`odd_minutes`](#odd_minutes) [`odd_seconds`](#odd_seconds) [`odd_us`](#odd_us) [`offset_text`](#offset_text) [`on_or_after`](#on_or_after) [`parse_date`](#parse_date) [`parse_datetime`](#parse_datetime) [`parse_offset`](#parse_offset) [`parse_time`](#parse_time) [`parse_timestamp`](#parse_timestamp) [`period_text`](#period_text) [`plus_days`](#plus_days) [`plus_months`](#plus_months) [`plus_years`](#plus_years) [`second_of`](#second_of) [`seconds`](#seconds) [`since`](#since) [`sleep`](#sleep) [`time`](#time) [`time_text`](#time_text) [`timestamp_text`](#timestamp_text) [`us_of`](#us_of) [`wall_of`](#wall_of) [`wall_us`](#wall_us) [`weekday`](#weekday) [`weekday_name`](#weekday_name) [`whole_days`](#whole_days) [`whole_hours`](#whole_hours) [`whole_micros`](#whole_micros) [`whole_millis`](#whole_millis) [`whole_minutes`](#whole_minutes) [`whole_seconds`](#whole_seconds) [`year_of`](#year_of) [`years`](#years) [`zoned_text`](#zoned_text) [`Date`](#date-1) [`DateTime`](#datetime) [`Duration`](#duration) [`Instant`](#instant) [`Offset`](#offset) [`Period`](#period) [`Resolution`](#resolution) [`Time`](#time-1) [`TimeParseError`](#timeparseerror) [`Weekday`](#weekday-1) [`YearMonthDay`](#yearmonthday) [`Zone`](#zone) [`Zoned`](#zoned) [`DurationUnits`](#durationunits) [Add for Date](#add-for-date) [Add for DateTime](#add-for-datetime) [Add for Duration](#add-for-duration) [Add for Instant](#add-for-instant) [Add for Period](#add-for-period) [Add for Zoned](#add-for-zoned) [Add for Zoned](#add-for-zoned-1) [DateTime](#datetime-1) [Display for Date](#display-for-date) [Display for DateTime](#display-for-datetime) [Display for Duration](#display-for-duration) [Display for Instant](#display-for-instant) [Display for Offset](#display-for-offset) [Display for Period](#display-for-period) [Display for Resolution](#display-for-resolution) [Display for Time](#display-for-time) [Display for TimeParseError](#display-for-timeparseerror) [Display for Zone](#display-for-zone) [Display for Zoned](#display-for-zoned) [DurationUnits for T](#durationunits-for-t) [Eq for Date](#eq-for-date) [Eq for DateTime](#eq-for-datetime) [Eq for Duration](#eq-for-duration) [Eq for Instant](#eq-for-instant) [Eq for Offset](#eq-for-offset) [Eq for Period](#eq-for-period) [Eq for Time](#eq-for-time) [Eq for Zone](#eq-for-zone) [Eq for Zoned](#eq-for-zoned) [Instant](#instant-1) [Mul for Duration](#mul-for-duration) [Mul for N](#mul-for-n) [Mul for Period](#mul-for-period) [Neg for Duration](#neg-for-duration) [Neg for Period](#neg-for-period) [Ord for Date](#ord-for-date) [Ord for DateTime](#ord-for-datetime) [Ord for Duration](#ord-for-duration) [Ord for Instant](#ord-for-instant) [Ord for Offset](#ord-for-offset) [Ord for Time](#ord-for-time) [Ord for Zoned](#ord-for-zoned) [Sub for Date](#sub-for-date) [Sub for DateTime](#sub-for-datetime) [Sub for Duration](#sub-for-duration) [Sub for Instant](#sub-for-instant) [Sub for Instant](#sub-for-instant-1) [Sub for Period](#sub-for-period) [Sub for Zoned](#sub-for-zoned) [Sub for Zoned](#sub-for-zoned-1) [Sub for Zoned](#sub-for-zoned-2) [Zone](#zone-1) [Zoned](#zoned-1)

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

## Values

### `utc`

```sysl
val utc: Zone = FixedZone("UTC", Offset(0))
```

The one zone that cannot fail to be found, which is why it is a value rather than a call.

## Functions

### `at_offset`

```sysl
at_offset(t: Instant, o: Offset) -> DateTime
```

An instant read against a wall set a fixed distance from UTC, and back. **Both directions are
total**, which is what separates a fixed offset from a zone: a zone whose clocks move answers the
second question with none or two, and this pair never does, because an offset is a number rather
than a rule with a history.

A timestamp on a wire, in a log or in a database column carries the offset that was in force when
it was written — the sender resolved the zone already, and what arrived is the record of that
decision. Reading one back needs no table, no update cadence and no filesystem.

### `checked_date`

```sysl
checked_date(y: int, m: int, d: int) -> Option[Date]
```

A date only if the calendar has one: `None` for a thirtieth of February or a thirteenth month.

What asks this one is a reader: three numbers that a person wrote, scanned out of text by
something that is not `parse_date`, and a format that says a date must exist has to be able to
refuse `2026-02-30` without a fault.

The year is unbounded on purpose: the proleptic Gregorian calendar is, and `days_from_civil` is
its own inverse over the whole `int` range.

### `civil_from_days`

```sysl
civil_from_days(d: Date) -> YearMonthDay
```

### `date`

```sysl
date(y: int, m: int, d: int) -> Date
```

The date with that year, month and day -- **and a fault where the calendar has no such date.**

Three numbers written into a program are the program's own words, so a thirtieth of February
here is a defect in it, reported at the line that wrote it (`date` is `@track_caller`) rather than
quietly rolled over into March. Text arriving from outside is the other channel: `parse_date`
answers a `Result`, and `checked_date` answers an `Option` for three numbers a program has read
rather than written.

The walk that has already established its dates are real -- a loop over the months, a calendar
grid -- uses `days_from_civil`, which is pure arithmetic and pays for no range test per step.

### `date_text`

```sysl
date_text(d: Date) -> string
```

### `date_time`

```sysl
date_time(y: int, mo: int, d: int, h: int, mi: int, s: int) -> DateTime
```

A wall clock reading, made of a `date` and a `time` and refused where either would be.

### `datetime_text`

```sysl
datetime_text(ldt: DateTime) -> string
```

### `day_of`

```sysl
day_of(d: Date) -> int
```

### `day_of_year`

```sysl
day_of_year(d: Date) -> int
```

The day of the year, from 1 on the first of January. It is a subtraction rather than a sum over
the months, because the day numbers are already what the subtraction needs.

### `days`

```sysl
days(n: long) -> Duration
```

### `days_from_civil`

```sysl
days_from_civil(c: YearMonthDay) -> Date
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

### `duration_text`

```sysl
duration_text(d: Duration) -> string
```

A length of time as a person reads one off a stopwatch: `1.234567s`, `12ms`, `1.5ms`, `7µs`,
`-3s`, and `0s` for none.

**The unit is the largest of seconds, milliseconds and microseconds the length reaches**, and the
digits below it are written only as far as the value has any -- the rule `time_text` keeps for the
microseconds of a time of day. So nothing the value says is dropped and nothing it does not say
is padded on. Seconds are the largest unit because a duration is a measurement rather than a
calendar reading: `5400s` is what a timer says, and `1h30m` is a rendering a caller can build out of
`whole_hours` and `odd_minutes` where it wants one.

### `from_offset`

```sysl
from_offset(ldt: DateTime, o: Offset) -> Instant
```

### `hour_of`

```sysl
hour_of(t: Time) -> int
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
minute_of(t: Time) -> int
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
month_of(d: Date) -> int
```

### `months`

```sysl
months(n: int) -> Period
```

A period of so many months.

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
on_or_after(d: Date, w: Weekday) -> Date
```

The first date on or after `d` falling on the given weekday, which is what "every Tuesday" means
once a rule has been given a starting point.

### `parse_date`

```sysl
parse_date(s: string) -> Result[Date, TimeParseError]
```

`YYYY-MM-DD`. The year is four digits, which is what ISO 8601 says and what `date_text` writes;
a year outside 0..9999 has no ISO spelling without a sign prefix, and that extension is not here.

### `parse_datetime`

```sysl
parse_datetime(s: string) -> Result[DateTime, TimeParseError]
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
parse_time(s: string) -> Result[Time, TimeParseError]
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

### `period_text`

```sysl
period_text(p: Period) -> string
```

`1y 2mo 3d`, leaving out what is zero, and `0d` for a period of nothing. A year is shown wherever
twelve months make one, which is a rendering choice and not a conversion: the value holds months.

### `plus_days`

```sysl
plus_days(ldt: DateTime, n: int) -> DateTime
```

Adding days to a wall clock reading is **not** adding a length of time to it. Seven days later is
the same clock face seven rows down the calendar, whatever the timeline did in between — and
across a change of a zone's clocks the two answers differ by an hour. Both are right; they answer
different questions, which is why they are spelled differently.

### `plus_months`

```sysl
plus_months(d: Date, n: int) -> Date
```

Months are added by the calendar rather than by counting days, so the day of the month is clamped
where the target month is shorter: the 31st of January plus one month is the 28th or 29th of
February. Every library that offers this makes that choice, and clamping is the one that keeps
"the last day of the month" landing on a last day.

### `plus_years`

```sysl
plus_years(d: Date, n: int) -> Date
```

### `second_of`

```sysl
second_of(t: Time) -> int
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

### `sleep`

```sysl
sleep(d: Duration)
```

Wait for `d`, however many attempts that takes.

**An interrupted wait carries on**: whatever answers the seam reports the time still owed, and this
asks again for exactly that until nothing is left, so a signal arriving on a host makes the wait no
shorter. It answers nothing, because it returns when the time has passed.

    sleep(millis(50))

A duration of zero or less returns at once without asking anything. `sysl.posix.time.sleep` is this
same function, and `sysl.posix.time.nanosleep` is the one to reach for where a wait shorter than a
microsecond, or the interruption itself, matters.

### `time`

```sysl
time(h: int, m: int, s: int) -> Time
```

The time of day with that hour, minute and second, and a fault where a clock never shows it --
an hour past 23, a minute or a second past 59. The same two channels as `date`: a reader of text
uses `parse_time`, which answers a `Result`.

### `time_text`

```sysl
time_text(t: Time) -> string
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
us_of(t: Time) -> long
```

### `wall_of`

```sysl
wall_of(us: long) -> DateTime
```

### `wall_us`

```sysl
wall_us(ldt: DateTime) -> long
```

A wall clock reading as a single count, measured from the same origin as an `Instant` but **not**
an instant: it is what the count would be if the zone's offset happened to be zero. A conversion
through a zone starts here and then asks what the offset actually is.

### `weekday`

```sysl
weekday(d: Date) -> Weekday
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
year_of(d: Date) -> int
```

### `years`

```sysl
years(n: int) -> Period
```

A period of so many years, which is twelve months each.

### `zoned_text`

```sysl
zoned_text(z: Zoned) -> string
```

`2026-03-08T09:00:00-04:00[America/Toronto]`: an RFC 3339 timestamp with the zone's name after it,
the shape `java.time` and the Temporal proposal both write.

## Types

### `Date`

```sysl
struct Date
    day: int
```

Days since 1970-01-01. Signed, so dates before the epoch are ordinary values rather than a special
case — the algorithm below never branches on the sign.

### `DateTime`

```sysl
struct DateTime
    date: Date
    time: Time
```

A wall clock reading. Two independent counts rather than one, because they are added to and
compared far more often than they are combined.

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

### `Offset`

```sysl
struct Offset
    minutes: int
```

How far a zone's wall clock is set from UTC, in whole minutes. Minutes rather than hours because
India is at +05:30 and Nepal at +05:45, and a library that assumes whole hours works everywhere
its author has lived.

### `Period`

```sysl
struct Period
    months: int
    days: int
```

A length of calendar: so many months and so many days.

**This is the other length, and it is why there are two.** A `Duration` is exact timeline -- so
many microseconds, whatever the calendar says -- and a `Period` is a step on the calendar, whose
length on the timeline depends on where it is taken. A month is 28 to 31 days and a day is 23 to 25
hours across a change of a zone's clocks, so neither converts to the other, and a `Period` is added
only to something that has a calendar: a `Date`, a `DateTime` or a `Zoned`, never an `Instant`.

**Months and days are two scales and never convert.** `months(1)` holds no days and
`Period(0, 30)` no months, and the two are not equal. A year is twelve months, which is exact, so
`years` is a maker of months rather than a third field.

Adding one applies the months first and the days after, which is the order every calendar library
uses: the 31st of January plus one month and one day is the 1st of March, by way of the 28th of
February. The months clamp to the end of a shorter month (`plus_months`).

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
of the gap, clamp it to the transition, or refuse it -- and `DateTime.at_earlier` and `at_later`
take the first, which is the one most libraries choose.

### `Time`

```sysl
struct Time
    us: long
```

Microseconds since midnight, from zero up to but not including a whole day.

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

### `YearMonthDay`

```sysl
struct YearMonthDay
    year: int
    month: int
    day: int
```

Proleptic Gregorian, which means the Gregorian rules run backwards through the years before
anybody agreed to them. That is a decision and not an oversight: the alternative is a calendar
with a ten-day hole in it whose position depends on which country you ask.

### `Zone`

```sysl
enum Zone
    FixedZone(name: string, offset: Offset)
    RuledZone(name: string, rule: &Fn(Instant) -> Offset)
```

A zone: a name, and the offset its clocks were set to at any instant.

**A fixed zone is a number and a ruled zone is a rule.** `utc` is the first kind, and so is a zone
named by its offset (`+05:30`). A zone whose clocks move holds the rule as a function, which is
what keeps this file free of a capability: a table decoded from a TZif file
(`sysl.posix.time.zone`), the host's own `localtime_r` (`sysl.posix.time.local_zone`), a package
talking to an RTC chip and a rule written out by hand all answer the one question, and all of them
are a `RuledZone`.

    val eastern = RuledZone("Eastern", t -> if t < switch then Offset(-300) else Offset(-240))

### `Zoned`

```sysl
struct Zoned
    date_time: DateTime
    offset: Offset
    zone: Zone
```

A wall reading in a zone: the reading, the offset that was in force, and the zone.

**The offset is held as well as the zone because the reading alone is not enough.** 01:30 on the
1st of November in Toronto happens twice, an hour apart, and only the offset says which of the two
this is. So a `Zoned` names one instant always, and `instant()` answers it.

Moving one by a `Duration` moves the timeline and moving it by a `Period` moves the calendar, and
across a change of the zone's clocks the two differ: a day after nine on a Saturday morning is nine
on Sunday morning, and twenty-four hours after it is ten. Both are right, and the program says
which it meant.

**`==` needs the same instant and the same zone, where `<` compares only the instant.** Nine in the
morning in Toronto and ten in the evening in Tokyo are one instant and two readings, so neither is
before the other and they are not equal.

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

### Add for Date

```sysl
impl Add[Period] for Date
```

A date moved along the calendar: the months first, clamped to the end of a shorter month, then the
days. The 31st of January plus a month is the last day of February, in a leap year or not.

### Add for DateTime

```sysl
impl Add[Period] for DateTime
```

A wall reading moved along the calendar: the date moves and the clock face stays where it was.

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

### Add for Period

```sysl
impl Add for Period
```

### Add for Zoned

```sysl
impl Add[Duration] for Zoned
```

A reading moved along the timeline: so many microseconds later, read in the same zone.

### Add for Zoned

```sysl
impl Add[Period] for Zoned
```

A reading moved along the calendar: the wall clock moves by the period and is read in the same zone
again. **The clock face is kept across a change of the zone's clocks**, which is the difference from
adding a `Duration`. Where the new reading happens twice, the offset it already had is kept if it
is one of the two and the earlier taken if not; where it never happens, it is pushed forward by
the size of the gap. Those are `java.time`'s rules, and the reasons for them are the same: a
reading that does not have to change should not.

### DateTime

```sysl
impl DateTime
```

| Member | Signature | Description |
|---|---|---|
| `at` | `at(self, z: Zone) -> Result[Zoned, Resolution]` | This reading in `z`, where the zone shows it exactly once -- and the `Resolution` otherwise, which is `Ambiguous` in the hour the clocks repeat and `Gap` in the hour they skip. |
| `at_earlier` | `at_earlier(self, z: Zone) -> Zoned` | This reading in `z`, taking the earlier instant where the zone shows it twice and pushing it forward by the size of the gap where the zone skips it: 02:30 in an hour that never happened is 03:30 in the hour after. |
| `at_later` | `at_later(self, z: Zone) -> Zoned` | As `at_earlier`, but taking the later instant where the zone shows the reading twice. |

### Display for Date

```sysl
impl Display for Date
```

### Display for DateTime

```sysl
impl Display for DateTime
```

### Display for Duration

```sysl
impl Display for Duration
```

### Display for Instant

```sysl
impl Display for Instant
```

### Display for Offset

```sysl
impl Display for Offset
```

### Display for Period

```sysl
impl Display for Period
```

### Display for Resolution

```sysl
impl Display for Resolution
```

The rendering, so that a resolution can be reported without matching on it.

### Display for Time

```sysl
impl Display for Time
```

### Display for TimeParseError

```sysl
impl Display for TimeParseError
```

### Display for Zone

```sysl
impl Display for Zone
```

### Display for Zoned

```sysl
impl Display for Zoned
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

### Eq for Date

```sysl
impl Eq for Date
```

### Eq for DateTime

```sysl
impl Eq for DateTime
```

### Eq for Duration

```sysl
impl Eq for Duration
```

### Eq for Instant

```sysl
impl Eq for Instant
```

### Eq for Offset

```sysl
impl Eq for Offset
```

### Eq for Period

```sysl
impl Eq for Period
```

### Eq for Time

```sysl
impl Eq for Time
```

### Eq for Zone

```sysl
impl Eq for Zone
```

Two zones are the same zone where they have the same name; a rule cannot be compared.

### Eq for Zoned

```sysl
impl Eq for Zoned
```

### Instant

```sysl
impl Instant
```

| Member | Signature | Description |
|---|---|---|
| `at` | `at(self, z: Zone) -> Zoned` | This instant as a wall reading in `z`, which always exists and is always one reading. |

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

### Mul for Period

```sysl
impl Mul[int] for Period
```

### Neg for Duration

```sysl
impl Neg for Duration
```

### Neg for Period

```sysl
impl Neg for Period
```

### Ord for Date

```sysl
impl Ord for Date
```

### Ord for DateTime

```sysl
impl Ord for DateTime
```

### Ord for Duration

```sysl
impl Ord for Duration
```

### Ord for Instant

```sysl
impl Ord for Instant
```

### Ord for Offset

```sysl
impl Ord for Offset
```

### Ord for Time

```sysl
impl Ord for Time
```

### Ord for Zoned

```sysl
impl Ord for Zoned
```

### Sub for Date

```sysl
impl Sub[Period] for Date
```

### Sub for DateTime

```sysl
impl Sub[Period] for DateTime
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

### Sub for Period

```sysl
impl Sub for Period
```

### Sub for Zoned

```sysl
impl Sub[Duration] for Zoned
```

### Sub for Zoned

```sysl
impl Sub[Period] for Zoned
```

### Sub for Zoned

```sysl
impl Sub[Zoned, Duration] for Zoned
```

How much timeline lies between two readings, whatever zones they were read in.

### Zone

```sysl
impl Zone
```

| Member | Signature | Description |
|---|---|---|
| `name` | `name(self) -> string` | The zone's name: `UTC`, `America/Toronto`, `+05:30`. |
| `offset_at` | `offset_at(self, t: Instant) -> Offset` | How far this zone's clocks were set from UTC at that instant. |
| `resolve` | `resolve(self, dt: DateTime) -> Resolution` | A wall clock reading resolved against this zone: one instant, two, or none. |

### Zoned

```sysl
impl Zoned
```

| Member | Signature | Description |
|---|---|---|
| `instant` | `instant(self) -> Instant` | The point on the timeline this reading names. |
| `at` | `at(self, z: Zone) -> Zoned` | The same instant, read in another zone. |
