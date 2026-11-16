# LLVM in ten minutes

*Skip this page if you can read LLVM IR and know what a module, bitcode, a pass and a landing pad are.*

What you need to know about LLVM to follow the rest of this guide. Examples are real compiler output, shortened.

## What LLVM is, and what Kotlin/Native uses it for

LLVM is a compiler back end packaged as a library. You give it a program in its own low-level language, **LLVM IR**, and it
optimizes the program and produces machine code for x86-64, ARM64 and so on. Clang (C, C++, Objective-C), Swift and Rust are
built on it.

```mermaid
flowchart LR
    K["Kotlin IR"] -->|"Kotlin/Native code generation"| L["LLVM IR"]
    L -->|"LLVM optimization passes"| L2["better LLVM IR"]
    L2 -->|"LLVM code generator"| O["object file (.o)"]
    O -->|"linker (not LLVM's job, but LLVM ships one: lld)"| B["executable or library"]
```

Kotlin/Native writes the first arrow. Everything after it is LLVM, driven through its C API. The compiler downloads its own
LLVM build, with its command-line tools, into `~/.konan/dependencies/`.

LLVM knows nothing about Kotlin: no classes, no objects, no garbage collector. Others provide that:

| Need | Who provides it |
|---|---|
| Objects, classes, virtual calls | Code generation emits structs, constants and indirect calls → [object-model.md](runtime/object-model.md) |
| Memory management | The runtime; the compiler tells it where references are → [runtime/](runtime/README.md) |
| Kotlin-aware optimizations (devirtualization, escape analysis) | Done on Kotlin IR before LLVM sees the program → [optimizations/](compiler/optimizations/README.md) |
| Calling C and Objective-C | Kotlin/Native runs Clang as a library to parse headers and to compile small C stubs → [cinterop.md](interop/cinterop.md) |

## Reading LLVM IR

```llvm
define i32 @"kfun:#risky(kotlin.Int){}kotlin.Int"(i32 %0) {     ; fun risky(x: Int): Int
entry:
  %4 = icmp slt i32 %0, 0                                       ;   val t = x < 0
  br i1 %4, label %when_case, label %when_exit                  ;   if (t) goto when_case else goto when_exit

when_case:
  %5 = call ptr @AllocInstance(ptr @"kclass:kotlin.IllegalArgumentException", ptr %3)
  call void @"kfun:kotlin.IllegalArgumentException#<init>(kotlin.String?){}"(ptr %5, ptr @2257)
  call void @ThrowException(ptr %5)                             ;   throw IllegalArgumentException("negative")
  unreachable

when_exit:
  %6 = mul i32 %0, 2                                            ;   return x * 2
  ret i32 %6
}
```

| You see | It means |
|---|---|
| `@name` | A global: a function, a global variable or a constant. Kotlin functions are named `kfun:...`, class descriptions `kclass:...` |
| `%name`, `%4` | A local value. Each is assigned exactly once (this is **SSA** form: static single assignment) |
| `i1`, `i32`, `i64`, `double`, `ptr` | Types: integers of a given bit width (`i1` is a boolean), floating point, an untyped pointer. Every Kotlin object reference is just a `ptr` |
| `entry:`, `when_case:` | **Basic blocks**: straight-line code that ends with a jump (`br`), a return (`ret`) or `unreachable` |
| `define` / `declare` | A function with a body / a function defined elsewhere |
| `alloca` | Reserve a slot in the stack frame; `load` and `store` read and write memory |
| `getelementptr` | Compute the address of a field or array element. It does arithmetic only and does not touch memory |
| `phi` | "The value depends on which block we came from": how SSA merges values after a branch or around a loop |
| `!dbg !123` | Debug information: links an instruction to a source line. It ends up as DWARF in the binary, which debuggers read |

Loops are just blocks that jump backwards. Here is the loop from the [tour](tour.md), with the variable `sum` kept in a stack
slot:

```llvm
while_loop:
  call void @Kotlin_mm_safePointWhileLoopBody()
  %7 = call ptr @"kfun:kotlin.collections.Iterator#next(){}1:0"(ptr %6, ptr %4)
  %9 = load double, ptr %sum
  %10 = call double @"kfun:Shape#area(){}kotlin.Double"(ptr %7)
  %11 = fadd double %9, %10
  store double %11, ptr %sum
  br label %loop_check

loop_check:
  %12 = call i1 @"kfun:kotlin.collections.Iterator#hasNext(){}kotlin.Boolean"(ptr %6)
  br i1 %12, label %while_loop, label %loop_exit
```

## Exceptions: `invoke` and landing pads

A call that sits inside a `try` is written as `invoke` instead of `call`. It has two continuations: one for a normal return
and one for an exception. The exceptional one is a **landing pad**. Getting from the `throw` to the landing pad is called
**unwinding**. → [exceptions.md](features/exceptions.md)

```llvm
  %4 = invoke i32 @"kfun:#risky(kotlin.Int){}kotlin.Int"(i32 %0)     ; try { risky(x) } catch (...) { ... }
          to label %call_success unwind label %landingpad

landingpad:
  %lp = landingpad { ptr, i32 } catch ptr null                       ; an exception thrown inside risky arrives here
```

## Modules and bitcode

- A **module** is LLVM's unit of compilation: a set of functions and globals, like one `.c` file after preprocessing.
  Kotlin/Native normally generates **one module for the whole program**, not one per source file.
- LLVM IR has two equivalent forms: text (`.ll`), which is what you read, and **bitcode** (`.bc`), a compact binary encoding.
  `llvm-dis` and `llvm-as` convert between them.
- Modules can be merged, and LLVM then optimizes across what used to be separate compilation units. This is called **LTO**
  (link-time optimization). Kotlin/Native uses it to combine the program with its C++ runtime.
  → [codegen.md](compiler/codegen.md#after-code-generation)

## Optimization passes and code generation

LLVM's optimizer is a pipeline of **passes**, each a transformation from IR to IR: inlining, removing dead code, moving
stack slots into registers, and many more. After that, LLVM's code generator turns the module into an
object file for the target. Only at this point does anything depend on the target's instruction set, which is why one
compiler can produce binaries for many platforms.

How to get the LLVM IR of your own code: [cheat-sheet.md](cheat-sheet.md). More tricks (running single passes with `opt`,
debugging the compiler) are in [HACKING.md](../../kotlin-native/HACKING.md).
