[Kotlin/Native guide](../README.md) › [compiler](README.md) › codegen

# From Kotlin IR to a binary

*Skip this page if you can read the LLVM IR that Kotlin/Native emits and know how the runtime's bitcode gets into the binary.*

**Code generation** is the step where Kotlin stops being a tree of Kotlin IR and becomes [LLVM IR](../llvm-primer.md). By then
the [lowerings](lowerings/README.md) have reduced the program to classes, fields, functions, calls, branches and loops, so
the translation is mostly one construct at a time, with everything Kotlin-specific spelled out as loads, stores and calls
into the [runtime](../runtime/README.md). After that the runtime is merged in, LLVM optimizes, and the system linker produces
the binary.

This page is a phrasebook: a Kotlin construct on top, the LLVM IR it becomes below. The snippets are real compiler output
for the program below, shortened (empty blocks and the lazy [file initialization](../features/initialization.md) check are removed).
A complete `main` is in the [tour](../tour.md#step-5-kotlin-ir--llvm-ir); `try`/`catch` is in [exceptions.md](../features/exceptions.md).

```kotlin
interface Shape { fun area(): Double }
open class Circle(val r: Double) : Shape { override fun area() = 3.14 * r * r; open fun name() = "circle" }
```

## A function, and its name

```llvm
define double @"kfun:#radius(Circle){}kotlin.Double"(ptr %c) {      ; fun radius(c: Circle): Double = c.r
  call void @Kotlin_mm_safePointFunctionPrologue()                  ; safepoint: the GC may pause the thread here
  %r = call double @"kfun:Circle#<get-r>(){}kotlin.Double"(ptr %c)
  ret double %r
}
```

The symbol name is `kfun:` + package and class + `#` + name + parameter types + `{}` (type parameters, if any) + return
type, so overloads get different symbols. Primitives are passed as machine
values (`double`, `i32`), every object reference as a plain `ptr`, and `this` is the first parameter.

## Reading a field

```llvm
%"kclassbody:Circle#internal" = type <{ %struct.ObjHeader, double }>            ; the layout of a Circle
define double @"kfun:Circle#<get-r>(){}kotlin.Double"(ptr %this) {
  %p = getelementptr inbounds %"kclassbody:Circle#internal", ptr %this, i32 0, i32 1    ; address of member 1
  %r = load double, ptr %p
  ret double %r
}
```

Each class gets an LLVM struct type: the object header, then the fields. → [object-model.md](../runtime/object-model.md)

## Allocating an object, returning it, and keeping it alive

```llvm
; fun makeCircle(r: Double): Circle = Circle(r)
define ptr @"kfun:#makeCircle(kotlin.Double){}Circle"(double %r, ptr %result) {
  %frame = alloca ptr, i32 3                           ; zeroed stack slots: 2 for a frame header, 1 for a reference
  %slot = getelementptr ptr, ptr %frame, i32 2
  call void @EnterFrame(ptr %frame, i32 0, i32 3)      ; link this frame into the thread's list of frames
  call void @Kotlin_mm_safePointFunctionPrologue()
  %c = call ptr @AllocInstance(ptr @"kclass:Circle", ptr %slot)     ; allocate; the reference is also stored into %slot
  call void @"kfun:Circle#<init>(kotlin.Double){}"(ptr %c, double %r)
  call void @UpdateReturnRef(ptr %result, ptr %c)      ; store the result into the caller's slot
  call void @LeaveFrame(ptr %frame, i32 0, i32 3)
  ret ptr %c
}
```

Three things here have no counterpart in the Kotlin source:

- **Allocation is a runtime call.** `AllocInstance` gets the class description (`@"kclass:Circle"`) and returns a new
  object. The constructor is then called as an ordinary function. → [gc-and-allocator.md](../runtime/gc-and-allocator.md)
- **The frame** (`EnterFrame`, `LeaveFrame`, the slots) is how the GC finds the references a function holds. Functions that
  hold none, like `radius` above, have no frame.
- **The return slot**: a function that returns an object takes one extra, last parameter, a slot in the *caller's* frame,
  and writes the result there before returning. Why both exist: [runtime/](../runtime/README.md#how-the-gc-finds-references).

## A virtual call and an interface call

A call to an overridable function is compiled as a direct call to a small generated function with the function's public
name. That function, a **trampoline**, does the dispatch through the tables described in
[object-model.md](../runtime/object-model.md). The real body is emitted under the name with `-impl` added.

```llvm
define ptr @"kfun:Circle#name(){}kotlin.String"(ptr %this, ptr %result) {     ; c.name(), c: Circle
  %ti = call ptr @Kotlin_Any_getTypeInfo(ptr %this)        ; the class description of the actual object
  %vtable = getelementptr %struct.TypeInfo, ptr %ti, i32 1 ; the vtable starts right after it
  %entry = getelementptr ptr, ptr %vtable, i32 4           ; name() is entry 4 in Circle and in all its subclasses
  %f = load ptr, ptr %entry
  %s = call ptr %f(ptr %this, ptr %result)                 ; indirect call
  ...
```

The interface trampoline `kfun:Shape#area(){}kotlin.Double` first finds the record for `Shape` in the object's interface
table, using a number that identifies the interface, and then calls through the function table stored in that record:

```llvm
  %i = and i32 %tableMask, -2057073250                     ; -2057073250 is the id of Shape
  %record = getelementptr %struct.InterfaceTableRecord, ptr %table, i32 %i
```

With `-opt`, [devirtualization](optimizations/devirtualization.md) turns many of these calls into direct calls.

## Constants

A string literal is a complete `String` object in the binary's constant data, and using it is just using its address
(`ptr @2267`). Nothing is allocated at run time. → [object-model.md](../runtime/object-model.md#objects-that-are-constants)

## Intrinsics

An **intrinsic** is a stdlib function that the compiler implements itself: marked `@TypedIntrinsic` with a kind, and usually
`external`, with no Kotlin body. This is `Int.plus` in the Native stdlib (`kotlin/Primitives.kt`), and what a call becomes:

```kotlin
@TypedIntrinsic(IntrinsicType.PLUS)
public actual external operator fun plus(other: Int): Int    // fun add(a: Int, b: Int) = a + b  →  %3 = add i32 %0, %1
```

Code generation emits instructions instead of a call (`IntrinsicGenerator.kt`). Other kinds never get that far, because a
lowering replaces the call first: `enumValues<Color>()` is inlined to `enumValuesIntrinsic<Color>()` (kind `ENUM_VALUES`),
which `EnumUsageLowering` turns into `Color.values()`. The kinds are listed twice, in the stdlib and in the compiler.
→ [before-you-change.md](../reference/before-you-change.md)

Related annotations that make the compiler treat a declaration specially (`@GCUnsafeCall`, `@ExportForCppRuntime`, ...) are in
`kotlin/native/internal/Annotations.kt`; `@SymbolName` is in `kotlin/native/Annotations.kt`. → [runtime/](../runtime/README.md#calls-in-both-directions)

## After code generation

1. **The runtime is linked in.** The C++ runtime is compiled by [Clang][clang] to [LLVM bitcode][bitcode] when Kotlin/Native
   itself is built (`runtime.bc`, plus one file per [replaceable part](../runtime/README.md#how-it-is-shipped), such as
   `concurrent_ms_gc.bc` for the default garbage collector). The compiler merges the files matching the chosen options into
   the program's LLVM module, together with the C stubs generated for interop calls. Runtime and program are now one module,
   so LLVM can inline small runtime functions, such as the safepoint check, straight into Kotlin code.
2. **LLVM passes run.** With `-opt`: LLVM's [standard `O3` pipeline][newpm], then removal of unused symbols and a
   whole-module ([LTO][lto]) pipeline. With `-g`: the same sequence at `O0`, so the code stays as written; only the runtime
   part is optimized. Kotlin/Native adds a few passes of its own, for example one that removes redundant safepoints.
3. **Object file.** The module is written out as bitcode and the bundled Clang translates it to machine code.
4. **System linker.** [`ld.lld`][lld], or `ld64` on Apple platforms, links the object file with system libraries and with the
   [caches](caches.md) of libraries compiled earlier.

## Debug information

With `-g` every instruction carries a [source location][dbg] (`!dbg !47372`), and locals and parameters are kept in named
stack slots that are described to the debugger. LLVM turns this into [DWARF][dwarf] in the object file. To DWARF every
reference has the same opaque type, `ObjHeader *`, so the [LLDB][lldb] script `konan_lldb.py` (in the distribution's `tools/`)
asks the runtime's debug functions for the class and the fields of an object. `-opt` and `-g` cannot be combined.

## Things to know

- **Raw output is noisy and deliberately naive**: the same blocks in every function (`prologue`, `entry`, `epilogue`), many
  empty, and [`phi`s][phi] with one input. LLVM removes that; speed comes from the Kotlin-level [optimizations](optimizations/README.md)
  before code generation and the LLVM passes after it. For your own code: [cheat-sheet.md](../reference/cheat-sheet.md).
- **The slot protocol is a contract with the runtime.** A reference that is live across a call but is in no registered
  slot is a bug that shows up only when the GC runs at the wrong moment.

## Where the code is

- [IrToBitcode.kt](../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/llvm/IrToBitcode.kt): translates each IR construct
- [CodeGenerator.kt](../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/llvm/CodeGenerator.kt): frames, slots, calls, trampolines
- [OptimizationPipeline.kt](../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/OptimizationPipeline.kt): which LLVM passes run in which mode
- [Linker.kt](../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/Linker.kt): the final link

[clang]: https://clang.llvm.org/
[bitcode]: https://llvm.org/docs/BitCodeFormat.html
[newpm]: https://llvm.org/docs/NewPassManager.html
[lto]: https://llvm.org/docs/LinkTimeOptimization.html
[lld]: https://lld.llvm.org/
[dbg]: https://llvm.org/docs/SourceLevelDebugging.html
[dwarf]: https://dwarfstd.org/
[lldb]: https://lldb.llvm.org/
[phi]: https://llvm.org/docs/LangRef.html#phi-instruction
