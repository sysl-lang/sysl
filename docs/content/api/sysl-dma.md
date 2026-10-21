---
title: sysl.dma
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.dma
summary: "Memory a device can reach by address, and the handover that makes it safe to share with one."
requires: "no alloc"
---

A device reads and writes memory by the address *it* sees -- a physical address, or a bus address
where an IOMMU stands between -- in runs that have to be contiguous on its side. A `[]u8` says
none of that: it may be a heap block, a stack array or a page of a window mapped from whatever
frames were free, and nothing in its type says which. So a driver that puts a caller's slice into
a descriptor compiles for every slice and is right for only some of them, and where it is wrong the
device writes to memory that is not the buffer.

**A `Region` is the other kind of buffer: it carries the device's address of its first byte.** It
is made in one place -- `adopt`, called by whatever owns device-reachable memory, usually a
kernel's frame allocator behind a `Source` -- and nowhere else: its fields are private, so no
program builds one from a slice, and `ptr_cast` cannot make one, a `Region` not being a pointer.
A function that hands memory to a device takes a `Region`, and a caller holding an ordinary slice
is refused at the call rather than finding out on hardware.

```
val r = dma.adopt(frame_va, frame_pa, 8192)     // the one trust point
val header = r.part(0, 16)                       // a part is a Region too, and costs nothing
desc.addr = header.bus()                         // the device's address, never computed
```

**A part of a region is a region**, four words and no count, so cutting one costs what cutting a
slice costs and a part crosses a concurrency domain as freely as a `*T` does. **The region does not
own its memory** -- the same bargain `Ring` and `*T` make: whoever adopted it frees it, through the
`Source` it came from, and after every device that was handed it has finished with it.

**The handover is two calls, and they are the cache maintenance.** On a machine whose caches the
device does not see, a write the processor made may still be in a cache when the device reads the
memory, and a line the processor cached before the device wrote may be read in place of what the
device wrote. `to_device` before the device is told about the region and `from_device` after it
says it has finished are what make both right; on a coherent region each is only the barrier.

## Index

[`adopt`](#adopt) [`Region`](#region) [`Source`](#source)

## Functions

### `adopt`

```sysl
adopt(base: *u8, bus: u64, len: usize, coherent: bool = false) -> Region
```

*The one place a device address enters a program**: `len` bytes at `base`, which the device sees
at `bus`. The caller is stating a fact no type can check -- that the run is contiguous on the
device's side and stays where it is while a device holds it -- which is why it is said once, by
whatever owns device memory, rather than at every descriptor.

`coherent` says the device sees the processor's caches (or the memory is mapped uncached), so the
handover is a barrier and nothing more. **Only AArch64 has its maintenance here**: on any other
machine a region that is not coherent is refused, rather than handed over without the maintenance
it needs.

## Types

### `Region`

```sysl
struct Region
    private base: *u8
    private device: u64
    private size: usize
    private coherent: bool
```

Memory a device can reach: `size` bytes at `base` as the processor sees them, which the device
sees at `device`.

| Member | Signature | Description |
|---|---|---|
| `len` | `len(self) -> usize` | How many bytes the region holds. |
| `is_empty` | `is_empty(self) -> bool` |  |
| `bus` | `bus(self) -> u64` | The address the device uses for the region's first byte -- what goes into a descriptor, a queue register or a DMA controller's source. |
| `is_coherent` | `is_coherent(self) -> bool` | Whether the device sees the processor's caches, so the handover needs no maintenance. |
| `bytes` | `bytes(self) -> []u8` | The region as the processor reads and writes it. |
| `part` | `part(self, from: usize, len: usize) -> Region` | `len` bytes of this region from byte `from`. |
| `bus_of` | `bus_of[T](self, p: *T) -> u64` | Where the device sees the `T` at `p`, which has to lie wholly inside the region -- a field of a struct laid over it, an element of a ring in it. |
| `at` | `at[T](self, offset: usize) -> *T` | The `T` laid over the region at byte `offset` -- a queue's shared structure, a descriptor table. |
| `to_device` | `to_device(self)` | Hand the region to the device: whatever the processor wrote reaches memory, and no line of it stays cached to be written back over what the device writes later. |
| `from_device` | `from_device(self)` | Take the region back from the device: whatever the processor reads next comes from memory, not from a line cached while the device was writing. |

## Traits

### `Source`

```sysl
trait Source
    take(*self, len: usize) -> Option[Region]
    give(*self, r: Region)
```

What hands out device-reachable memory -- a kernel's frame allocator, a reserved carve-out, a
user-space driver's pinned pages. A driver written against it allocates its rings and buffers
without knowing which.

| Member | Signature | Description |
|---|---|---|
| `take` | `take(*self, len: usize) -> Option[Region]` | A region of at least `len` bytes, or nothing where there is none. |
| `give` | `give(*self, r: Region)` | A region `take` handed out, every device being finished with it. |
