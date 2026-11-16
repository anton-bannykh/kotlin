# CoroutinesVarSpillingLowering

Saves local variables into fields of the coroutine object before each suspension point and loads them back after the
resume. This is called **spilling**.

**Why:** a suspended coroutine has no stack frame, so whatever must survive a suspension has to live in the coroutine
object on the heap. See [coroutines.md](../coroutines.md).

```kotlin
// before: `$twiceCOROUTINE$0`.invokeSuspend with placeholders
val first = suspensionPoint(id0) {
    saveCoroutineState(); label = id0                   // nothing is live here
    ... fetch(this.id) ...
} resume { restoreCoroutineState(); result.getOrThrow() } as String
val next = this.id.plus(first.length)
val second = suspensionPoint(id1) {
    saveCoroutineState(); label = id1                   // live here: first
    ... fetch(next) ...
} resume { restoreCoroutineState(); result.getOrThrow() } as String
return first.plus(second)

// after
class `$twiceCOROUTINE$0` { ...; private var first: String }     // new field, one per spilled variable
var first = suspensionPoint(id0) {                      // `val` became `var` so it can be reloaded
    label = id0                                         // placeholder gone: nothing to save
    ... fetch(this.id) ...
} resume { result.getOrThrow() } as String
val next = this.id.plus(first.length)
val second = suspensionPoint(id1) {
    this.first = first; label = id1                     // save
    ... fetch(next) ...
} resume { first = this.first; result.getOrThrow() } as String      // restore
return first.plus(second)
```

**Know this**
- It runs late, after devirtualization, the pre-codegen inliner and dead code elimination. Until then those optimizations
  see plain local variables and the `saveCoroutineState` placeholder instead of field reads and writes.
- Which variables to save was decided earlier by [CoroutinesLivenessAnalysis](CoroutinesLivenessAnalysis.md).
- Every spilled variable gets its own field. Fields are not shared between variables, even if they are never live together.

**Source:** [CoroutinesVarSpillingLowering.kt](../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/CoroutinesVarSpillingLowering.kt)
· **Runs:** between the whole-program optimizations (after dead code elimination, just before code generation), always.
