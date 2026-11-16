# The runtime

*Skip this page if you know the shadow stack, safepoints, the two thread states and how a Kotlin program starts.*

Kotlin/Native has no virtual machine. What a VM would do while the program runs (allocate and collect memory, keep track of
threads, check types, throw exceptions, start the program) is done by the **runtime**: a C++ library that is linked into
every binary. Compiled Kotlin code calls it constantly.

## How it is shipped

The runtime is a set of LLVM bitcode files in the distribution (`konan/targets/<target>/native/`): `runtime.bc` with the
core, and one file for each **module** that has several interchangeable implementations.

| Module | Implementations (one is linked) |
|---|---|
| garbage collector | `concurrent_ms_gc.bc` (default), `pmcs_gc.bc`, `same_thread_ms_gc.bc`, `noop_gc.bc` |
| GC scheduler (when to collect) | `adaptive_gcScheduler.bc` (default), `manual_gcScheduler.bc`, `aggressive_gcScheduler.bc` |
| allocator | `custom_alloc.bc` (default), `legacy_alloc.bc` |

The compiler chooses the files from the binary options (for example `-Xbinary=gc=noop`), the target (`objc.bc` on Apple
targets) and the kind of output (`launcher.bc` for an executable), and merges them into the program's LLVM module.
→ [codegen.md](../compiler/codegen.md#after-code-generation)

## Startup and shutdown

In an executable, C `main` is the launcher's `Konan_main` (the compiler adds `main` as an alias for it). It initializes the
runtime, wraps the command-line arguments into a Kotlin `Array<String>` and calls `Konan_start`. That function is generated
by the compiler: it calls the Kotlin `main` inside a `try`/`catch` that reports an uncaught exception and terminates
(→ [exceptions.md](../features/exceptions.md#an-exception-nobody-catches)).

Runtime initialization happens **per thread**, the first time the thread needs the runtime. The first thread also creates
the process-wide state (heap, GC thread). A library has no launcher: the functions it exports to C or Objective-C call
`Kotlin_initRuntimeIfNeeded()` on entry, so whichever thread calls in first initializes the runtime.

There is almost no shutdown: when `main` returns, the process exits without a last collection. Top-level properties are
not initialized at startup. → [initialization.md](../features/initialization.md)

## Threads and their two states

Every thread that runs Kotlin code is **registered**: the runtime keeps a record for it (`ThreadData`) with everything the
GC needs to know about that thread. The record is removed when the thread exits.

The GC sometimes has to pause all Kotlin threads, for example to look at their stacks. It cannot stop a thread from the
outside; it sets a flag and waits until each thread notices it at a safepoint (below). A thread blocked in `read()` or
`sleep()` would not notice for a long time. So every registered thread is in one of two states:

- **runnable**: running Kotlin code. It may touch Kotlin objects, and it must reach a safepoint regularly.
- **native**: running foreign code, or blocked. It promises not to touch Kotlin objects, so the GC does not wait for it.

The compiler switches the state around every call to a C or Objective-C function. This is `usleep(1000u)` called through
[cinterop](../interop/cinterop.md), in a debug build (optimized builds call the same functions without the `_debug` suffix):

```llvm
  call void @Kotlin_mm_switchThreadStateNative_debug()      ; "I am leaving Kotlin code: the GC need not wait for me"
  %3 = invoke i32 @..._knbridge1(i32 1000)                  ; a small generated C function that calls usleep
          to label %call_success unwind label %filteringExceptionHandler

call_success:
  call void @Kotlin_mm_switchThreadStateRunnable_debug()    ; back in Kotlin; if a GC pause is in progress, wait here
```

When foreign code calls into Kotlin (a callback, an exported function), the thread becomes runnable on entry and native
again on return.

## Safepoints

A **safepoint** is a place in compiled code where the thread asks "does the runtime want something from me?". The compiler
puts one at the start of every function and in the body of every loop, so a runnable thread reaches one after a bounded
amount of work. In LLVM IR they are the calls to `Kotlin_mm_safePointFunctionPrologue` and
`Kotlin_mm_safePointWhileLoopBody`.

Normally a safepoint costs one load of a global and a branch that is not taken. When the GC has requested a pause, the
thread stops in the safepoint until the GC lets it go. Switching from native to runnable includes the same check.

## How the GC finds references

The GC starts from **roots**: references that are not inside heap objects. It has to know where every one of them is.

- **Globals.** The compiler-generated initializer of each file registers the address of every top-level property that holds
  a reference. `@ThreadLocal` properties live in a per-thread table instead.
- **Local variables.** LLVM knows nothing about garbage collection, so the compiler cannot ask it where references are in
  registers or on the machine stack. Instead each function keeps a copy of every reference it holds in a block of stack
  slots, and links that block into a per-thread list with `EnterFrame` and unlinks it with `LeaveFrame`. This list is the
  **shadow stack**.
- **References held by foreign code**, such as a Kotlin object retained by Swift. → [gc-and-allocator.md](gc-and-allocator.md)

The shadow stack while `main` is inside `build()`, a function with two local objects:

```
 ┌──────────────────────┐◄─ the thread's "current frame" pointer. This block is in build()'s stack frame: `alloca ptr, i32 4`
 │ previous ────────────┼─┐
 │ parameters=0 count=4 │ │   count includes the two header slots
 │ slot: a              │ │   every object the function creates or gets back from a call is stored in a slot;
 │ slot: b              │ │   for a call, the callee receives a pointer to the slot and stores the result itself
 └──────────────────────┘ │
 ┌──────────────────────┐◄┘   the block in main()'s stack frame
 │ previous ──► ...     │     ...and so on, down to the thread's first Kotlin frame
 │ parameters=0 count=5 │
 └──────────────────────┘
```

Walking this list gives the GC every reference held by the thread's Kotlin functions. The price is a store to memory for
each reference a function obtains.

**The return slot.** After `LeaveFrame` in the callee, a returned object would be in no slot at all. So every function that
returns an object takes one extra, last parameter: a pointer to a slot in the *caller's* frame, and writes the result there
before returning. The LLVM IR of all this is in [codegen.md](../compiler/codegen.md#allocating-an-object-returning-it-and-keeping-it-alive).

## Calls in both directions

**Compiled code calls the runtime by name**: `AllocInstance`, `ThrowException`, `IsSubtype`, `EnterFrame`, and so on. Some
stdlib functions are also implemented in C++: `external fun` declarations annotated with
`@GCUnsafeCall("Kotlin_String_plusImpl")` are calls straight to that symbol, with no state switch.

**The runtime calls Kotlin** where the work is easier to write in Kotlin, such as throwing a `NullPointerException` or
printing an uncaught exception. These are internal stdlib functions annotated with `@ExportForCppRuntime`, which gives them
a plain C symbol name (by default the function's name) that C++ can declare and call:

```kotlin
@ExportForCppRuntime                                   // C++ side declares: void ThrowNullPointerException();
internal fun ThrowNullPointerException(): Nothing {
    throw NullPointerException()
}
```

Both sides must also agree on the layout of objects and type information. → [object-model.md](object-model.md)

## Things to know

- **A wrong thread state is a serious bug.** Touching Kotlin objects in the native state races with the GC; blocking in the
  runnable state stalls every GC pause, and with it all other threads.
- **`EnterFrame` is skipped for functions that hold no references**, and exception handlers reset the shadow stack with
  `SetCurrentFrame`, because unwinding skips the `LeaveFrame` calls of the frames it leaves. → [exceptions.md](../features/exceptions.md)
- **The runtime has its own threads**: the GC thread, a timer thread for the GC scheduler and a finalizer thread. Only
  the finalizer thread is registered like a Kotlin thread, because it can run Kotlin cleanup code.

## Where the code is

- [launcher.cpp](../../../kotlin-native/runtime/src/launcher/cpp/launcher.cpp),
  [Runtime.cpp](../../../kotlin-native/runtime/src/main/cpp/Runtime.cpp): startup, per-thread initialization
- [Memory.h](../../../kotlin-native/runtime/src/main/cpp/Memory.h): the functions compiled code calls for memory and thread state
- [mm/](../../../kotlin-native/runtime/src/main/cpp/mm): thread registry, thread states, safepoints, shadow stack, root set
- [runtime/README.md](../../../kotlin-native/runtime/README.md): the full list of modules and how to build and test them
