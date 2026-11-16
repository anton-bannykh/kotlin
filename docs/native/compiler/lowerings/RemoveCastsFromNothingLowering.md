# RemoveCastsFromNothingLowering

Removes any cast or type check whose argument has type `Nothing`.

**Why:** an expression of type `Nothing` never produces a value, so the cast around it can never run. Removing it here
means the analysis in [CastsOptimization](CastsOptimization.md) and the cast lowerings after it have less to look at.

```kotlin
fun nothing(): String = error("no") as String

// before (`error` is inline, so its body is already here)
fun nothing(): String {
    return {
        val message: String = "no"
        throw IllegalStateException(message.toString())     // the block has type Nothing
    } as String
}

// after
fun nothing(): String {
    return {
        val message: String = "no"
        throw IllegalStateException(message.toString())
    }                                                       // cast gone
}
```

**Know this**
- It applies to every type operator, not only `as`: `as?`, `is`, `!is` and implicit casts are dropped the same way.
- Such casts mostly appear after inlining, when a function body turns out to always throw.

**Source:** [RemoveCastsFromNothingLowering.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/RemoveCastsFromNothingLowering.kt)
· **Runs:** after inlining, always, right after the second [ComputeTypesPass](ComputeTypesPass.md).
