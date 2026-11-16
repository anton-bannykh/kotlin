[Kotlin/Native guide](../../README.md) › [compiler](../README.md) › [lowerings](README.md) › inlining

# Inlining

*Skip this page if you know where `inline` functions are inlined and what becomes of `reified`, `typeOf` and `::class`.
Reference material.*

Inlining divides the lowering list in two: some rewrites only make sense once type parameters have been replaced by real
types. This page covers the inliner and the lowerings that clean up after it:

`NativePrivateFunctionInlining → NativeAllFunctionInlining → ReifiedFunctionLowering → TypeOfProcessingLowering →
PostInlineLowering`

## NativeAllFunctionInlining

Together with `NativePrivateFunctionInlining`, replaces every call of an `inline` function with a copy of the function's
body. Lambdas passed to it are pasted into that copy, so no function object is created for them. `reified` type parameters
and non-local `return` only work this way.

```kotlin
// before
private inline fun twice(block: (Int) -> Unit) { block(0); block(1) }
fun a() { twice { println(it) } }
fun b(list: List<String>) { list.forEach { println(it) } }      // forEach: a public inline function in the stdlib klib

// after NativePrivateFunctionInlining: only `a` changed
fun a() {
    { val it = 0; println(it) }                 // the body of `twice`; each `block(...)` became the lambda's body
    { val it = 1; println(it) }
}

// after NativeAllFunctionInlining
fun b(list: List<String>) {
    val iterator = list.iterator()              // the body of `forEach`, loaded from the stdlib klib
    while (iterator.hasNext()) {
        val element: Any? = iterator.next()
        val it: String = element /* implicit cast */
        println(it)
    }
}
```

**Already done in the first stage.** The same inliner also runs before the klib is written: within a module from language
version 2.4, across modules from 2.5. With 2.5, the default in this repository, the example arrives in the second stage
already inlined and these two phases change nothing in it. The dumps above were made with `-language-version 2.3`.

**Why two phases.** Inlining a private inline function is a per-file job like any other lowering. A non-private one is
copied into other files and klibs. Before that, its body must be free of calls to private inline functions (the first
phase), and the private declarations it still uses need accessors (`SyntheticAccessorLowering` runs in between). The
second phase reads bodies from other files, so it starts only when every file has finished all earlier lowerings.

- The body of an inline function from another klib is loaded from that klib when the first call is met, and the lowerings
  that precede inlining are applied to it on the spot.
- Each pasted body stays wrapped in a marker (`IrInlinedFunctionBlock`) that records which function it came from.

**Source:** [NativeIrInliner.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/NativeIrInliner.kt),
[FunctionInlining.kt](../../../../compiler/ir/ir.inline/src/org/jetbrains/kotlin/ir/inline/FunctionInlining.kt) (the shared inliner)
· **Runs:** always; the last steps of the "before inlining" list.

## ReifiedFunctionLowering

After inlining, each call site has its own copy of the body with the real type in place of `T`. The original declaration
is still compiled like any other function, but its body uses `T` as a real type and there are no type arguments at run
time. So its body, and the default values of its parameters, are replaced with a `throw`.

```kotlin
// before (the call isA<String>(x) is already inlined elsewhere, with T = String)
inline fun <reified T> isA(x: Any, fallback: Boolean = x is T): Boolean = x is T || fallback

// after
inline fun <reified T> isA(x: Any, fallback: Boolean = throw ThrowIllegalStateExceptionWithMessage(/* same */)): Boolean {
    throw ThrowIllegalStateExceptionWithMessage("unsupported call of reified inlined function `isA`")
}
```

- If you see this exception at run time, a call of a reified function was not inlined.

**Source:** [ReifiedFunctionLowering.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/ReifiedFunctionLowering.kt) · **Runs:** after inlining, always.

## TypeOfProcessingLowering

Replaces each call of `typeOf<T>()` with a constant `KType` object. Everything `typeOf` can report is known to the
compiler, so the whole object is stored in the binary as constant data and nothing is allocated at run time.

```kotlin
// before
fun direct() = typeOf<List<String?>>()
fun <T> generic() = typeOf<List<T>>()

// after: `const` marks a constant object, not a constructor call
fun direct() = const KTypeImpl(
    classifier = const KClassImpl<List<*>>,
    arguments = const KTypeProjectionList(                          // one argument, invariant
        variance = [0],
        type = [const KTypeImpl(classifier = const KClassImpl<String>, arguments = [], isMarkedNullable = true)]),
    isMarkedNullable = false)

// generic(): the same shape, but the classifier of the argument is
//     const KTypeParameterImpl(name = "T", containerFqName = "generic(){0§<kotlin.Any?>}kotlin.reflect.KType", ...)
```

- It runs after inlining because of `inline fun <reified T> f() = typeOf<List<T>>()`: only the inlined copy has a concrete
  type, for example `typeOf<List<Int>>()`.
- A non-reified type parameter with a recursive upper bound (`T : Comparable<T>`) is reported as a compilation error.

**Source:** [TypeOfProcessingLowering.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/TypeOfProcessingLowering.kt),
[NativeReflectionIrBuilder.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/NativeReflectionIrBuilder.kt) (builds the constants)
· **Runs:** after inlining, always.

## PostInlineLowering

Rewrites three kinds of expressions that are only final once inlining is done: class references, `immutableBlobOf(...)`,
and explicit constructor calls of the `KType` implementation classes with constant arguments, which are folded into a
constant object (`typeOf` itself is handled above).

```kotlin
// before
fun show(x: Any) {
    println(String::class)
    println(x::class)
}
fun blob() = immutableBlobOf(1, 2, 255)

// after
fun show(x: Any) {
    println(const KClassImpl<String>)                           // a constant object: nothing is allocated
    println(KClassImpl<Any>(typeInfo = getObjectTypeInfo(x)))   // a new wrapper around the object's type information
}
fun blob() = immutableBlobOfImpl(data = "\u0001\u0002ÿ")        // the bytes, packed into one string constant
```

`getObjectTypeInfo` reads the pointer to the class description that every object starts with, see
[object-model.md](../../runtime/object-model.md).

- `immutableBlobOf` takes a `vararg`. It is rewritten here so that
  [VarargInjectionLowering](functions.md#vararginjectionlowering) never sees it and builds no array at run time. Every
  element must be a constant between 0 and 255.
- For some interop types (Objective-C protocols, Kotlin subclasses of Objective-C classes, C structs and other
  `NativePointed` types) `Foo::class` becomes a constant "unsupported" object that carries a message, not a real `KClass`.

**Source:** [PostInlineLowering.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/PostInlineLowering.kt) · **Runs:** after inlining, always.

## ContractsDslRemover

Meant to delete the stdlib classes of the contracts DSL (marked `@ContractsDsl`, such as `ContractBuilder`), which nothing
needs at run time. **In the current code it changes nothing:** for an annotated class it returns `null` to the list helper
`transformFlat`, where `null` means "keep the element", so the class stays. `contract { }` calls are not its job either:
the K2 frontend drops them when the IR is built, before any lowering.

**Source:** [ContractsDslRemover.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/ContractsDslRemover.kt) · **Runs:** after inlining, always.
