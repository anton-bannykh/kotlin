[Kotlin/Native guide](../README.md) › reference › glossary

# Glossary

*Skip this page until you meet a term you do not know. Reference material.*

Terms you will meet in the code, in reviews and in issues, each in one line. The arrow points to the page that explains it.

## Compiler: the outside view

- **konan**, **K/N**: the old name of Kotlin/Native (it survives in package names, `konanc`, `~/.konan`), and the short one.
- **First stage / second stage**: sources → klib / klibs → binary. **One-stage mode**: both in one `konanc` command. → [compiler/](../compiler/README.md)
- [**klib**](https://kotlinlang.org/docs/native-libraries.html): the Kotlin library format: metadata plus Kotlin IR in a zip archive. → [klibs-and-linking.md](../compiler/klibs-and-linking.md)
- **Metadata**: the declarations of a library as the frontend sees them, without bodies. → [klibs-and-linking.md](../compiler/klibs-and-linking.md)
- **Kotlin IR**: the compiler's tree of classes, functions and expressions; what lowerings work on. → [tour.md](../tour.md)
- **K2, FIR**: the current Kotlin frontend and its internal representation. Used by the first stage only.
- **K1, descriptors**: the old frontend's model of declarations; the second stage still loads klib metadata this way.
- **Signature** (`IdSignature`): the string that identifies a declaration across klibs. → [klibs-and-linking.md](../compiler/klibs-and-linking.md)
- **Partial linkage**: klibs that do not match still link; the broken spot throws `IrLinkageError` when reached. → [klibs-and-linking.md](../compiler/klibs-and-linking.md)
- **Closed world**: the second stage sees the whole program, and a binary contains all of it. → [compiler/](../compiler/README.md#the-outside-view)
- **Output kind**: what `-produce` asks for: program, static or dynamic library, framework, cache. → [compiler/](../compiler/README.md)
- **`.kexe`**: the file extension of a Kotlin/Native executable (except on Windows).
- [**Binary option**](https://kotlinlang.org/docs/native-binary-options.html): a `-Xbinary=name=value` setting that changes the produced binary, for example `gc=pmcs`.
- [**Target, host**](https://kotlinlang.org/docs/native-target-support.html): the platform a binary is for (`KonanTarget`, e.g. `linux_x64`), and the one the compiler runs on. → [build-and-tests/](../build-and-tests/README.md)
- **Distribution** (`dist`, konan home): the built compiler with the runtime and stdlib. → [code-map.md](code-map.md)
- **`konan.properties`**: per-target settings: toolchain, sysroot, [Clang](https://clang.llvm.org/) and linker flags. → [build-and-tests/](../build-and-tests/README.md)
- **Compiler cache**: a library compiled to machine code once and reused in later debug builds. **Per-file cache, incremental compilation**: the same per source file. → [caches.md](../compiler/caches.md)

## Compiler: inside

- **Phase**: one named step of the pipeline. Flags such as `-Xdisable-phases` take phase names. → [compiler/](../compiler/README.md)
- **Lowering**: a phase that rewrites one language feature into simpler IR. → [lowerings/](../compiler/lowerings/README.md)
- **Fragment**: the part of the program that becomes one LLVM module: all of it, or one file. → [compiler/](../compiler/README.md)
- **Intrinsic**: a function with no Kotlin body, which the compiler implements itself (`@TypedIntrinsic`). → [codegen.md](../compiler/codegen.md#intrinsics)
- **Bridge**: a generated function that adapts one calling convention to another, e.g. boxed to unboxed arguments.
- [**Boxing**](https://en.wikipedia.org/wiki/Boxing_(computer_programming)): wrapping a primitive value or a value class into a heap object where a reference is needed.
- **Static initializer**: code that initializes top-level properties and objects, run lazily. → [initialization.md](../features/initialization.md)
- [**Continuation, suspension point**](https://github.com/Kotlin/KEEP/blob/master/proposals/coroutines.md#continuation-passing-style): how a `suspend` function pauses and resumes. → [coroutines.md](../features/coroutines.md)
- **Whole-program (global) optimizations**: analyses that need the closed world; `-opt` only. → [optimizations/](../compiler/optimizations/README.md)
- **DFG**: the data-flow graph of the program that these optimizations are computed on. → [optimizations/](../compiler/optimizations/README.md)
- **Devirtualization**: replacing a virtual call with direct calls to the possible targets. → [optimizations/devirtualization.md](../compiler/optimizations/devirtualization.md)
- **DCE**: [dead code elimination](https://en.wikipedia.org/wiki/Dead-code_elimination), removing what the program cannot reach. → [optimizations/dce.md](../compiler/optimizations/dce.md)
- **[Escape analysis](https://en.wikipedia.org/wiki/Escape_analysis), lifetime**: finding objects that can be allocated on the stack. → [optimizations/escape-analysis.md](../compiler/optimizations/escape-analysis.md)
- **Codegen**: translating Kotlin IR to LLVM IR. → [codegen.md](../compiler/codegen.md)
- **`kfun:`, `kclass:`, [mangled name](https://en.wikipedia.org/wiki/Name_mangling)**: symbol names of Kotlin functions and class descriptions in LLVM IR and binaries. → [codegen.md](../compiler/codegen.md)

## LLVM

- **[LLVM IR](https://llvm.org/docs/LangRef.html), [bitcode](https://llvm.org/docs/BitCodeFormat.html)**: LLVM's input language, and its binary encoding (`.bc`). → [llvm-primer.md](../llvm-primer.md)
- [**Module**](https://llvm.org/docs/LangRef.html#module-structure), [**pass**](https://llvm.org/docs/Passes.html): LLVM's unit of compilation, and one LLVM transformation of it. → [llvm-primer.md](../llvm-primer.md)
- [**LTO**](https://llvm.org/docs/LinkTimeOptimization.html): link-time optimization: optimizing across what were separate compilation units. → [llvm-primer.md](../llvm-primer.md)
- **[Landing pad](https://llvm.org/docs/LangRef.html#landingpad-instruction), [unwinding](https://llvm.org/docs/ExceptionHandling.html)**: where an exception arrives in a function, and how it gets there. → [exceptions.md](../features/exceptions.md)
- **Debug info, [DWARF](https://dwarfstd.org/)**: the mapping from machine code back to source lines and variables, read by debuggers.
- [**Sysroot**](https://clang.llvm.org/docs/CrossCompilation.html): the headers and libraries of a target system, needed by Clang and the linker. → [build-and-tests/](../build-and-tests/README.md)

## Runtime

- **Runtime**: the C++ library linked into every binary: memory, threads, exceptions, type checks. → [runtime/](../runtime/README.md)
- **Runtime module**: a replaceable part of the runtime, shipped as its own bitcode file (a GC, an allocator). → [runtime/](../runtime/README.md)
- **`ObjHeader`**: the header every object starts with; it points to the object's `TypeInfo`. → [object-model.md](../runtime/object-model.md)
- **`TypeInfo`** ([RTTI](https://en.wikipedia.org/wiki/Run-time_type_information)): the constant that describes a class at run time: size, supertypes, vtable. → [object-model.md](../runtime/object-model.md)
- **[vtable](https://en.wikipedia.org/wiki/Virtual_method_table), itable**: tables of function pointers used for virtual calls and for interface calls. → [object-model.md](../runtime/object-model.md)
- **Permanent object**: an object stored in the binary itself, such as a string literal; never collected. → [object-model.md](../runtime/object-model.md)
- **Mutator**: a thread that runs Kotlin code, as opposed to a GC thread. [**Stop-the-world (STW)**](https://en.wikipedia.org/wiki/Tracing_garbage_collection#Stop-the-world_vs._incremental_vs._concurrent): a pause of all mutators.
- [**Safepoint**](https://openjdk.org/groups/hotspot/docs/HotSpotGlossary.html#safepoint): a place where a thread checks whether the GC wants it to pause. → [runtime/](../runtime/README.md)
- **Thread state**: *runnable* (running Kotlin code) or *native* (in foreign code; the GC need not wait for it). → [runtime/](../runtime/README.md)
- **Shadow stack, frame**: per-function blocks of stack slots through which the GC finds local references. → [runtime/](../runtime/README.md)
- **CMS, PMCS, STWMS, noop**: the garbage collectors; CMS ([concurrent mark and sweep](https://en.wikipedia.org/wiki/Tracing_garbage_collection#Naive_mark-and-sweep)) is the default. → [gc-and-allocator.md](../runtime/gc-and-allocator.md)
- **Custom allocator**: the runtime's own heap allocator, the default one. → [gc-and-allocator.md](../runtime/gc-and-allocator.md)
- **`ExternalRCRef`**: the runtime's counted reference to a Kotlin object from outside the heap: a root while the count is above zero, a weak reference at zero. → [gc-and-allocator.md](../runtime/gc-and-allocator.md#things-to-know)
- **Worker, freezing, legacy memory model**: the old rules for sharing objects between threads. `Worker` remains as an obsolete API; freezing does nothing. → [runtime/](../runtime/README.md#things-to-know)
- **`@GCUnsafeCall`**, **`@ExportForCppRuntime`**: a Kotlin `external` function implemented in the C++ runtime, and a Kotlin function the runtime calls by name. → [runtime/](../runtime/README.md#calls-in-both-directions)

## Interop and export

- [**cinterop**](https://kotlinlang.org/docs/native-c-interop.html): the tool that turns C and Objective-C headers into a klib; also the feature as a whole. → [cinterop.md](../interop/cinterop.md)
- [**`.def` file**](https://kotlinlang.org/docs/native-definition-file.html): the input of cinterop: which headers to import and with which options. → [cinterop.md](../interop/cinterop.md)
- **cinterop klib**: a klib made by cinterop: metadata and bitcode, no IR. → [klibs-and-linking.md](../compiler/klibs-and-linking.md)
- [**Platform libraries**](https://kotlinlang.org/docs/native-platform-libs.html): cinterop klibs for system APIs (POSIX, Apple frameworks), shipped with the compiler. → [cinterop.md](../interop/cinterop.md)
- **C stub / C bridge**: a small C function the compiler generates and compiles with Clang to make an interop call. → [cinterop.md](../interop/cinterop.md)
- **Commonizer**: makes platform libraries [usable from code shared between several targets](https://kotlinlang.org/docs/multiplatform/multiplatform-share-on-platforms.html#connect-platform-specific-libraries). → [cinterop.md](../interop/cinterop.md)
- [**ObjCExport**](https://kotlinlang.org/docs/native-objc-interop.html), [**C export**](https://kotlinlang.org/docs/native-dynamic-libraries.html), [**Swift export**](https://kotlinlang.org/docs/native-swift-export.html): producing an Objective-C framework, a C library with a header, or (in development) Swift sources from Kotlin. → [export.md](../interop/export.md)
- **Umbrella framework**: one framework that contains all Kotlin modules of an app. → [compiler/](../compiler/README.md#the-outside-view)
