# CoroutinesLivenessAnalysis, CoroutinesLivenessAnalysisFallback

Compute, for every suspension point of a coroutine, which local variables are still needed after it. They do not change the
IR: the answer is attached to each suspension point and used later.

**Why:** when a coroutine suspends, `invokeSuspend` returns and its stack frame is gone. Every local variable that is read
after the resume must be saved in the coroutine object first. Saving only those keeps coroutine objects small. See
[coroutines.md](../../features/coroutines.md).

```kotlin
// inside `$twiceCOROUTINE$0`.invokeSuspend, before and after (the IR is the same)
var suspendResult: Any?
val first = suspensionPoint(id0) { ... fetch(this.id) ... } as String
                                    // live here: nothing. `id` is already a field, `first` does not exist yet
val next = this.id.plus(first.length)
val second = suspensionPoint(id1) { ... fetch(next) ... } as String
                                    // live here: first. `next` was only needed to make the call
return first.plus(second)

// what ends up saved at suspension point 1:
//   CoroutinesLivenessAnalysis           first
//   CoroutinesLivenessAnalysisFallback   suspendResult, first, next     (everything in scope)
```

**Know this**
- Only one of the two does any work. `CoroutinesLivenessAnalysis` is a real backward data-flow analysis (shared with other
  backends). The fallback takes every variable visible at the suspension point, and only runs if the first one was switched
  off with `-Xdisable-phases=CoroutinesLivenessAnalysis`. Saving too much is still correct, only wasteful.
- The result has two users: the data-flow graph that the whole-program optimizations work on, and
  [CoroutinesVarSpillingLowering](CoroutinesVarSpillingLowering.md), which emits the actual saves.
- With `-opt`, the pre-codegen inliner runs the analysis again on every function it inlined something into.

**Source:** [CoroutinesLivenessAnalysis.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/CoroutinesLivenessAnalysis.kt), the analysis itself in
[LivenessAnalysis.kt](../../../../compiler/ir/backend.common/src/org/jetbrains/kotlin/backend/common/lower/optimizations/LivenessAnalysis.kt)
· **Runs:** after inlining, always, after [NativeSuspendFunctionsLowering](NativeSuspendFunctionsLowering.md).
