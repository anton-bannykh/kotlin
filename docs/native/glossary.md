# Glossary

Terms you will meet in the code, in reviews and in issues, each in one line. The arrow points to the page that explains it.

## Compiler: the outside view

- **konan**: the old name of Kotlin/Native. It survives in package names, `konanc`, `konan.properties`, `~/.konan`.
- **K/N**: short for Kotlin/Native.
- **First stage / second stage**: sources → klib / klibs → binary. → [pipeline.md](pipeline.md)
- **One-stage mode**: both stages in one `konanc` command, through a temporary klib. → [pipeline.md](pipeline.md)
- **klib**: the Kotlin library format: metadata plus Kotlin IR in a zip archive. → [klibs-and-linking.md](klibs-and-linking.md)
- **Metadata**: the declarations of a library as the frontend sees them, without bodies. → [klibs-and-linking.md](klibs-and-linking.md)
- **Kotlin IR**: the compiler's tree of classes, functions and expressions; what lowerings work on. → [tour.md](tour.md)
- **K2, FIR**: the current Kotlin frontend and its internal representation. Used by the first stage only.
- **K1, descriptors**: the old frontend's model of declarations; the second stage still loads klib metadata this way.
- **Signature** (`IdSignature`): the string that identifies a declaration across klibs. → [klibs-and-linking.md](klibs-and-linking.md)
- **Partial linkage**: klibs that do not match still link; the broken spot throws `IrLinkageError` when reached. → [klibs-and-linking.md](klibs-and-linking.md)
- **Closed world**: the second stage sees the whole program, and a binary contains all of it. → [compilation-model.md](compilation-model.md)
- **Output kind**: what `-produce` asks for: program, static or dynamic library, framework, cache. → [pipeline.md](pipeline.md)
- **`.kexe`**: the file extension of a Kotlin/Native executable (except on Windows).
- **Binary option**: a `-Xbinary=name=value` setting that changes the produced binary, for example `gc=pmcs`.
- **Target, host**: the platform a binary is for (`KonanTarget`, e.g. `linux_x64`), and the one the compiler runs on. → [build-and-tests.md](build-and-tests.md)
- **Distribution** (`dist`, konan home): the built compiler with the runtime and stdlib. → [code-map.md](code-map.md)
- **`konan.properties`**: per-target settings: toolchain, sysroot, Clang and linker flags. → [build-and-tests.md](build-and-tests.md)
- **Compiler cache**: a library compiled to machine code once and reused in later debug builds. → [caches.md](caches.md)
- **Per-file cache, incremental compilation**: the same with one cache per source file of a project. → [caches.md](caches.md)

## Compiler: inside

- **Phase**: one named step of the pipeline. Flags such as `-Xdisable-phases` take phase names. → [pipeline.md](pipeline.md)
- **Lowering**: a phase that rewrites one language feature into simpler IR. → [lowerings/](lowerings/README.md)
- **Fragment**: the part of the program that becomes one LLVM module: all of it, or one file. → [pipeline.md](pipeline.md)
- **Intrinsic**: a function with no Kotlin body, which the compiler implements itself (`@TypedIntrinsic`). → [codegen.md](codegen.md)
- **Bridge**: a generated function that adapts one calling convention to another, e.g. boxed to unboxed arguments.
- **Boxing**: wrapping a primitive value or a value class into a heap object where a reference is needed.
- **Static initializer**: code that initializes top-level properties and objects, run lazily. → [initialization.md](initialization.md)
- **Continuation, suspension point**: how a `suspend` function pauses and resumes. → [coroutines.md](coroutines.md)
- **Whole-program (global) optimizations**: analyses that need the closed world; `-opt` only. → [optimizations.md](optimizations.md)
- **DFG**: the data-flow graph of the program that these optimizations are computed on. → [optimizations.md](optimizations.md)
- **Devirtualization**: replacing a virtual call with direct calls to the possible targets. → [optimizations/devirtualization.md](optimizations/devirtualization.md)
- **DCE**: dead code elimination, removing what the program cannot reach. → [optimizations/dce.md](optimizations/dce.md)
- **Escape analysis, lifetime**: finding objects that can be allocated on the stack. → [optimizations/escape-analysis.md](optimizations/escape-analysis.md)
- **Codegen**: translating Kotlin IR to LLVM IR. → [codegen.md](codegen.md)
- **`kfun:`, `kclass:`**: prefixes of the symbol names of Kotlin functions and class descriptions in LLVM IR and binaries.
- **Mangled name**: the full symbol name, e.g. `kfun:shapes#total(kotlin.collections.List<shapes.Shape>){}kotlin.Double`.

