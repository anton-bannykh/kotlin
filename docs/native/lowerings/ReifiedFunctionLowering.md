# ReifiedFunctionLowering

Replaces the body of every inline function that has a `reified` type parameter with a `throw`. Default values of its
parameters are replaced too.

**Why:** after inlining, each call site has its own copy of the body with the real type in place of `T`. The original
declaration is still in the IR and would be compiled like any other function, but its body uses `T` as a real type
(`x is T`), and there are no type arguments at run time to compile that against.

```kotlin
// before (inlining is already done)
inline fun <reified T> isA(x: Any, fallback: Boolean = x is T): Boolean = x is T || fallback

fun check(x: Any): Boolean {
    val fallback = x is String                  // the call isA<String>(x) was inlined: T is String here
    return x is String || fallback
}

// after
inline fun <reified T> isA(
    x: Any,
    fallback: Boolean = throw ThrowIllegalStateExceptionWithMessage("unsupported call of reified inlined function `isA`")
): Boolean {
    throw ThrowIllegalStateExceptionWithMessage("unsupported call of reified inlined function `isA`")
}

fun check(x: Any): Boolean { ... }              // unchanged
```

**Know this**
- The function is kept, only emptied. If you see this exception at run time, a call of a reified function was not inlined.

**Source:** [ReifiedFunctionLowering.kt](../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/ReifiedFunctionLowering.kt)
· **Runs:** after inlining, always.
