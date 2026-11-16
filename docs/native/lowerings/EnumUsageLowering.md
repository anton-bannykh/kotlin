# EnumUsageLowering

Rewrites every use of an enum into a call to the members that [EnumClassLowering](EnumClassLowering.md) created.

**Why:** once enum entries are gone from the class, `Color.BLUE` has nothing to refer to. It has to become a read from the
static array.

```kotlin
enum class Color { RED, GREEN, BLUE }
```

```kotlin
// before                                    // after
val c = Color.BLUE                           val c = Color.`$getEnumAt`(0)      // BLUE is first by name
enumValues<Color>()                          Color.values()
enumValueOf<Color>("GREEN")                  Color.valueOf("GREEN")
enumEntries<Color>()                         Color.entries
```

**Know this**
- The index passed to `$getEnumAt` is the entry's position in **name order**, not its ordinal.
- It works for enums from other klibs too, so `$getEnumAt` is effectively part of an enum's binary interface.
- `enumValues<T>()` and friends are reified inline functions. They can only be rewritten once inlining has replaced `T` with a
  concrete enum, which is why this lowering runs after the inliner.

**Source:** [EnumClassLowering.kt](../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/EnumClassLowering.kt)
(class `EnumUsageLowering`) · **Runs:** after inlining, always, right after `EnumClassLowering`.
