# Control flow: `tailrec`, `finally`, `for`

*Skip this page if you know that `tailrec` becomes a loop, `finally` is copied onto every exit, and `for` loses its iterator.*

Three independent lowerings that leave only plain loops, jumps and `try`/`catch` for code generation. All three are thin
Native wrappers around lowerings shared with the other backends. They run in this order.

## NativeTailrecLowering

Turns a `tailrec` function into a loop: each recursive call in tail position becomes "assign the new argument values and
jump back to the start". `tailrec` promises that the stack does not grow, and a call in LLVM IR gives no such guarantee.

```kotlin
// before
tailrec fun gcd(a: Int, b: Int): Int =
    if (b == 0) a else gcd(b, a % b)

// after
tailrec fun gcd(a: Int, b: Int): Int {
    var a = a                          // parameters cannot be assigned, so they are copied to variables
    var b = b
    do {
        return if (b == 0) a else {
            val tmp0 = b               // all new values are computed first...
            val tmp1 = a % b
            a = tmp0                   // ...and only then assigned, because `a % b` still needs the old `a`
            b = tmp1
            continue                   // was: the recursive call
        }
        break
    } while (true)
}
```

**Know this**
- A recursive call that is not in tail position stays an ordinary call.
- It must run after local functions were moved out, and before `NativeFinallyBlocksLowering` and the
  [default-argument lowerings](functions.md#nativedefaultargumentstubgenerator).

**Source:** [TailrecLowering.kt](../../../../compiler/ir/backend.common/src/org/jetbrains/kotlin/backend/common/lower/TailrecLowering.kt) (shared)
· **Runs:** after inlining, always.

## NativeFinallyBlocksLowering

Removes `finally`. The block is copied onto every path that leaves the `try`: normal completion, an exception, and each
`return`, `break` or `continue` that jumps out. Code generation only knows `try`/`catch`
(→ [exceptions.md](../../features/exceptions.md)).

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

**Source:** [FinallyBlocksLowering.kt](../../../../compiler/ir/backend.common/src/org/jetbrains/kotlin/backend/common/lower/FinallyBlocksLowering.kt) (shared)
· **Runs:** after inlining, always.

## NativeForLoopsLowering

Rewrites `for` over ranges, progressions (`until`, `downTo`, `step`, `indices`, ...) and arrays into a counting loop. The
frontend expresses every `for` through an iterator object: an allocation and several calls per iteration where a counter
and a comparison are enough.

```kotlin
// before: `for (i in 0 until n) s += i`                   // after
val iterator = (0 until n).iterator()                      var inductionVariable = 0
while (iterator.hasNext()) {                               if (inductionVariable < n) do {
    val i = iterator.next()                                    val i = inductionVariable
    s += i                                                     inductionVariable += 1
}                                                              s += i
                                                           } while (inductionVariable < n)

// `for (x in a) s += x` with `a: IntArray` becomes
val indexedObject = a
var inductionVariable = 0
val last = indexedObject.size
while (inductionVariable < last) {
    val x = indexedObject.getWithoutBoundCheck(inductionVariable)   // the index is known to be in range
    inductionVariable += 1
    s += x
}
```

**Know this**
- The Native-specific part is **bounds-check elimination**. The shared lowering builds the loop; Native plugs in a body
  transformer that replaces `a[i]` with `getWithoutBoundCheck` (and `a[i] = v` with `setWithoutBoundCheck`) when the loop
  bounds prove the index valid, for example inside `for (i in a.indices)`.
- A closed range `1..n` ends with the test `i != n` instead of `inductionVariable <= n`, so it also works when `n` is
  `Int.MAX_VALUE`. Loops over other things, such as a `List`, keep their iterator.

**Source:** [ForLoopsLowering.kt](../../../../compiler/ir/backend.common/src/org/jetbrains/kotlin/backend/common/lower/loops/ForLoopsLowering.kt) (shared),
[BCEForLoopBodyTransformer.kt](../../../../compiler/ir/backend.common/src/org/jetbrains/kotlin/backend/common/lower/loops/BCEForLoopBodyTransformer.kt)
· **Runs:** after inlining, always.
