[Kotlin/Native guide](../README.md) › [runtime](README.md) › gc-and-allocator

# Garbage collector and allocator

*Skip this page if you know the phases of the concurrent mark-and-sweep cycle, the write barrier and the paged allocator.*

Compiled Kotlin code never frees memory. It asks the [runtime](README.md) for every object, and a **garbage collector**
(GC) finds the objects the program can no longer reach and gives their memory back to the **allocator**. Both are
replaceable [runtime modules](README.md#how-it-is-shipped); this page describes the defaults: the concurrent
[mark-and-sweep][marksweep] collector (CMS) and the "custom" allocator.

One collection with the default collector. Time goes left to right. Neither marking nor sweeping happens during a pause:

```
 Kotlin threads  ═════╡ pause ╞════ running, write barrier on ═══════════════╡ pause ╞═════ running ═════════════════
 GC thread         1  │   2   │  3: mark              4: weak references     │   5   │  6: sweep
 finalizer thread                                                                                     7: finalizers
```

1. **Trigger.** The GC scheduler decides that a collection is due and wakes the GC thread.
2. **Pause: thread roots.** All Kotlin threads are stopped at [safepoints](README.md#safepoints). The GC thread reads their
   roots (the shadow stack and thread-local properties of each thread) and turns the write barrier on. Threads resume.
3. **Mark, concurrently.** The GC thread adds the global roots, then follows references from object to object and sets a
   mark on everything it reaches. The program keeps running and keeps changing the object graph meanwhile.
4. **Weak references, concurrently.** Every [weak reference][weakref] whose target was not marked is set to null.
5. **Pause: switch over.** The barrier is turned off and the allocator is told that a sweep begins.
6. **Sweep, concurrently.** Every unmarked object is freed and the marks of the survivors are cleared.
7. **Finalizers.** Dead objects that need cleanup (a [`Cleaner`][cleaner], an attached Objective-C object) were put in a
   queue by the sweep. A separate finalizer thread processes the queue.

## The write barrier

A [**barrier**][barrier] is a small piece of code that runs on every store of a reference into an object or a global.
Concurrent marking needs one because the program can hide an object from the marker: it reads a reference out of an object
the marker has not visited yet, keeps it only in a local variable, and overwrites the field. The marker scanned the stack
earlier and will never see that object, although it is alive.

So before a reference is overwritten, the barrier hands the **old** value to the marker. Together with "objects allocated
during marking are born marked", this means everything that was reachable at the pause in step 2 survives the collection.
Garbage created during the cycle is collected by the next one.

Outside of marking the barrier is one load and a branch that is not taken. It lives inside the runtime functions that
compiled code calls to store references (`UpdateHeapRef` and friends).

## What kind of collector this is

- [**Tracing**][tracing]: it finds live objects by following references from [roots](README.md#how-the-gc-finds-references).
  There is no [reference counting][refcount], so cycles are not a problem.
- [**Non-moving**][nonmoving]: an object stays at the same address for its whole life, so pointers held in registers, in the
  runtime's C++ code or in foreign code stay valid across a collection. The cost is that free memory can become fragmented.
- **Non-generational**: there is no "young generation". Every collection marks the whole heap.

## When a collection starts

The default scheduler (`adaptive`) triggers a collection in two cases:

- **Heap growth.** Threads report how much they allocate. When the heap approaches a target size, a collection starts. The
  target is recomputed after each collection from the amount of memory that survived (by default, twice that amount).
  If the heap still passes the target before the collection finishes, Kotlin threads are paused until it does.
- **Time.** If no collection happened for a while (10 seconds by default), a timer thread starts one.

A program can also ask explicitly with [`kotlin.native.runtime.GC.collect()`][gcapi].

## The other collectors

Chosen at compile time with the [binary option][binopts] `gc`. All share the cycle above; they differ in what runs during a
pause.

| Collector | What is different |
|---|---|
| `cms`: concurrent mark and sweep | The default, described above |
| `pmcs`: parallel mark, concurrent sweep | All marking happens inside the first pause, on several threads: the paused Kotlin threads help. No write barrier |
| `stwms`: stop-the-world mark and sweep | The whole collection up to finalizers is one pause, on the GC thread |
| `noop` | Never collects; memory only grows. Comes with the `manual` scheduler |

## The allocator

The allocator gets large **pages** from the operating system and cuts them into **cells** of 8 bytes. A page serves one
kind of allocation:

```
 small objects (up to 1 KiB): one page per size, so every block in it has the same size
 ┌────────┬─────┬─────┬─────┬─────┬─────┬─────┬─────┬───┐
 │ header │ obj │free │ obj │ obj │free │free │ obj │...│   free space is a linked list inside the page:
 └────────┴─────┴──┬──┴─────┴─────┴──▲──┴─────┴─────┴───┘   allocating takes the next free block
                   └─────────────────┘
 medium objects: one page holds blocks of different sizes
 ┌────────┬───────────┬──────┬───────────────────┬──────┐
 │ header │ obj       │ obj  │ free              │ obj  │    allocating searches for a free block that is big enough
 └────────┴───────────┴──────┴───────────────────┴──────┘
 large objects (bigger than such a page): each gets a page of its own, returned as a whole when the object dies
```

- **Allocation takes no lock.** Each thread owns one current page per size, and only that thread allocates in it. Shared
  state is touched only when the thread needs another page.
- **Sweeping is per page.** Walking a page block by block, the sweep rebuilds its [free list][freelist] from the unmarked
  blocks. A thread that needs a page which has not been swept yet in this cycle sweeps it itself.
- **The GC's mark lives next to the object**: the allocator puts one extra word in front of every heap object for the
  collector. → [object-model.md](object-model.md)

## Things to know

- **Not every object is on this heap.** Stack objects (→ [escape analysis](../compiler/optimizations/escape-analysis.md))
  and permanent objects in the binary's data are told apart by a tag in the header and are never freed by the GC.
  → [object-model.md](object-model.md#objects-that-are-constants)
- **One kind of record stands behind every reference from outside the heap.** An Objective-C or Swift wrapper, a
  `StableRef` or C-export handle, a weak reference and a `Cleaner`'s action each hold an `ExternalRCRef`: a pointer to the
  object plus a counter. While the counter is above zero the object is a GC root; at zero the record is only a weak
  reference, which the GC clears when the object dies. → [export.md](../interop/export.md#how-objects-cross-the-boundary)
- **An Objective-C object held by Kotlin is released late.** The collector must first find its Kotlin wrapper dead; the
  release then happens in step 7, on the finalizer thread. If the object was attached to its wrapper on the main thread,
  the release is posted to the main run loop instead, provided that loop is running (binary option `objcDisposeOnMain`,
  on by default).
- **Running out of memory aborts the process.** The allocator prints `Out of memory trying to allocate N bytes. Aborting.`
  and calls `abort()`. `OutOfMemoryError` exists, but only a few size checks throw it (a string or a collection capacity
  that would be too large), never the allocator.
- **A dead object is not freed at once**, and with a finalizer it may live through one more collection. Never rely on
  the timing of `Cleaner` actions.
- **The compiler flag `-Xgc=cms` selects `pmcs`**, for historical reasons. The binary option `-Xbinary=gc=cms` means CMS.

## Where the code is

- [gc/MainGCThread.hpp](../../../kotlin-native/runtime/src/main/cpp/gc/MainGCThread.hpp): the collection cycle shared by all
  collectors; [gc/cms/](../../../kotlin-native/runtime/src/gc/cms/cpp) has concurrent marking and the barriers
- [gcScheduler/](../../../kotlin-native/runtime/src/main/cpp/gcScheduler): when to collect; defaults are in `GCSchedulerConfig.hpp`
- [alloc/custom/](../../../kotlin-native/runtime/src/alloc/custom): the allocator, with its own README

## Further reading

- [Kotlin/Native memory management](https://kotlinlang.org/docs/native-memory-manager.html): this collector as its users
  see it, with the options for adjusting and monitoring it
- [Integration with Swift/Objective-C ARC](https://kotlinlang.org/docs/native-arc-integration.html): how the collector
  lives together with reference counting
- [Tracing garbage collection](https://en.wikipedia.org/wiki/Tracing_garbage_collection#Tri-color_marking) on Wikipedia:
  mark-and-sweep, tri-color marking, and stop-the-world, incremental and concurrent collectors
- [The Garbage Collection Handbook](https://gchandbook.org/): the standard textbook on collectors, barriers and allocators

[marksweep]: https://en.wikipedia.org/wiki/Tracing_garbage_collection#Naive_mark-and-sweep
[weakref]: https://en.wikipedia.org/wiki/Weak_reference
[cleaner]: https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.native.ref/-cleaner/
[barrier]: https://en.wikipedia.org/wiki/Write_barrier
[tracing]: https://en.wikipedia.org/wiki/Tracing_garbage_collection
[refcount]: https://en.wikipedia.org/wiki/Reference_counting
[nonmoving]: https://en.wikipedia.org/wiki/Tracing_garbage_collection#Moving_vs._non-moving
[gcapi]: https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.native.runtime/-g-c/
[binopts]: https://kotlinlang.org/docs/native-binary-options.html
[freelist]: https://en.wikipedia.org/wiki/Free_list
