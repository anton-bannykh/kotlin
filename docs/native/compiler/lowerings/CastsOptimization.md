# CastsOptimization

Finds casts and type checks whose outcome is already known from the surrounding code, and marks or folds them so that no
run-time check is generated.

**Why:** in debug builds implicit casts are checked at run time (the `genericSafeCasts` binary option; see
[type-checks.md](../optimizations/type-checks.md)). This catches unsound generic code early, but smart casts are implicit
casts too, and there are a lot of them. Without this pass each one would cost a real check.

```kotlin
fun smart(a: Any): String = if (a is Dog) a.bark() else ""
fun first(dogs: List<Dog>): String = dogs.first().bark()

// before
fun smart(a: Any): String = when {
    a is Dog -> (a /*as Dog*/).bark()               // implicit cast from the smart cast
    else -> ""
}

// after
fun smart(a: Any): String = when {
    a is Dog -> noCastNeeded { a /*as Dog*/ }.bark()    // proven by the `is` above: marked, will not be checked
    else -> ""
}

// what the binary finally contains (after CastsLowering)
a is Dog -> a.bark()                                                // no check
downcast<Dog?>(dogs.first(), Dog::class, nullable = true).bark()    // nothing proves this one: checked
// with this pass disabled, `smart` gets a check too:
a is Dog -> downcast<Dog?>(a, Dog::class, nullable = true).bark()
```

**Know this**
- It only runs when `genericSafeCasts` is on. That is the default without `-opt`; with `-opt` the option is off, implicit
  casts are not checked at all, and there is nothing for this pass to save.
- The analysis is per function: it tracks facts like "`a` is `Dog`" and "`a` is not null" along the control flow, through
  branches, loops and `try`. If a function is too complex for it, the function is left unchanged.
- `noCastNeeded` above is a block with the origin `NO_CAST_NEEDED`. [Autoboxing](Autoboxing.md), which is where implicit
  casts are turned into checked ones, skips such blocks.

**Source:** [CastsOptimization.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/optimizations/CastsOptimization.kt), the default of the option in
[NativeSecondStageCompilationConfig.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/NativeSecondStageCompilationConfig.kt)
· **Runs:** after inlining, only when generic casts are checked (debug builds by default).
