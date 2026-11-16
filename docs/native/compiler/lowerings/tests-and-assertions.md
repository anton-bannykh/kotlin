# Tests and assertions

*Skip this page if you already know how `@Test` functions get registered and how `-ea` removes `assert`. Reference material.*

Two unrelated pairs with the same shape: an early lowering leaves code in a form that can be stored in a klib, and a late
one finishes the job when the binary is built: `TestProcessor → TestsInitializer` and
`NativeAssertionWrapperLowering → NativeAssertionRemoverLowering`.

## TestProcessor

Finds the functions marked with `kotlin.test` annotations and generates code that registers them with the test runner in
the runtime. `TestsInitializer` makes that code run at program start. `TestsDumper` lists the tests in a file.

**Why:** there is no reflection that could find `@Test` functions at run time. The list of tests has to be compiled in.

```kotlin
// before
class CalcTest {
    @BeforeTest fun setUp() {}
    @Test fun adds() { assertEquals(4, 2 + 2) }
    @Ignore @Test fun broken() {}
}
@Test fun topLevel() { assertTrue(true) }

// after TestProcessor: the code above is unchanged; the file gets @file:TestsProcessed and these declarations
private class `$CalcTest$test$0` : BaseClassSuite<CalcTest, Nothing>(name = "CalcTest", ignored = false) {
    init {                                              // the base class registers the suite with the runner
        registerFunction(TestFunctionKind.BEFORE_TEST, { it: CalcTest -> it.setUp() })
        registerTestCase(name = "adds", testFunction = { it: CalcTest -> it.adds() }, ignored = false)
        registerTestCase(name = "broken", testFunction = { it: CalcTest -> it.broken() }, ignored = true)
    }
    override fun createInstance() = CalcTest()
}
@TestInitializer private fun `$createTestSuites`() {
    `$CalcTest$test$0`()
    val suite = TopLevelSuite(name = "TKt")             // top-level tests of the file t.kt
    suite.registerTestCase(name = "topLevel", testFunction = { topLevel() }, ignored = false)
}

// after TestsInitializer: the function became a field with the same statements as its initializer
@EagerInitialization @ThreadLocal private static val `$createTestSuites`: Unit = { ... }
```

**Know this**
- `TestProcessor` does not depend on `-generate-test-runner` (`-tr`): any file with `@Test` functions gets its suites. `-tr`
  makes the test runner the entry point of the binary and adds `TestsDumper`, which only reads the generated code: with
  `-Xdump-tests-to=<file>` it writes one `Suite:test` line per test (`CalcTest:adds`, `CalcTest:broken`, `TKt:topLevel`).
- `TestProcessor` is in the lists of both stages. With the current default language version it works in the first stage, so
  the suites are stored in the klib, and `@file:TestsProcessed` makes the second-stage run skip the file. The eager field
  (see [initialization.md](../../features/initialization.md)) is only created in the second stage.

**Source:** [TestProcessor.kt](../../../../compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/TestProcessor.kt),
[TestsInitializer.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/TestsInitializer.kt)
· **Runs:** `TestProcessor` first of all lowerings; the other two after inlining, `TestsDumper` only with `-tr`.

## NativeAssertionWrapperLowering

Puts every `assert(...)` call under a check of a compiler intrinsic. Much later, `NativeAssertionRemoverLowering` replaces
that intrinsic with the value of `-enable-assertions` (`-ea`), which keeps the assertion or deletes it.

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
- Without `-ea` the condition is not evaluated at all: side effects inside `assert(...)` disappear. The two intrinsics could
  switch "evaluate the arguments" and "throw" separately, but Kotlin/Native passes the same `-ea` value for both.
- The wrapper also runs in the first stage (together with the inliner that runs there), so a klib usually already contains
  the wrapped and inlined form. The remover runs only in the second stage.

**Source:** [KlibAssertionLowering.kt](../../../../compiler/ir/backend.common/src/org/jetbrains/kotlin/backend/common/lower/KlibAssertionLowering.kt)
(the shared base classes of both),
[AssertRemovalLowering.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/AssertRemovalLowering.kt)
· **Runs:** the wrapper before inlining, the remover after inlining; both always.
