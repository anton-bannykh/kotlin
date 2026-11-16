# SpecialObjCValidationLowering

Reports a compilation error when a `suspend` function is called inside `autoreleasepool { }`. It changes nothing in the IR.

**Why:** `autoreleasepool` (from `kotlinx.cinterop`) opens an Objective-C autorelease pool, runs the block and closes the
pool. Pools form a stack. If the block suspends, the coroutine's caller goes on while the pool is still open; when the
caller closes its own pool, the inner one is closed with it, and resuming the coroutine later crashes.

```kotlin
inline fun <T> myPool(block: () -> T) = autoreleasepool(block)
fun later(f: suspend () -> Unit) { ... }

suspend fun load() {
    autoreleasepool { fetch() }             // error: calling suspend functions from `autoreleasepool {}` is prohibited
    myPool { fetch() }                      // the same error, although `autoreleasepool` is not called here directly
    autoreleasepool { later { fetch() } }   // allowed: the call is in a local function, not in the block itself
}
```

**Know this**
- The check is not in the frontend because of the indirect case (`myPool`). After inlining it is easy: the block's code sits
  inside the marker that the inliner leaves around the pasted body of `autoreleasepool`, however the call was written.
  See [NativeInlining](NativeInlining.md).

**Source:** [SpecialObjCValidationLowering.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/SpecialObjCValidationLowering.kt)
· **Runs:** right after `NativeAllFunctionInlining`, always.
