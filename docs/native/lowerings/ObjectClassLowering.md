# ObjectClassLowering

Gives every `object` and `companion object` a static field holding its single instance, plus a getter, and replaces every
reference to the object with a call to that getter.

**Why:** "the instance of `Foo`" is a language concept. Codegen needs an actual place where the instance is stored and code
that creates it.

```kotlin
// before
object Plain { val y = 1 }
object OnlyConst { const val x = 1 }
class Host { companion object { const val c = 3 } }
fun main() { println(Plain.y); val u = Unit }

// after
object Plain {
    val y = 1
    static val `$instance`: Plain = {
        `$instance` = createUninitializedInstance<Plain>()    // published before the constructor runs
        initInstance(`$instance`, Plain())
        `$instance`
    }
}
object OnlyConst {
    const val x = 1
    static val `$instance`: OnlyConst = <constant object>     // no state, no init code: nothing to run
}
class Host {
    companion object { const val c = 3 }
    static val `$companion`: Companion = <constant object>    // lives in the outer class
}
fun main() { println(Plain.`$instance`.y); val u = theUnitInstance() }
```

**Know this**
- An object with no state and no initialization code becomes a compile-time constant in the binary: no allocation, no check.
- The field is not initialized here: [StaticInitializersLowering](StaticInitializersLowering.md) makes that lazy.

**Source:** [ObjectClassLowering.kt](../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/ObjectClassLowering.kt)
· **Runs:** after inlining, always.
