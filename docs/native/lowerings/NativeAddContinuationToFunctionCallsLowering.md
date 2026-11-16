# NativeAddContinuationToFunctionCallsLowering

Passes the continuation explicitly at every call of a suspend function. Its partner, the shared
`AddContinuationToNonLocalSuspendFunctionsLowering`, runs just before it and adds the matching parameter to every suspend
function.

**Why:** a suspend function must know whom to resume when it finishes. That "whom" is a `Continuation` object, hidden in
source code. After these two lowerings it is an ordinary last argument, and `suspend` functions are ordinary functions.
See [coroutines.md](../coroutines.md).

```kotlin
// before (after NativeSuspendFunctionsLowering)
suspend fun fetch(id: Int): String = "v".plus(id)
suspend fun tail(id: Int): String = fetch(id.plus(1))
suspend fun twice(id: Int): String =
    `$twiceCOROUTINE$0`(id, completion = getContinuation<String>()).invokeSuspend(Result(Unit))

// after AddContinuationToNonLocalSuspendFunctionsLowering: declarations change
fun fetch(id: Int, `$completion`: Continuation<String>): Any = ...     // returns Any: a String or COROUTINE_SUSPENDED
fun tail(id: Int, `$completion`: Continuation<String>): Any = ...
fun twice(id: Int, `$completion`: Continuation<String>): Any = ...

// after NativeAddContinuationToFunctionCallsLowering: calls change
fun tail(id: Int, `$completion`: Continuation<String>): Any =
    fetch(id.plus(1), `$completion`)                                   // tail call: hand over our own continuation
fun twice(id: Int, `$completion`: Continuation<String>): Any =
    `$twiceCOROUTINE$0`(id, completion = `$completion`).invokeSuspend(Result(Unit))    // was getContinuation()
```

**Know this**
- Which continuation is passed depends on where the call is. In a plain function it is the function's own `$completion`
  parameter. Inside `invokeSuspend` of a coroutine class it is `this`: the coroutine object is itself the continuation of
  the calls it makes.
- The return type becomes `Any` because a suspend function can return either its result or the marker
  `COROUTINE_SUSPENDED`.

**Source:** [NativeAddContinuationToFunctionCallsLowering.kt](../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/NativeAddContinuationToFunctionCallsLowering.kt), shared
[AddContinuationToFunctionCallsLowering.kt](../../../compiler/ir/backend.common/src/org/jetbrains/kotlin/backend/common/lower/coroutines/AddContinuationToFunctionCallsLowering.kt) and
[AddContinuationToFunctionsLowering.kt](../../../compiler/ir/backend.common/src/org/jetbrains/kotlin/backend/common/lower/coroutines/AddContinuationToFunctionsLowering.kt)
· **Runs:** after inlining, always, right after [NativeSuspendFunctionsLowering](NativeSuspendFunctionsLowering.md).
