# Autoboxing

Makes every conversion between a raw value and an object explicit. Wherever an `Int`, `Double`, ... or a value class meets
`Any`, a nullable type or a generic, a call to `<T-box>` or `<T-unbox>` is inserted.

**Why:** an `Int` is a 32-bit number in a register, an `Any` is a pointer to a heap object with a header
(→ [object-model.md](../../runtime/object-model.md)). The Kotlin type system hides the difference; code generation must not have to
guess where it is.

```kotlin
// before
value class Meters(val v: Double)
fun boxing(x: Int): Any = x
fun nullable(x: Int): Int? = x
fun unboxing(a: Any): Int = a as Int
fun boxMeters(m: Meters): Any = m
fun add(l: MutableList<Int>, x: Int) { l.add(x) }
fun sum(s: Source<Int>): Int = s.next() + 1

// after
fun boxing(x: Int): Any = <Int-box>(x)                  // allocates an object holding the Int (small values are cached)
fun nullable(x: Int): Int? = <Int-box>(x)               // Int? is a reference too: null must be representable
fun unboxing(a: Any): Int = <Int-unbox>(a as Int)       // reads the value out of the object
fun boxMeters(m: Meters): Any = <Meters-box>(m)         // m itself is just a Double
fun add(l: MutableList<Int>, x: Int) { l.add(<Int-box>(x)) }
fun sum(s: Source<Int>): Int = <Int-unbox>(s.next() as Int?) + 1    // debug builds also check the type of a generic result

fun <Meters-box>(value: Meters): Any {                  // generated for each value class
    val box = createUninitializedInstance<Meters>(); box.v = value; return box
}
fun <Meters-unbox>(value: Any): Meters = value.v
```

**Know this**
- A value class is represented by its single property: `Meters` is a `Double`. Inside the class, reading `this.v` becomes
  `reinterpret<Meters, Double>(this)`, which generates no code, and `Meters(1.0)` becomes `1.0` (plus a call of a
  generated function that runs the `init` blocks).
- A constant that needs a box becomes a constant object in the binary: `boxing(1)` allocates nothing.
- The `as Int?` check on values coming out of generic code exists because unchecked casts can put anything there. It is
  on in debug builds and off with `-opt` by default.
- [BridgesBuilding](BridgesBuilding.md) creates the bridge functions; the box and unbox calls inside them are added here.

**Source:** [Autoboxing.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/Autoboxing.kt)
· **Runs:** after inlining, always. With `-opt`, [RedundantCoercionsCleaner](RedundantCoercionsCleaner.md) later removes
pairs that cancel out.
