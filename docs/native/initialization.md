# Initialization of top-level properties, objects and enums

Kotlin/Native does not initialize global state at program start. A file's top-level properties are initialized **the first
time anything in that file is used**; an `object`, a companion object or an enum is initialized the first time the class is
used. This keeps startup fast and means unused code costs nothing, but it requires a check in front of almost every
function. This page follows that check from the lowerings to the runtime.

```kotlin
// lib.kt
const val LIMIT = 10
val table = listOf(1, 2, 3).also { println("  init lib.kt: table") }
@ThreadLocal val scratch = mutableListOf<Int>().also { println("  init lib.kt: scratch (this thread)") }
@EagerInitialization val registered = println("  eager: registered")

object Config { val verbose = false.also { println("  init Config") } }
enum class Color { RED, BLUE; init { println("  init Color.$name") } }

fun lookup(i: Int): Int = table[i]
```

```kotlin
// main.kt                      what the program prints
fun main() {                    //   eager: registered                    <- before main: runtime startup
    println("main starts")      // main starts
    println(LIMIT)              // 10                                     <- a constant: nothing is initialized
    println(lookup(1))          //   init lib.kt: table                   <- first use of lib.kt: the whole file,
                                //   init lib.kt: scratch (this thread)      in declaration order
                                // 2
    println(Config.verbose)     //   init Config                          <- first use of Config
                                // false
    println(Color.BLUE)         //   init Color.RED                       <- first use of Color: all entries
                                //   init Color.BLUE
                                // BLUE
}
```

The unit of initialization is a **container**: a file (for its top-level properties) or a class (for its object instance,
companion instance or enum entries). A container is initialized as a whole, once, and independently of other containers:
using `Config` initializes `Config`, not the rest of `lib.kt`.

## What happens

**1. Lowerings make "first use" explicit.** [ObjectClassLowering](lowerings/ObjectClassLowering.md) and
[EnumClassLowering](lowerings/EnumClassLowering.md) turn object instances and enum entries into static fields with
initializers. [StaticInitializersLowering](lowerings/StaticInitializersLowering.md) then moves all initializers of a
container into one function and inserts a call to it at the start of every function that could be the first one used:

```kotlin
private fun `$init_global$lib.kt`()       { table = listOf(1, 2, 3).also { ... } }
private fun `$init_thread_local$lib.kt`() { scratch = ArrayList<Int>().also { ... } }

fun lookup(i: Int): Int {
    `$init_global$lib.kt`()             // inserted
    `$init_thread_local$lib.kt`()       // inserted
    return table[i]
}
object Config {
    private static fun `$init_global`() { `$instance` = ...; Config() }
    static val `$instance` get() { `$init_global`(); return field }      // `Config.verbose` reads `$instance` first
}
```

**2. Code generation turns each inserted call into a check.** Every container gets a **state word**, a global integer.
The call becomes "load the state; if it is not 2, call the runtime". This is `lookup` in LLVM IR (names shortened):

```llvm
define i32 @"kfun:#lookup(kotlin.Int){}kotlin.Int"(i32 %0) {
entry:
  %6 = load atomic i64, ptr @"state_global$lib.kt" acquire      ; fast path: one load and one compare
  %7 = icmp eq i64 %6, 2                                        ; 2 = initialized
  br i1 %7, label %label_continue, label %label_init

label_init:                                                     ; slow path: let the runtime run $init_global once
  call void @CallInitGlobalPossiblyLock(ptr @"state_global$lib.kt", ptr @"kfun:$init_global$lib.kt", ptr null)
  br label %label_continue

label_continue:
  ...                                                           ; the same for the thread-local state word, then the body
  %10 = call ptr @"kfun:#<get-table>(){}kotlin.collections.List<kotlin.Int>"(ptr %3)
```

**3. The runtime runs the initializer exactly once.** `CallInitGlobalPossiblyLock` is a C++ function of the
[runtime](runtime.md). The state word itself is the lock:

| State word | Meaning | A thread that arrives now |
|---|---|---|
| 0 | not initialized | atomically sets it to 1 (plus its thread id) and runs the initializer |
| 1 + thread id | being initialized | the same thread: returns at once. Another thread: waits until the state changes |
| 2 | initialized | does not get here: the generated code already took the fast path |
| 3 | the initializer threw | throws `NoClassDefFoundError`, every time |

## Things to know

- **Not everything needs initialization.** A `const val`, a property with a constant initializer (`val greeting = "hi"`) and
  an object without state are plain data in the binary: the string or object is laid out at compile time and the field
  points at it from the start. See the constant
  objects in [ObjectClassLowering](lowerings/ObjectClassLowering.md).
- **Re-entrance on the same thread is allowed, and shows half-initialized state.** If an initializer reaches, through some
  call chain, a function of its own container, that function proceeds and sees properties that are not assigned yet. This
  is how cyclic dependencies between files "work" instead of deadlocking.
- **A failed initializer is permanent.** The access that ran it gets `ExceptionInInitializerError` with the original
  exception as its cause (an `Error` is rethrown as is). The container is never retried: every later access throws
  `NoClassDefFoundError("Could not initialize ...")`.
- **`@ThreadLocal` properties** live in thread-local storage, so every thread has its own copy and its own state word. Their
  initializer runs once per thread, on that thread's first use of the container, after the shared properties. No locking
  is needed for them.
- **`@EagerInitialization`** opts a top-level property out of laziness: it is initialized when the runtime starts, before
  `main`. It exists for code that must register itself without being called (the annotation is deprecated; do not add new
  uses).
- **With `-opt` most checks disappear.** A [whole-program analysis](optimizations.md) computes which containers are
  certainly initialized whenever a function is entered, and deletes the checks that can never take the slow path. In the
  optimized build of a program like the one above, `lookup` has no check at all, because `main`, its only caller, already
  did it. Over the whole binary (mostly the standard library), 540 slow-path call sites became 132.

## Where the code is

- [StaticInitializersLowering.kt](../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/StaticInitializersLowering.kt):
  builds the `$init_...` functions and inserts the calls.
- [IrToBitcode.kt](../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/llvm/IrToBitcode.kt):
  search for `evaluateFileGlobalInitializerCall` to find the generated check, and for `state_global` to find the state words.
- [Runtime.cpp](../../kotlin-native/runtime/src/main/cpp/Runtime.cpp): `CallInitGlobalPossiblyLock` and `CallInitThreadLocal`,
  the slow paths; `initRuntime`, which runs the eager initializers.
- [StaticInitializersOptimization.kt](../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/optimizations/StaticInitializersOptimization.kt):
  the `-opt` analysis that removes redundant checks.
- [KotlinStaticData.kt](../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/llvm/KotlinStaticData.kt):
  how constant objects and strings are laid out in the binary.

Read next: [object-model.md](object-model.md) for what a constant object looks like, [exceptions.md](exceptions.md) for what
happens to the exception of a failed initializer.
