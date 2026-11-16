# StaticInitializersLowering

Moves the initializers of static fields (top-level properties, object instances, enum arrays) into a per-file or per-class
init function, and makes every function that could be the first one called in that file or class call it.

**Why:** Kotlin/Native initializes a file's top-level properties lazily, on first access to the file. This lowering makes
"on first access" explicit, so codegen only has to compile a cheap "already initialized?" check.

```kotlin
// before (a.kt)
val greeting = "hi"
val list = listOf(1, 2)
@ThreadLocal val perThread = mutableListOf<Int>()

fun use() = list.size + perThread.size

// after
private fun `$init_global$a.kt`()       { list = listOf(1, 2) }
private fun `$init_thread_local$a.kt`() { perThread = ArrayList() }

val greeting = "hi"            // constant: stays in the binary's data, needs no code
val list: List<Int>            // initializer moved out
@ThreadLocal val perThread: MutableList<Int>

fun use(): Int {
    `$init_global$a.kt`()            // inserted; runs the body once per process
    `$init_thread_local$a.kt`()      // inserted; runs the body once per thread
    return list.size + perThread.size
}
```

**Know this**
- The inserted calls are not plain calls. Codegen turns each into a state check with a slow path into the runtime, which
  handles "run once" and concurrent first access. See [initialization.md](../../features/initialization.md).
- Classes work the same way: static fields (such as `$instance` or `$VALUES`) move to a per-class `$init_global`, called from
  constructors and static members.
- `@EagerInitialization` properties go to a separate function that runs at program start instead of on first access.

**Source:** [StaticInitializersLowering.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/StaticInitializersLowering.kt)
· **Runs:** after inlining, always, after [ObjectClassLowering](ObjectClassLowering.md).
