# RedundantCoercionsCleaner

Removes a box that is immediately unboxed again (or the other way round), so that the value never becomes an object.

**Why:** such pairs appear after [devirtualization](../optimizations/devirtualization.md). A call through `Source<Int>`
returns a boxed `Int`, which the caller unboxes. When devirtualization replaces the virtual call with a direct call to
`Counter.next()`, which returns a raw `Int`, it has to box the result to keep the IR consistent. Box and unbox now meet.

```kotlin
fun sum(s: Source<Int>): Int = s.next() + 1       // s is a Counter or a Twice

// before (after devirtualization)
fun sum(s: Source<Int>): Int =
    <Int-unbox>(when (getObjectTypeInfo(s)) {
        Counter::class -> <Int-box>(super<Counter>.next())      // direct call returns Int; boxed to match the old call
        else -> <Int-box>(super<Twice>.next())
    }) + 1

// after
fun sum(s: Source<Int>): Int =
    when (getObjectTypeInfo(s)) {
        Counter::class -> super<Counter>.next()                 // no allocation left
        else -> super<Twice>.next()
    } + 1
```

**Know this**
- The outer coercion is pushed into the branches of a `when` and into the returns of an inlined block. If only some
  branches cancel, the others get the coercion instead: `box(when { a -> unbox(x); b -> y })` becomes
  `when { a -> x; b -> box(y) }`.
- Casts between the two calls are dropped together with the pair.
- It runs in every build, but devirtualization, its main source of work, runs only with `-opt`.

**Source:** [RedundantCoercionsCleaner.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/RedundantCoercionsCleaner.kt)
· **Runs:** right after devirtualization, always · **Next:** [UnboxInlineLowering](UnboxInlineLowering.md).
