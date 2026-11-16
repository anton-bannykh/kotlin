# Garbage collector and allocator

Compiled Kotlin code never frees memory. It asks the [runtime](runtime.md) for every object (`AllocInstance`,
`AllocArrayInstance`), and a **garbage collector** (GC) finds the objects the program can no longer reach and gives their
memory back to the **allocator**. Both are replaceable runtime modules; this page describes the defaults: the concurrent
mark-and-sweep collector (CMS) and the "custom" allocator.

One collection with the default collector. Time goes left to right. Neither marking nor sweeping happens during a pause:

```
 Kotlin threads  ═════╡ pause ╞════ running, write barrier on ═══════════════╡ pause ╞═════ running ═════════════════
 GC thread         1  │   2   │  3: mark              4: weak references     │   5   │  6: sweep
 finalizer thread                                                                                     7: finalizers
```

1. **Trigger.** The GC scheduler decides that a collection is due and wakes the GC thread.
2. **Pause: thread roots.** All Kotlin threads are stopped at [safepoints](runtime.md#safepoints). The GC thread reads their
   roots (the shadow stack and thread-local properties of each thread) and turns the write barrier on. Threads resume.
3. **Mark, concurrently.** The GC thread adds the global roots, then follows references from object to object and sets a
   mark on everything it reaches. The program keeps running and keeps changing the object graph meanwhile.
4. **Weak references, concurrently.** Every weak reference whose target was not marked is set to null.
5. **Pause: switch over.** The barrier is turned off and the allocator is told that a sweep begins.
6. **Sweep, concurrently.** Every unmarked object is freed and the marks of the survivors are cleared.
7. **Finalizers.** Dead objects that need cleanup (a `Cleaner`, an attached Objective-C object) were put in a queue by the
   sweep. A separate finalizer thread processes the queue.

## The write barrier

A **barrier** is a small piece of code that runs on every store of a reference into an object or a global. Concurrent
marking needs one because the program can hide an object from the marker: it reads a reference out of an object the marker
has not visited yet, keeps it only in a local variable, and overwrites the field. The marker scanned the stack earlier and
will never see that object, although it is alive.

The barrier used here closes the hole from the other side: before a reference is overwritten, the **old** value is handed to
the marker. Together with "objects allocated during marking are born marked", this means everything that was reachable at
the pause in step 2 survives the collection. Garbage created during the cycle is collected by the next one.

Outside of marking the barrier is one load and a branch that is not taken. It lives inside the runtime functions that
compiled code calls to store references (`UpdateHeapRef` and friends), so the compiler does not generate it itself.

## What kind of collector this is

- **Tracing**: it finds live objects by following references from roots. There is no reference counting, so cycles are not
  a problem. What the roots are is explained in [runtime.md](runtime.md#how-the-gc-finds-references).
- **Non-moving**: an object stays at the same address for its whole life. Pointers held in registers, in the runtime's C++
  code or in foreign code stay valid across a collection and never have to be updated. The cost is that free memory can
  become fragmented.
- **Non-generational**: there is no "young generation". Every collection marks the whole heap.

## When a collection starts

The default scheduler (`adaptive`) triggers a collection in two cases:

- **Heap growth.** Threads report how much they allocate. When the heap approaches a target size, a collection starts. The
  target is recomputed after each collection from the amount of memory that survived (by default, twice that amount).
  If the heap still passes the target before the collection finishes, Kotlin threads are paused until it does.
- **Time.** If no collection happened for a while (10 seconds by default), a timer thread starts one, except when a
  UIKit application is in the background.

A program can also ask explicitly with `kotlin.native.runtime.GC.collect()`.

## The other collectors

Chosen at compile time with the binary option `gc`. All share the cycle above; they differ in what runs during a pause.

| Collector | What is different | Used |
|---|---|---|
| `cms`: concurrent mark and sweep | Described above | Default |
| `pmcs`: parallel mark, concurrent sweep | All marking happens inside the first pause, on several threads: the paused Kotlin threads help. No write barrier | On request |
| `stwms`: stop-the-world mark and sweep | The whole collection up to finalizers is one pause, on the GC thread | On request |
| `noop` | Never collects; memory only grows. Comes with the `manual` scheduler | On request |

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
- **Sweeping is per page.** Walking a page block by block, the sweep rebuilds its free list from the unmarked blocks. A
  thread that needs a page which has not been swept yet in this cycle sweeps it itself.
- **The GC's mark lives next to the object**: the allocator puts one extra word in front of every heap object for the
  collector. → [object-model.md](object-model.md)

## Things to know

- **Not every object is on this heap.** Objects that [escape analysis](optimizations/escape-analysis.md) proves local are
  allocated in the stack frame of the function. Constants such as string literals are **permanent** objects in the binary's
  data. A tag in the object header tells the three kinds apart; the GC never frees the last two.
- **Objective-C and Swift keep Kotlin objects alive through a counter.** A Kotlin object passed to Swift gets a record with
  the number of foreign references to it. While the number is above zero the object is a GC root; at zero the record is
  only a weak reference. → [export.md](export.md)
- **A dead object is not freed at once**, and with a finalizer it may live through one more collection. Never rely on
  the timing of `Cleaner` actions.
- **The compiler flag `-Xgc=cms` selects `pmcs`**, for historical reasons. The binary option `-Xbinary=gc=cms` means CMS.
- **With a sanitizer** the page scheme is switched off by default and every object is allocated separately, because
  sanitizers are not useful with paged allocation.

## Where the code is

- [gc/MainGCThread.hpp](../../kotlin-native/runtime/src/main/cpp/gc/MainGCThread.hpp): the collection cycle shared by all
  collectors; [gc/cms/](../../kotlin-native/runtime/src/gc/cms/cpp) has concurrent marking and the barriers
- [gcScheduler/](../../kotlin-native/runtime/src/main/cpp/gcScheduler): when to collect; defaults are in `GCSchedulerConfig.hpp`
- [alloc/custom/](../../kotlin-native/runtime/src/alloc/custom): the allocator, with its own README
- [mm/ExternalRCRefRegistry.hpp](../../kotlin-native/runtime/src/main/cpp/mm/ExternalRCRefRegistry.hpp): references held by
  foreign code, and weak references
