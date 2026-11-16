# EnumConstructorsLowering

Adds two parameters, `name` and `ordinal`, to every constructor of an enum class, and makes every entry pass its own name
and position.

**Why:** the base class `Enum` stores `name` and `ordinal` in fields and takes them as constructor arguments. In source
code nobody passes them: the compiler has to.

```kotlin
// before
enum class Color(val rgb: Int) {
    RED(0xff0000),
    BLUE(0x0000ff) { override fun describe() = "blue!" };
    constructor() : this(0)
    open fun describe() = name
}

// after
enum class Color : Enum<Color> {
    protected constructor(name: String, ordinal: Int, rgb: Int) {
        super<Enum>(name, ordinal)                    // was: super<Enum>()
        ...
    }
    protected constructor(name: String, ordinal: Int) {
        this(name, ordinal, rgb = 0)                  // secondary constructors pass them on
    }
    RED = Color(name = "RED", ordinal = 0, rgb = 0xff0000)
    BLUE = BLUE(name = "BLUE", ordinal = 1)
    enum entry class BLUE : Color {                   // an entry with a body is a subclass
        protected constructor(name: String, ordinal: Int) {
            super<Color>(name = "BLUE", ordinal = 1, rgb = 0x0000ff)
        }
        override fun describe() = "blue!"
    }
}
```

**Know this**
- The entries are still entries after this lowering. [EnumClassLowering](EnumClassLowering.md) later turns them into objects
  in a static array and reuses the constructor calls prepared here.
- The ordinal is the position in the source. Do not confuse it with the position in name order that
  [EnumUsageLowering](EnumUsageLowering.md) uses to address an entry.

**Source:** [EnumConstructorsLowering.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/EnumConstructorsLowering.kt)
· **Runs:** after inlining, always, before `InitializersLowering`.
