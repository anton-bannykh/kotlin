# TestProcessor, TestsInitializer, TestsDumper

`TestProcessor` finds the functions marked with `kotlin.test` annotations and generates code that registers them with the
test runner in the runtime. `TestsInitializer` makes that code run at program start. `TestsDumper` lists the tests in a file.

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
  makes the test runner the entry point of the binary and adds `TestsDumper`.
- `TestProcessor` is in the lists of both stages. With the current default language version it does its work in the first
  stage, so the suites are stored in the klib, and `@file:TestsProcessed` makes the second-stage run skip the file.
- The eager field is only created in the second stage. `@EagerInitialization` makes its initializer run at program start
  instead of on first access, see [initialization.md](../../features/initialization.md).
- `TestsDumper` only reads the generated code. With `-Xdump-tests-to=<file>` it writes one `Suite:test` line per test: here
  `CalcTest:adds`, `CalcTest:broken`, `TKt:topLevel`.

**Source:** [TestProcessor.kt](../../../../compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/TestProcessor.kt),
[TestsInitializer.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/TestsInitializer.kt)
· **Runs:** `TestProcessor` first of all lowerings; the other two after inlining, `TestsDumper` only with `-tr`.
