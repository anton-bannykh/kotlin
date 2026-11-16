# NativeTailrecLowering

Turns a `tailrec` function into a loop: each recursive call in tail position becomes "assign the new argument values and
jump back to the start".

**Why:** `tailrec` promises that the recursion does not grow the stack. A call in LLVM IR gives no such guarantee,
especially in a debug build, so the compiler removes the call itself.

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
- This is the shared `TailrecLowering` with nothing overridden. The Native class exists to place it in the phase list: after
  local functions were moved out, and before [NativeFinallyBlocksLowering](NativeFinallyBlocksLowering.md) and the
  [default-argument lowerings](NativeDefaultArguments.md), which require it to have run.

**Source:** [TailrecLowering.kt](../../../compiler/ir/backend.common/src/org/jetbrains/kotlin/backend/common/lower/TailrecLowering.kt) (shared),
[NativeTailrecLowering.kt](../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/NativeTailrecLowering.kt)
· **Runs:** after inlining, always.
