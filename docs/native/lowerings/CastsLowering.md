# CastsLowering

Turns every remaining `as` cast into a call to `downcast`, a small function in the standard library that does the check
and throws `ClassCastException` or `NullPointerException`. A cast to a superclass is dropped or reduced to a null check.

**Why:** compiled inline, each cast is a null test, a type check and a call that throws. In debug builds casts are
everywhere, because implicit casts at generic boundaries are checked too ([CastsOptimization](CastsOptimization.md)), so
this made the LLVM IR much bigger and slowed LLVM down. One call per cast keeps it small. How the check itself works is in
[type-checks.md](../optimizations/type-checks.md).

```kotlin
// before
fun plain(a: Any): Dog = a as Dog
fun up(d: Dog?): Animal = d as Animal
fun first(dogs: List<Dog>): Dog = dogs.first() as Dog?      // was an implicit cast; Autoboxing made it a real one

// after
fun plain(a: Any): Dog = downcast<Dog>(a, Dog::class, nullable = false)
fun up(d: Dog?): Animal = checkNotNull<Animal>(d)            // Dog is an Animal: only null can go wrong
fun first(dogs: List<Dog>): Dog = downcast<Dog?>(dogs.first(), Dog::class, nullable = true)
```

**Know this**
- It runs only without `-opt`. With `-opt` the casts stay in the IR as they are, and code generation compiles the same
  cases inline (null test, type check, throw).
- Only `as` is handled. `is` is always compiled by code generation, and `as?` was already rewritten by
  [TypeOperatorLowering](TypeOperatorLowering.md).
- Casts to Objective-C classes are left for code generation, which checks them in a different way.

**Source:** [CastsLowering.kt](../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/CastsLowering.kt); the inline version is `evaluateCast` in
[IrToBitcode.kt](../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/llvm/IrToBitcode.kt)
· **Runs:** after inlining, last in the list, only without `-opt`.
