# NativeEnumWhenLowering

Rewrites a `when` over an enum value so that it compares integer ordinals instead of enum objects.

**Why:** it is an optimization. Without it every branch would have to load an entry object (`Color.RED`) and compare it
with the subject. With it the subject's ordinal is read once and compared with integer constants.

```kotlin
enum class Color { RED, BLUE }

// before                                            // after
fun f(c: Color): Int {                               fun f(c: Color): Int {
    val subject = c                                      val subject = c
                                                         val ordinal = subject.ordinal
    return when {                                        return when {
        subject == Color.RED -> 1                            ordinal == 0 -> 1
        subject == Color.BLUE -> 2                           ordinal == 1 -> 2
        else -> throwNoWhenBranchMatchedException(subject)   else -> throwNoWhenBranchMatchedException(subject)
    }                                                    }
}                                                    }

// a nullable subject: `null` gets the ordinal -1
fun g(c: Color?) = when (c) {                        val ordinal = if (subject == null) -1 else subject.ordinal
    Color.RED -> "r"                                     ordinal == 0 -> "r"
    null -> "n"                                          ordinal == -1 -> "n"
    else -> "e"                                          else -> "e"
}
```

**Know this**
- The ordinals are written into the caller as constants, and the caller no longer mentions the enum entries. So when the
  enum's file changes, the caller must be recompiled. The Native subclass adds exactly that: it records a dependency on the
  enum's file, so that a cached caller is rebuilt (see [caches.md](../caches.md)). Everything else is the shared
  `EnumWhenLowering`.
- Only comparisons of the subject with an entry or with `null` are rewritten; `is` and `else` branches stay as they are.
  If some branch condition is another kind of call, the whole `when` is left alone.

**Source:** [EnumWhenLowering.kt](../../../compiler/ir/backend.common/src/org/jetbrains/kotlin/backend/common/lower/EnumWhenLowering.kt) (shared),
[EnumClassLowering.kt](../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/EnumClassLowering.kt) (class `NativeEnumWhenLowering`)
· **Runs:** after inlining, always, just before [EnumClassLowering](EnumClassLowering.md).