## LLVM

- **LLVM IR, bitcode**: LLVM's input language, and its binary encoding (`.bc`). → [llvm-primer.md](llvm-primer.md)
- **Module**: LLVM's unit of compilation. Normally one for the whole program. → [llvm-primer.md](llvm-primer.md)
- **Pass**: one LLVM transformation of a module. → [llvm-primer.md](llvm-primer.md)
- **LTO**: link-time optimization: optimizing across what were separate compilation units. → [llvm-primer.md](llvm-primer.md)
- **Landing pad, unwinding**: where an exception arrives in a function, and how it gets there. → [exceptions.md](exceptions.md)
- **Debug info, DWARF**: the mapping from machine code back to source lines and variables, read by debuggers.
- **Sysroot**: the headers and libraries of a target system, needed by Clang and the linker. → [build-and-tests.md](build-and-tests.md)

## Runtime

- **Runtime**: the C++ library linked into every binary: memory, threads, exceptions, type checks. → [runtime.md](runtime.md)
- **Runtime module**: a replaceable part of the runtime, shipped as its own bitcode file (a GC, an allocator). → [runtime.md](runtime.md)
- **`ObjHeader`**: the header every object starts with; it points to the object's `TypeInfo`. → [object-model.md](object-model.md)
- **`TypeInfo`** (RTTI): the constant that describes a class at run time: size, supertypes, vtable. → [object-model.md](object-model.md)
- **vtable, itable**: tables of function pointers used for virtual calls and for interface calls. → [object-model.md](object-model.md)
- **Permanent object**: an object stored in the binary itself, such as a string literal; never collected. → [object-model.md](object-model.md)
- **Mutator**: a thread that runs Kotlin code, as opposed to a GC thread. → [gc-and-allocator.md](gc-and-allocator.md)
- **Safepoint**: a place where a thread checks whether the GC wants it to pause. → [runtime.md](runtime.md)
- **Thread state**: *runnable* (running Kotlin code) or *native* (in foreign code; the GC need not wait for it). → [runtime.md](runtime.md)
- **Stop-the-world (STW)**: a pause of all mutators, requested by the GC. → [gc-and-allocator.md](gc-and-allocator.md)
- **Shadow stack, frame**: per-function blocks of stack slots through which the GC finds local references. → [runtime.md](runtime.md)
- **CMS, PMCS, STWMS, noop**: the garbage collectors; CMS (concurrent mark and sweep) is the default. → [gc-and-allocator.md](gc-and-allocator.md)
- **Custom allocator**: the runtime's own heap allocator, the default one. → [gc-and-allocator.md](gc-and-allocator.md)
- **`@GCUnsafeCall`**: marks a Kotlin `external` function implemented in the C++ runtime. → [runtime.md](runtime.md)
- **`@ExportForCppRuntime`**: marks a Kotlin function that the C++ runtime calls by name. → [runtime.md](runtime.md)

## Interop and export

- **cinterop**: the tool that turns C and Objective-C headers into a klib; also the feature as a whole. → [cinterop.md](cinterop.md)
- **`.def` file**: the input of cinterop: which headers to import and with which options. → [cinterop.md](cinterop.md)
- **cinterop klib**: a klib made by cinterop: metadata and bitcode, no IR. → [klibs-and-linking.md](klibs-and-linking.md)
- **Platform libraries**: cinterop klibs for system APIs (POSIX, Apple frameworks), shipped with the compiler. → [cinterop.md](cinterop.md)
- **C stub / C bridge**: a small C function the compiler generates and compiles with Clang to make an interop call. → [cinterop.md](cinterop.md)
- **Commonizer**: makes platform libraries usable from code shared between several targets. → [cinterop.md](cinterop.md)
- **ObjCExport**: producing an Objective-C framework from Kotlin, and its run-time support. → [export.md](export.md)
- **C export**: producing a C library with a generated header (`-produce dynamic` or `static`). → [export.md](export.md)
- **Swift export**: direct export to Swift without Objective-C, in development. → [export.md](export.md)
- **Umbrella framework**: one framework that contains all Kotlin modules of an app. → [compilation-model.md](compilation-model.md)
