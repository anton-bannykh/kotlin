# Enums

*Skip this page if you know that a Native enum is a plain class with a name-sorted static array of instances.
Reference material.*

Codegen knows nothing about enum entries. Four lowerings, all after inlining, turn an `enum class` into an ordinary class
whose instances sit in a static array, and make every use of the enum go through that array:

`EnumConstructorsLowering → NativeEnumWhenLowering → EnumClassLowering → EnumUsageLowering`

One example is followed through all four:

```kotlin
enum class Color(val rgb: Int) {
    RED(0xff0000),
    BLUE(0x0000ff) { override fun describe() = "blue!" };
    open fun describe() = name
}
fun f(c: Color) = when (c) { Color.RED -> 1; Color.BLUE -> 2 }
fun blue() = Color.BLUE
```

## EnumConstructorsLowering

The base class `Enum` takes `name` and `ordinal` as constructor arguments, and in source code nobody passes them. This
lowering adds the two parameters to every constructor of an enum class and makes every entry pass its name and position.

```kotlin
enum class Color : Enum<Color> {
    protected constructor(name: String, ordinal: Int, rgb: Int) { super<Enum>(name, ordinal); ... }  // was: super<Enum>()
    RED = Color(name = "RED", ordinal = 0, rgb = 0xff0000)
    BLUE = BLUE(name = "BLUE", ordinal = 1)
    enum entry class BLUE : Color {                   // an entry with a body is a subclass
        protected constructor(name: String, ordinal: Int) { super<Color>(name = "BLUE", ordinal = 1, rgb = 0x0000ff) }
        override fun describe() = "blue!"
    }
}
```

- The entries are still entries. A secondary constructor passes the two parameters on to the one it delegates to.

**Source:** [EnumConstructorsLowering.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/EnumConstructorsLowering.kt)
· **Runs:** after inlining, always, before `InitializersLowering`.

## NativeEnumWhenLowering

An optimization: a `when` over an enum value reads the subject's ordinal once and compares integers, so no branch has to
load an entry object.

```kotlin
// before                                            // after
val subject = c                                      val subject = c
                                                     val ordinal = subject.ordinal
return when {                                        return when {
    subject == Color.RED -> 1                            ordinal == 0 -> 1
    subject == Color.BLUE -> 2                           ordinal == 1 -> 2
    else -> throwNoWhenBranchMatchedException(subject)   else -> throwNoWhenBranchMatchedException(subject)
}                                                    }
```

- The caller now holds the ordinals as constants, so it must be recompiled when the enum's file changes. The Native
  subclass adds exactly that: it records a dependency on that file for [caches](../caches.md). The rest is shared code.
- Only comparisons of the subject with an entry or with `null` (ordinal -1) are rewritten; `is` and `else` branches stay.
  If some branch condition is another kind of call, the whole `when` is left alone.

**Source:** [EnumWhenLowering.kt](../../../../compiler/ir/backend.common/src/org/jetbrains/kotlin/backend/common/lower/EnumWhenLowering.kt) (shared),
[EnumClassLowering.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/EnumClassLowering.kt) (class `NativeEnumWhenLowering`)
· **Runs:** after inlining, always, just before `EnumClassLowering`.

## EnumClassLowering

Turns the enum into an ordinary class: the entries become objects in a static array, and `values()`, `valueOf()` and
`entries` get real bodies.

```kotlin
class Color(name: String, ordinal: Int, val rgb: Int) : Enum<Color> {
    private static val `$VALUES`: Array<Color> = {      // sorted by name: BLUE = 0, RED = 1
        val a = arrayOf(createUninitializedInstance<Color$BLUE>(), createUninitializedInstance<Color>())
        `$VALUES` = a                                   // published before any constructor runs
        initInstance(a[1], Color("RED", 0, 0xff0000))   // constructors run in declaration order
        initInstance(a[0], Color$BLUE("BLUE", 1))
        a
    }
    private static val `$ENTRIES`: EnumEntries<Color> = enumEntries(arrayOf(a[1], a[0]))   // ordinal order
    class `Color$BLUE` : Color { override fun describe() = "blue!" }    // was the entry's body
    static fun values() = valuesForEnum(`$VALUES`)
    static fun valueOf(value: String) = valueOfForEnum(value, `$VALUES`)
    static val entries get() = `$ENTRIES`
    static fun `$getEnumAt`(getterId: Int) = `$VALUES`[getterId]
}
```

- `$VALUES` is sorted **by name**, not by ordinal (the position in the source), because `valueOf` does a binary search
  over it. An entry is addressed by its position in name order, so adding or renaming an entry shifts those ids.
- An entry's constructor can already see the other entries, possibly not yet constructed.

**Source:** [EnumClassLowering.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/EnumClassLowering.kt)
· **Runs:** after inlining, always. The static fields are made lazy later, see
[initialization.md](../../features/initialization.md).

## EnumUsageLowering

With the entries gone, `Color.BLUE` has nothing to refer to. Every use of an enum becomes a call of the new members.

```kotlin
// before                                    // after
fun blue() = Color.BLUE                      fun blue() = Color.`$getEnumAt`(0)     // BLUE is first by name
enumValues<Color>()                          Color.values()
enumValueOf<Color>("RED")                    Color.valueOf("RED")
enumEntries<Color>()                         Color.entries
```

- It works for enums from other klibs too, so `$getEnumAt` and its name-order index are effectively part of an enum's
  binary interface.
- `enumValues<T>()` and friends are reified inline functions: they can only be rewritten once inlining has replaced `T`
  with a concrete enum. That is why the chain runs after the inliner.

**Source:** [EnumClassLowering.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/EnumClassLowering.kt) (class `EnumUsageLowering`)
· **Runs:** after inlining, always, right after `EnumClassLowering`.
