[Kotlin/Native guide](../../README.md) › [compiler](../README.md) › [optimizations](README.md) › escape-analysis

# Escape analysis

*Skip this page if you know which objects Kotlin/Native allocates on the stack and what makes an object escape.*

Normally every Kotlin object is allocated on the heap by the runtime and later reclaimed by the
[garbage collector](../../runtime/gc-and-allocator.md). Many objects are short-lived helpers that never leave the function
that creates them. [Escape analysis][ea] finds these, and code generation puts them into the function's stack frame instead:
no call to the allocator, and nothing for the GC to clean up. It is one of the [whole-program optimizations](README.md).

```kotlin
class Point(val x: Int, val y: Int)

fun manhattan(a: Int, b: Int): Int {
    val p = Point(a, b)             // p is used only inside this function
    return p.x + p.y
}

var lastPoint: Point? = null
fun remember(a: Int, b: Int): Int {
    val p = Point(a, b)
    lastPoint = p                   // p is stored in a global: it lives on after the function returns
    return p.x + p.y
}
```

In a debug build `manhattan` gets its `Point` from the runtime: `call ptr @AllocInstance(ptr @"kclass:Point", ptr %4)`.
With `-opt` there is no allocation call. The object is a slot in the stack frame ([`alloca`][alloca]). LLVM IR right after
code generation, shortened:

```llvm
  %5 = alloca %"kclassbody:Point#internal"                      ; room for a Point (header + two Ints) in the stack frame
  call void @llvm.memset.p0.i32(ptr %5, i8 0, i32 16, i1 false) ; zero its 16 bytes
  %6 = or i64 ptrtoint (ptr @"kclass:Point" to i64), 3          ; object header = pointer to the class, with the two low
  %7 = inttoptr i64 %6 to ptr                                   ;   bits set: this marks "stack object" for the runtime
  store ptr %7, ptr %typeInfoOrMeta_
  ...
  call void @"kfun:Point#<init>(kotlin.Int;kotlin.Int){}"(ptr %objHeader, i32 %0, i32 %1)   ; the constructor does not care
  %9 = load i32, ptr %8                                         ; p.x (the getter was inlined, also an -opt effect)
  %11 = load i32, ptr %10                                       ; p.y
  %12 = add i32 %9, %11
```

`remember` still calls `AllocInstance` in the `-opt` build, because its `Point` escapes. The stack object is a complete
Kotlin object with a normal header, so any code can use it without knowing where it lives.

## The question it answers

For every allocation: **can this object still be referenced after the function that created it has returned?** An object
**escapes** (must stay on the heap) when it is:

- stored in a global: a top-level property or a field of an `object`;
- stored in a field or array element of something that itself escapes or lives on the heap;
- returned from the function, or stored into an object that was passed in as a parameter;
- thrown as an exception;
- passed to a function the compiler knows nothing about: a virtual call with many possible targets, or a C or
  Objective-C function.

Everything an escaping object refers to escapes too.

## It looks across function calls

Passing an object to another function does not by itself make it escape. The compiler analyses callees before callers
and keeps, for each function, a short summary: what happens to each parameter (does it escape? does it end up referenced
from another parameter or from the return value?). At a call site the summary is used in place of the callee's body. For
example, `main` calls `speak(Dog())`, where `fun speak(a: Animal) = a.sound()`. The summary of `speak` says its parameter
does not escape, so this `Dog` is placed in the stack frame of `main`.

This is why [devirtualization](devirtualization.md) runs first. For a direct call there is one summary to apply. For a
virtual call with a few known targets (up to five), all their summaries are applied. For a virtual call with more targets
the compiler gives up and treats every argument as escaping.

Functions of the runtime written in C++ (`external fun` in the stdlib) have no body to analyse. Their summaries are written
by hand as annotations:

```kotlin
@Escapes.Nothing                                   // neither the receiver, nor the argument, nor the result escapes
internal external fun plusImpl(other: String): String

@PointsTo(0x000, 0x000, 0x002)                     // the result is one of the elements of `this`
public external operator fun get(index: Int): T    // (in class Array)
```

## Things to know

- **The result is all or nothing.** Internally the analysis distinguishes more cases (escapes only through the return
  value, only into a parameter, ...), but code generation supports two outcomes: stack or heap.
- **Some objects never go on the stack**: classes with a finalizer; arrays whose size is not a compile-time constant, or
  beyond 64 KB of stack arrays per function; an object allocated in a loop and assigned to a variable declared outside it.
- **A wrong answer here corrupts memory.** If an object is put on the stack but a reference to it survives the function,
  that reference points into a dead stack frame. Such a bug appears only with `-opt`; to check, compile with
  `-Xdisable-phases=EscapeAnalysis`.
- **A wrong `@Escapes` or `@PointsTo` annotation has the same effect.** The compiler checks that (almost) every stdlib
  `external fun` that takes or returns objects has one, but it cannot check that the annotation is true.

## Where the code is

- [EscapeAnalysis.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/optimizations/EscapeAnalysis.kt):
  the analysis. The comment at the top of the file describes the algorithm.
- [Annotations.kt](../../../../kotlin-native/runtime/src/main/kotlin/kotlin/native/internal/escapeAnalysis/Annotations.kt):
  `@Escapes` and `@PointsTo`, with a full explanation of the encoding.
- [CodeGenerator.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/llvm/CodeGenerator.kt):
  where an allocation becomes either a stack slot or a call to `AllocInstance` (search for `Lifetime.STACK`).
- [Memory.h](../../../../kotlin-native/runtime/src/main/cpp/Memory.h): the runtime's side: how a stack object is recognised
  from its header (`OBJECT_TAG_STACK`).

**Read next:** [dce.md](dce.md)

[ea]: https://en.wikipedia.org/wiki/Escape_analysis
[alloca]: https://llvm.org/docs/LangRef.html#alloca-instruction
