[Kotlin/Native guide](../README.md) › [compiler](README.md) › klibs-and-linking

# Klibs and linking

*Skip this page if you know how a klib is laid out, what a signature is and what partial linkage does.*

A [**klib**][klib] is the Kotlin library format for every backend except JVM: the output of the first stage and the input of
the second. It stores a library as "half-compiled Kotlin": checked, resolved, but with no machine code. **Linking** here
means IR linking: the second stage loads many klibs and connects them into one program. (The system linker comes much later,
see [compiler/](README.md).)

## What a klib holds

```kotlin
// shapes.kt, compiled with: konanc shapes.kt -p library -o shapes
package shapes

interface Shape { fun area(): Double }
class Circle(val r: Double) : Shape { override fun area() = 3.14 * r * r }
fun total(shapes: List<Shape>): Double = shapes.sumOf { it.area() }
```

A klib is a zip archive. `unzip -l shapes.klib`, shortened:

```
default/manifest                              name, dependencies, versions, targets
default/linkdata/module                       METADATA: what the library declares, as the frontend sees it
default/linkdata/package_shapes/0_shapes.knm    one directory per package
default/ir/irDeclarations.knd                 IR: every declaration...
default/ir/bodies.knb                           ...and every function body, kept apart so it can be read on demand
default/ir/signatures.knt                       the signatures this library declares and refers to (see below)
default/ir/types.knt, strings.knt, files.knf, fileEntries.knf, debugInfo.knd
default/indices/signatures.idx                the same signatures as a quick index
default/targets/linux_x64/native/             empty here; interop libraries keep LLVM bitcode in it
default/targets/linux_x64/included/           empty here; interop libraries can bundle static libraries in it
```

The manifest is a properties file:

```
unique_name=shapes                the library's identity
depends=stdlib                    libraries that must be present when this one is used
native_targets=linux_x64
abi_version=2.5.0                 format version, and the compiler that wrote the library
compiler_version=2.5.255-SNAPSHOT
```

## Metadata and IR: two descriptions of the same code

| | Metadata (`linkdata/`) | IR (`ir/`) |
|---|---|---|
| Contains | Declarations only: classes, function headers, types, annotations | Declarations and all function bodies |
| Who reads it | The frontend, when it compiles code that *uses* this library. Also the IDE | The second stage, to generate code |
| Looks like | `public final fun total(shapes: List<Shape>): Double` | The tree behind `shapes.sumOf { it.area() }` |

The frontend never needs bodies, and the backend needs more than headers, so the two are stored separately. The `klib` tool
(in `kotlin-native/dist/bin`) prints either. → [cheat-sheet.md](../reference/cheat-sheet.md)

## How several klibs become one IR

Inside a klib, IR does not point at other libraries directly. A reference to anything public is stored as a **signature**:
a string that identifies a declaration by package, name and parameter types. `klib dump-signatures shapes.klib` shows both
directions:

```
// Declared signatures: 8
shapes/Circle.<init>|<init>(kotlin.Double){}[0]
shapes/Circle.area|area(){}[0]
shapes/Shape|null[0]
shapes/total|total(kotlin.collections.List<shapes.Shape>){}[0]
...
// Imported signatures: 17                       all from stdlib in this case
kotlin.collections/Iterator.next|next(){}[0]
kotlin/Double.times|times(kotlin.Double){}[0]
kotlin.collections/sumOf|sumOf@kotlin.collections.Iterable<0:0>(kotlin.Function1<0:0,kotlin.Double>){0§<kotlin.Any?>}[0]
...
```

Whenever the second stage meets an imported signature, it looks for a library that declares the same signature and connects
the two. After this step a call to `sumOf` in `shapes` points at the real `sumOf` function from the stdlib klib, with its
body.

The same naming scheme later gives the function its symbol name in the binary:
`kfun:shapes#total(kotlin.collections.List<shapes.Shape>){}kotlin.Double`. → [codegen.md](codegen.md#a-function-and-its-name)

## Interop klibs

`cinterop` turns C or Objective-C headers into a klib too. Such a klib has metadata but no `ir/` directory, its manifest
says `interop=true`, and `targets/<target>/native/` holds bitcode compiled from C. → [cinterop.md](../interop/cinterop.md)

## Partial linkage

Libraries are compiled separately, so the klibs given to the second stage may not match. Typically library `mid` was
compiled against `lib` 1.0 and calls `removedLater()`, but the application brings `lib` 2.0, where that function is gone.
The signature that `mid` imports is then declared nowhere. Failing the build would be too strict: the application may never
reach that call. So the compiler replaces the broken call with code that throws, and the program fails only if it gets
there:

```
warning: <mid> @ mid.kt:3:39: Function 'removedLater' can not be called:      (shown with -Xpartial-linkage-loglevel=warning)
    No function found for symbol 'lib/removedLater|removedLater(){}[0]'

Uncaught Kotlin exception: kotlin.internal.IrLinkageError: Function 'removedLater' can not be called: ...
```

## Things to know

- **A signature is the declaration's binary identity.** Changing the package, the name or a parameter type gives a
  different signature, and libraries compiled against the old one no longer link. Renaming a parameter changes nothing.
- **IR is loaded on demand.** From a dependency, the compiler reads only the declarations the program refers to, directly
  or indirectly, not the whole library.
- **Partial linkage is silent by default.** `-Xpartial-linkage-loglevel=warning` shows every mismatch at compile time.
- **The second stage has a "K1 frontend" step.** It compiles no sources. It only loads the metadata of the klibs in the
  form used by the old frontend (descriptors), because some parts of the backend, such as Objective-C export, still
  work with it.

## Where the code is

- [writeKlib.kt](../../../compiler/cli/cli-native-klib/src/main/kotlin/org/jetbrains/kotlin/native/writeKlib.kt): the first
  stage writes the klib.
- [linkKlibs.kt](../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/linkKlibs.kt):
  the second stage loads and links klibs.
- [KonanIrLinker.kt](../../../compiler/ir/serialization.native/src/org/jetbrains/kotlin/backend/konan/serialization/KonanIrLinker.kt):
  the Native IR linker; picks how each kind of klib (regular, interop, cached) is read.
- [PartialLinkageSupportForLinkerImpl.kt](../../../compiler/ir/serialization.common/src/org/jetbrains/kotlin/backend/common/linkage/partial/PartialLinkageSupportForLinkerImpl.kt):
  partial linkage, shared with the JS and Wasm backends.

[klib]: https://kotlinlang.org/docs/native-libraries.html
