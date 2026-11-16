# NativeAssertionWrapperLowering, NativeAssertionRemoverLowering

The wrapper puts every `assert(...)` call under a check of a compiler intrinsic. Much later, the remover replaces that
intrinsic with the value of `-enable-assertions` (`-ea`), which keeps the assertion or deletes it.

**Why:** whether assertions are on is decided when the binary is built, not when a library is compiled. The klib must
therefore keep the assertion in a form that can still be dropped, including its condition and message.

```kotlin
// before
fun checkPositive(x: Int) { assert(x > 0) { "x must be positive: $x" } }

// after NativeAssertionWrapperLowering
fun checkPositive(x: Int) {
    if (isAssertionArgumentEvaluationEnabled()) assert(x > 0) { "x must be positive: $x" }
}

// after inlining: `assert` is an inline function from the stdlib, and its body has a second intrinsic
fun checkPositive(x: Int) {
    if (isAssertionArgumentEvaluationEnabled()) {
        val value = x > 0
        if (isAssertionThrowingErrorEnabled()) {
            if (!value) throw AssertionError("x must be positive: " + x)
        }
    }
}

// after NativeAssertionRemoverLowering, without -ea        // ... and with -ea
fun checkPositive(x: Int) { }                               fun checkPositive(x: Int) {
                                                                val value = x > 0
                                                                if (!value) throw AssertionError("x must be positive: " + x)
                                                            }
```

**Know this**
- Without `-ea` the condition is not evaluated at all. Side effects inside `assert(...)` disappear.
- The two intrinsics exist so that "evaluate the arguments" and "throw" could be switched separately. Kotlin/Native passes
  the same `-ea` value for both.
- The wrapper also runs in the first stage (together with the inliner that runs there), so a klib usually already contains
  the wrapped and inlined form. The remover runs only in the second stage.

**Source:** [KlibAssertionLowering.kt](../../../compiler/ir/backend.common/src/org/jetbrains/kotlin/backend/common/lower/KlibAssertionLowering.kt)
(the shared base classes of both),
[AssertRemovalLowering.kt](../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/AssertRemovalLowering.kt)
· **Runs:** the wrapper before inlining, the remover after inlining; both always.
