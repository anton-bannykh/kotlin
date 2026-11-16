# NativeFinallyBlocksLowering

Removes `finally`. The code of the `finally` block is copied onto every path that leaves the `try`: normal completion,
an exception, and each `return`, `break` or `continue` that jumps out of it.

**Why:** code generation only knows `try`/`catch` (see [exceptions.md](../../features/exceptions.md)). "Run this no matter how we
leave" has to be spelled out as ordinary control flow.

```kotlin
// before
fun f(x: Int): Int {
    try {
        if (x < 0) return -1
        println(x)
    } catch (e: IllegalStateException) { println("caught") }
    finally { println("done") }
    return 0
}

// after (the labels are added here for readability; in IR these are "returnable blocks")
fun f(x: Int): Int {
    normal@ {
        return {                                   // exit 3: the `return -1` inside the try
            val result: Int = returning@ {
                try {
                    try {
                        if (x < 0) return@returning -1
                        println(x)
                        return@normal              // was: reaching the end of the try
                    } catch (e: IllegalStateException) { println("caught"); return@normal }
                } catch (t: Throwable) {           // exit 2: an exception that was not caught, or thrown by a catch
                    println("done"); throw t
                }
            }
            println("done")                        // the value to return is already computed here
            result
        }
    }
    println("done")                                // exit 1: normal completion
    return 0
}
```

**Know this**
- The `finally` code is duplicated: once for normal completion, once for exceptions, and once per distinct jump target.
- This is the shared `FinallyBlocksLowering`. The Native class only says which type the catch-all clause uses (`Throwable`).

**Source:** [FinallyBlocksLowering.kt](../../../../compiler/ir/backend.common/src/org/jetbrains/kotlin/backend/common/lower/FinallyBlocksLowering.kt) (shared),
[NativeFinallyBlocksLowering.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/NativeFinallyBlocksLowering.kt)
· **Runs:** after inlining, always.
