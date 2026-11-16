# NativeSuspendFunctionsLowering

Turns a `suspend` function that has to pause in the middle into a **coroutine class**: an object that holds the function's
state and whose `invokeSuspend` method is the old body, restartable at each suspension point.

**Why:** a native function cannot return to its caller and later continue from the middle. An object that remembers where
it stopped (`label`) and is called again on resume can. The whole story is in [coroutines.md](../../features/coroutines.md).

```kotlin
// before
suspend fun tail(id: Int): String = fetch(id + 1)           // the only suspend call is the last thing it does
suspend fun twice(id: Int): String {
    val first = fetch(id)                                   // suspension point 0
    val next = id + first.length
    val second = fetch(next)                                // suspension point 1
    return first + second
}

// after
suspend fun tail(id: Int): String = fetch(id.plus(1))       // unchanged: a tail call needs no state
suspend fun twice(id: Int): String =                        // only creates the state machine and starts it
    `$twiceCOROUTINE$0`(id, getContinuation<String>()).invokeSuspend(Result(Unit))

private class `$twiceCOROUTINE$0`(val id: Int, completion: Continuation<String>) : ContinuationImpl(completion) {
    private var label: NativePtr                            // where to resume; null on the first call
    override fun invokeSuspend(result: Result<Any?>): Any? {
        var suspendResult: Any?
        suspendable(label) {                                // "if label is set, jump to it"
            val first = suspensionPoint(id0) {
                saveCoroutineState(); label = id0
                suspendResult = fetch(this.id)
                if (suspendResult === COROUTINE_SUSPENDED) return suspendResult    // callee paused: so do we
                suspendResult                               // callee finished at once: go on with its value
            } resume { restoreCoroutineState(); result.getOrThrow() } as String    // entered here on resume
            val next = this.id.plus(first.length)
            val second = suspensionPoint(id1) { /* same shape, calls fetch(next) */ } as String
            return first.plus(second)
        }
    }
}
```

**Know this**
- A function gets a class only if it has a suspend call that is not a tail call. A call inside `try` is never a tail call.
- `suspendable` and `suspensionPoint` stand for two special IR nodes (`IrSuspendableExpression`, `IrSuspensionPoint`)
  that stay until code generation. The Kotlin-like IR dump cannot print them: dump this phase in the default format.
- Parameters become fields at once. Local variables do not: `saveCoroutineState`/`restoreCoroutineState` are placeholders,
  filled in much later by [CoroutinesVarSpillingLowering](CoroutinesVarSpillingLowering.md).

**Source:** [NativeSuspendFunctionLowering.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/NativeSuspendFunctionLowering.kt), shared base
[AbstractSuspendFunctionsLowering.kt](../../../../compiler/ir/backend.common/src/org/jetbrains/kotlin/backend/common/lower/AbstractSuspendFunctionsLowering.kt)
· **Runs:** after inlining, always · **Next:** [NativeAddContinuationToFunctionCallsLowering](NativeAddContinuationToFunctionCallsLowering.md).
