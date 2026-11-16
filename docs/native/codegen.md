# From Kotlin IR to a binary

**Code generation** is the step where Kotlin stops being a tree of Kotlin IR and becomes [LLVM IR](llvm-primer.md). By then
the [lowerings](lowerings/README.md) have reduced the program to classes, fields, functions, calls, branches and loops, so
the translation is mostly one construct at a time. LLVM knows nothing about objects or garbage collection, so everything
Kotlin-specific is spelled out as loads, stores and calls into the [runtime](runtime.md). After that the runtime is merged
in, LLVM optimizes, and the system linker produces the binary.

This page is a phrasebook: a Kotlin construct on top, the LLVM IR it becomes below. The snippets are real compiler output
for the program below, shortened (empty blocks and the lazy [file initialization](initialization.md) check are removed).
A complete `main` is in the [tour](tour.md#step-5-kotlin-ir--llvm-ir).

```kotlin
interface Shape { fun area(): Double }
open class Circle(val r: Double) : Shape { override fun area() = 3.14 * r * r; open fun name() = "circle" }
```

## A function, and its name

```llvm
define double @"kfun:#radius(Circle){}kotlin.Double"(ptr %c) {      ; fun radius(c: Circle): Double = c.r
  call void @Kotlin_mm_safePointFunctionPrologue()                  ; safepoint, see below
  %r = call double @"kfun:Circle#<get-r>(){}kotlin.Double"(ptr %c)
  ret double %r
}
```

One Kotlin function becomes one LLVM function. The symbol name is `kfun:` + package and class + `#` + name + parameter
types + `{}` (type parameters, if any) + return type, so overloads get different symbols. Primitives are passed as machine
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

Each class gets an LLVM struct type: the object header, then the fields. → [object-model.md](object-model.md)

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
  object of that class. The constructor is then called as an ordinary function. → [gc-and-allocator.md](gc-and-allocator.md)
- **The garbage collector must be able to find every reference a function holds.** LLVM's own values live in registers,
  where the GC cannot see them. So each function that holds references reserves a block of stack slots, registers it with
  `EnterFrame`, and keeps a copy of each reference in a slot. The GC walks the list of frames to find its roots.
  Functions that hold no references of their own, like `radius` above, have no frame at all.
- **The return slot.** After `LeaveFrame` in the callee, a returned object would be in no slot at all. So every function
  that returns an object takes one extra, last parameter: a pointer to a slot in the *caller's* frame, and writes the
  result there before returning. A caller writes `call ptr @"kfun:#makeCircle(...)"(double %r, ptr %slot)`.

## A virtual call and an interface call

A call to an overridable function is compiled as a direct call to a small generated function with the function's public
name. That function, a **trampoline**, does the dispatch. The real body is emitted under the name with `-impl` added.

```llvm
define ptr @"kfun:Circle#name(){}kotlin.String"(ptr %this, ptr %result) {     ; c.name(), c: Circle
  %ti = call ptr @Kotlin_Any_getTypeInfo(ptr %this)        ; the class description of the actual object
  %vtable = getelementptr %struct.TypeInfo, ptr %ti, i32 1 ; the vtable starts right after it
  %entry = getelementptr ptr, ptr %vtable, i32 4           ; name() is entry 4 in Circle and in all its subclasses
  %f = load ptr, ptr %entry
  %s = call ptr %f(ptr %this, ptr %result)                 ; indirect call
  ...
```

For an interface the entry number cannot be fixed, because a class may implement any set of interfaces. The trampoline
`kfun:Shape#area(){}kotlin.Double` first finds the record for `Shape` in the object's **interface table**, using a number
that identifies the interface, and then calls through the function table stored in that record:

```llvm
  %i = and i32 %tableMask, -2057073250                     ; -2057073250 is the id of Shape
  %record = getelementptr %struct.InterfaceTableRecord, ptr %table, i32 %i
```

Both tables are explained in [object-model.md](object-model.md). With `-opt`,
[devirtualization](optimizations/devirtualization.md) turns many of these calls into direct calls.

## A string literal

```llvm
@2267 = internal unnamed_addr constant { ptr, i32, i32, i16, [10 x i8] }       ; "hello"
    { ptr getelementptr (i8, ptr @"kclass:kotlin.String", i32 1), i32 6, i32 99162322, i16 0,
      [10 x i8] c"h\00e\00l\00l\00o\00" }
```

A string literal is a complete `String` object in the binary's constant data: header, length, precomputed hash code, flags,
UTF-16 characters. Using it is just using the address `@2267`. Nothing is allocated at run time.

## After code generation

1. **The runtime is linked in.** The C++ runtime ships as LLVM bitcode and is merged into the program's module, so LLVM
   can inline runtime functions into Kotlin code.
2. **LLVM passes run.** With `-opt`: LLVM's standard `O3` pipeline, then removal of unused symbols and a whole-module (LTO)
   pipeline. With `-g`: the same sequence at `O0`, so the code stays as written; only the runtime part is optimized.
3. **Object file.** The module is written out as bitcode and Clang translates it to machine code.
4. **System linker.** `ld.lld`, or `ld64` on Apple platforms, links the object file with system libraries.

[Caches](caches.md) let most of this be done once per library instead of once per program. How `try`/`catch` is compiled is
in [exceptions.md](exceptions.md).

## Debug information

With `-g` every instruction carries a source location (`!dbg !47372`), and locals and parameters are kept in named stack
slots that are described to the debugger. LLVM turns this into DWARF in the object file. The DWARF says where variables
are, but not what a Kotlin object looks like inside: every reference has the same opaque type, `ObjHeader *`. The LLDB
script `konan_lldb.py` (in the distribution's `tools/` directory) fills the gap: it asks the running program, through debug
functions of the runtime, for the class and the fields of an object. `-opt` and `-g` cannot be combined.

## Things to know

- **Raw output is noisy.** Every function has the same blocks (`prologue`, `entry`, `epilogue` with the single return),
  many of them empty, and `phi`s with one input. LLVM removes all that.
- **Safepoints.** A safepoint is a place where a thread checks whether the GC has asked it to pause. Code generation puts
  one at the start of a function (`Kotlin_mm_safePointFunctionPrologue`, above) and one in every loop body
  (`Kotlin_mm_safePointWhileLoopBody`, shown in [llvm-primer.md](llvm-primer.md)). → [runtime.md](runtime.md)
- **The slot protocol is a contract with the runtime.** A reference that is live across a call but is in no registered
  slot is a bug that shows up only when the GC runs at the wrong moment.
- **Code generation is deliberately naive.** Speed comes from the Kotlin-level [optimizations](optimizations.md) before
  it and the LLVM passes after it. To see its output for your code, see the end of [llvm-primer.md](llvm-primer.md).

## Where the code is

- [IrToBitcode.kt](../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/llvm/IrToBitcode.kt): translates each IR construct
- [CodeGenerator.kt](../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/llvm/CodeGenerator.kt): frames, slots, calls, trampolines
- [OptimizationPipeline.kt](../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/OptimizationPipeline.kt): which LLVM passes run in which mode
- [Linker.kt](../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/Linker.kt): the final link
- [konan_lldb.py](../../kotlin-native/llvmDebugInfoC/src/scripts/konan_lldb.py): the LLDB helper
