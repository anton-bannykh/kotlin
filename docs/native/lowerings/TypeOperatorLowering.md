# TypeOperatorLowering

Simplifies two cast operators: `as?` is rewritten into an `is` check, and `as T` gets a class instead of a type parameter
as its target.

**Why:** at run time an object only knows its class, not its type arguments, so a check can only be made against a class
([type-checks.md](../optimizations/type-checks.md)). And with `as?` gone, code generation has one cast operator fewer to
implement.

```kotlin
// before
fun safe(a: Any): Dog? = a as? Dog
fun <T : Animal> bounded(a: Any?): T = a as T
fun <T> generic(a: Any?): T = a as T
fun plain(a: Any): Dog = a as Dog
fun check(a: Any?): Boolean = a is Dog

// after
fun safe(a: Any): Dog? = when {
    a is Dog -> noCastNeeded { a /*as Dog*/ }        // implicit cast; the `is` just proved it, so it is never checked
    else -> null
}
fun <T : Animal> bounded(a: Any?): T = a as Animal  // T erased to its upper bound: this is all that can be checked
fun <T> generic(a: Any?): T = a as Any?             // upper bound is Any?: this cast can never fail
fun plain(a: Any): Dog = a as Dog                   // unchanged; see CastsLowering
fun check(a: Any?): Boolean = a is Dog              // unchanged; code generation compiles `is` directly
```

**Know this**
- `a as T` therefore does not fail where you might expect. `generic<String>(42)` returns normally. In a debug build the
  caller then throws `ClassCastException`, because it checks the implicit cast of the result to `String`. With `-opt`
  nothing is checked, and the `Int` is used as if it were a `String`.
- In a debug build, [CastsOptimization](CastsOptimization.md) runs first and has already removed casts like the one in
  `generic`, so this lowering sees fewer of them.
- A cast to an Objective-C forward declaration (`@class Foo;` with no definition) is checked against `Any` only: there is
  no class to compare with.

**Source:** [TypeOperatorLowering.kt](../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/TypeOperatorLowering.kt)
· **Runs:** after inlining, always · **Next:** [CastsLowering](CastsLowering.md) turns the remaining `as` into a call.
