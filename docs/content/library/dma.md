---
title: The dma module
summary: "`sysl.dma` — `Region`, memory a device can reach by address: it carries the device's address of its first byte, a part of it is a region too, and `to_device`/`from_device` are the cache maintenance a handover needs."
weight: 34
---

**Every declaration in `sysl.dma`, with its signature:** [the generated API page](/api/sysl-dma/#index). This page is the argument — what the module is for, and how its pieces fit; that one is the list.

A device reads and writes memory by the address **it** sees — a physical address, or a bus address
where an IOMMU stands between — in runs that have to be contiguous on its side. A `[]u8` says none of
that. It may be a heap block, a local array on a stack, or a page of a window mapped from whatever
frames were free, and nothing in its type says which; so a driver that puts a caller's slice into a
descriptor compiles for every slice and is right for only some of them, and where it is wrong the
device writes to memory that is not the buffer.

`sysl.dma` is the other kind of buffer. A **`Region`** is a run of memory together with the address
the device uses for its first byte:

```sysl
import sysl.dma
import sysl.slices.as_mut_ptr

var frames: [4096]u8 = [0; 4096]
val r = dma.adopt(as_mut_ptr(frames[..]), 0x4100_0000, 4096)

val header = r.part(0, 16)
val data = r.part(512, 512)

print(r.len(), header.len(), data.len())
print(f"${header.bus()}%x ${data.bus()}%x")
```

```output
4096 16 512
41000000 41000200
```

| | |
|---|---|
| `adopt(base, bus, len, coherent = false)` | the one place a device address enters a program |
| `r.len()`, `r.is_empty()` | how many bytes it holds |
| `r.bus()` | the device's address of the first byte — what goes into a descriptor |
| `r.bytes()` | the region as the processor reads and writes it, a `[]u8` |
| `r.part(from, len)` | a part of it, which is a region too |
| `r.at[T](offset)` | the `T` laid over it at that byte, checked to fit and be aligned |
| `r.bus_of(p)` | where the device sees the `T` at `p`, checked to lie inside |
| `r.to_device()`, `r.from_device()` | the handover, which is the cache maintenance |
| `Source` | what hands regions out: `take(len)` and `give(r)` |

## A device address is stated once, and computed never

**`adopt` is the trust point, and there is only one.** Whoever owns device-reachable memory — a
kernel's frame allocator, a reserved carve-out, a user-space driver's pinned pages — knows the two
addresses and says so there. Everything after it is arithmetic the module does: a part's bus address
moves with its processor address, `bus_of` answers for a field of a structure laid over the region,
and nothing a driver writes ever subtracts a base from a pointer.

A `Region`'s fields are private, so a program cannot build one from a slice it happens to hold:

```sysl
import sysl.dma

var buf: [512]u8 = [0; 512]
val r = dma.Region(&buf[0], 0, 512, false)

print(r.len())
```

```error
the constructor names every field of 'sysl.dma.Region' in order, and 'base' is private to 'library/sysl/dma/dma.sysl', the file that declares it
```

and a function that hands memory to a device takes a `Region`, so the stack buffer that would have
been handed over as a physical address that is not memory at all is refused at the call:

```sysl
import sysl.dma

read_sector(n: u64, into: dma.Region)
    print(n, into.len())

var buf: [512]u8 = [0; 512]
read_sector(1, buf[..])
```

```error
'into' of 'read_sector' is sysl.dma.Region, but []byte was given
```

**The region does not own its memory**, the bargain [`Ring`](/library/ring/) and a `*T` make: whoever
adopted it gives it back, through the `Source` it came from, after every device that was handed it
has finished with it. That is also what makes it cheap — **a region is four words and no count**, so
cutting a part costs what cutting a slice costs, and a region crosses a concurrency domain as freely
as a raw pointer does: a driver's state in a `&sync` box may hold them, and an interrupt handler may
read one.

## A structure laid over a region, and the addresses of its fields

A device's queue is usually a structure the processor and the device share — descriptor tables,
rings, request headers — laid over a run of frames. `at[T]` is that overlay, and it checks the two
things a hand-written `ptr_cast` beside an `@assert` of the size leaves to the reader: that the `T`
fits, and that it is aligned. `bus_of` is the other direction, the device's address of anything
inside:

```sysl
import sysl.dma
import sysl.slices.as_mut_ptr

struct Request
    kind: u32
    reserved: u32
    sector: u64

var frames: [512]u64 = [0; 512]
val r = dma.adopt(ptr_cast(as_mut_ptr(frames[..])), 0x4100_0000, 4096)
val req: *Request = r.at(64)

req.sector = 7

print(f"${r.bus_of(req)}%x ${r.bus_of(&req.sector)}%x")
```

```output
41000040 41000048
```

A pointer that is not inside the region panics rather than answering, because the address it would
have answered is exactly the one a device must never be handed. A part that runs past the end and an
overlay that does not fit or is misaligned panic the same way — the bargain an index past the end of
a slice makes.

## The handover is the cache maintenance

On a machine whose caches the device does not see, two things go wrong without help: a write the
processor made may still be in a cache when the device reads the memory, and a line the processor
cached before the device wrote may be read in place of what the device wrote. **`to_device` before
the device is told about the region and `from_device` after it says it has finished** are what make
both right:

```sysl build=c target=aarch64-freestanding
import sysl.dma

@export("submit")
submit(r: dma.Region, doorbell: *volatile u32) -> u64
    r.to_device()
    *doorbell = 1
    r.bus()

@export("complete")
complete(r: dma.Region) -> u8
    r.from_device()
    r.bytes()[0]
```

On AArch64 each one **cleans and invalidates every data cache line the region touches** (`dc civac`,
stepping by the smallest line `CTR_EL0` reports), then issues `dsb sy`, so the maintenance is
finished before the store that rings the doorbell. One operation serves both directions — cleaned so
the device reads what was written, invalidated so neither a dirty line evicted later nor a clean one
read later stands in for the memory — and that keeps the rule one sentence:

> **While the device holds a region, the processor writes nothing in the cache lines it touches.**

A line is the unit, so two regions sharing a line share the rule: a request header and the status
byte after it may sit in one line, the processor writing neither while the request is in flight.

A region adopted with **`coherent: true`** — the device sees the processor's caches, or the memory is
mapped uncached — has a handover that is the barrier alone. **Only AArch64 has its maintenance in this
module**: on any other machine `adopt` refuses a region that is not coherent rather than handing one
over without the maintenance it needs, and the handover there is `atomic_fence(SeqCst)`.

## Where regions come from

`Source` is what hands them out, so a driver written as a package allocates its rings and buffers
without knowing whose memory it is:

```sysl
import sysl.dma
import sysl.dma.Source
import sysl.slices.as_mut_ptr

struct Arena
    base: *u8
    room: usize
    taken: bool

impl Source for Arena
    take(*self, len: usize) -> Option[dma.Region]
        if self.taken || len > self.room then return None

        self.taken = true
        Some(dma.adopt(self.base, 0x4100_0000, len))

    give(*self, r: dma.Region)
        self.taken = false

queue_memory[S: Source](src: *S) -> Option[dma.Region] = src.take(2048)

var store: [4096]u8 = [0; 4096]
var arena = Arena(as_mut_ptr(store[..]), 4096, false)

print(queue_memory(&arena).is_some(), queue_memory(&arena).is_some())
```

```output
true false
```

A kernel's version adopts frames from its frame allocator, each at its linear-map address and its
physical one; a hosted user-space driver's adopts pages it pinned and asked the IOMMU about. Either
way the two addresses are known where the memory is made, and nowhere else does a program need them.
