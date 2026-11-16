# EnumClassLowering

Turns an `enum class` into an ordinary class: the entries become objects stored in a static array, and `values()`,
`valueOf()` and `entries` get real bodies.

**Why:** codegen knows nothing about enum entries. After this lowering an enum is just a class with static fields.

```kotlin
// before
enum class Color(val rgb: Int) {
    RED(0xff0000),
    BLUE(0x0000ff) { override fun describe() = "blue!" };
    open fun describe() = name
}

// after
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

**Know this**
- `$VALUES` is sorted **by name**, not by ordinal, because `valueOf` does a binary search over it. An entry is therefore
  addressed by its position in name order (`getterId`), and adding or renaming an entry shifts those ids.
- All instances are allocated and published before the first constructor runs, so an entry's constructor can already see the
  other entries (possibly not yet constructed).

**Source:** [EnumClassLowering.kt](../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/EnumClassLowering.kt)
· **Runs:** after inlining, always · **Next:** [EnumUsageLowering](EnumUsageLowering.md) rewrites the uses;
[StaticInitializersLowering](StaticInitializersLowering.md) makes the static fields lazily initialized.
