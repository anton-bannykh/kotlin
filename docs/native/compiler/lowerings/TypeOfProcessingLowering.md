# TypeOfProcessingLowering

Replaces each call of `typeOf<T>()` with a constant `KType` object that describes `T`.

**Why:** types do not exist at run time. Everything `typeOf` can report (classifier, type arguments, nullability) is known
to the compiler, so the whole `KType` is built at compile time and stored in the binary as constant data. Nothing
is allocated when the code runs.

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

fun <T> generic() = const KTypeImpl(
    classifier = const KClassImpl<List<*>>,
    arguments = const KTypeProjectionList(variance = [0], type = [const KTypeImpl(
        classifier = const KTypeParameterImpl(name = "T", containerFqName = "generic(){0§<kotlin.Any?>}kotlin.reflect.KType",
                                              upperBoundsArray = [/* KType of Any? */], varianceId = 0, isReified = false),
        arguments = [], isMarkedNullable = false)]),
    isMarkedNullable = false)
```

**Know this**
- It runs after inlining because of `inline fun <reified T> f() = typeOf<List<T>>()`: only the inlined copy has a concrete
  type, for example `typeOf<List<Int>>()`.
- A type parameter that is not reified stays a type parameter: it is described by a `KTypeParameterImpl`, as in `generic`.
- A non-reified type parameter with a recursive upper bound (`T : Comparable<T>`) is reported as a compilation error.

**Source:** [TypeOfProcessingLowering.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/TypeOfProcessingLowering.kt),
[NativeReflectionIrBuilder.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/NativeReflectionIrBuilder.kt)
(builds the constants) · **Runs:** after inlining, always.
