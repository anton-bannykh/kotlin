# PropertyReferenceLowering

Turns a property reference (`Person::name`, `p::name`, `::global`) into the creation of a `KProperty` object from the
runtime, built from the property's name and function references to its getter and setter.

**Why:** codegen has no notion of "a reference to a property". After this lowering there are only constructor calls and
function references, and the latter are handled by [NativeFunctionReferenceLowering](NativeFunctionReferenceLowering.md).

```kotlin
// before
class Person(val name: String)
var global = 1
fun refs(p: Person) {
    val unbound = Person::name
    val bound = p::name
    val mutable = ::global
}
fun local(): Int { val x by lazy { 1 }; return x }        // an earlier lowering moved the reference the delegate gets to:
private static val `$KPROPERTY1`: KProperty0<Int> = <reference to the local property x>

// after
fun refs(p: Person) {
    val unbound = KProperty1Impl<Person, String>("name", null, getter = { p0: Person -> p0.name })  // null: no linkage error
    val bound   = KProperty0Impl<String>("name", null, getter = <reference bound to p> { receiver: Person -> receiver.name })
    val mutable = KMutableProperty0Impl<Int>("global", null, getter = { global }, setter = { value: Int -> global = value })
}
private static val `$KPROPERTY1`: KProperty0<Int> = <constant KLocalDelegatedPropertyImpl(name = "x", …)>
```

**Know this**
- Kotlin/Native has no full reflection. A `KProperty` object knows the property's name, can get and set it, and compares
  equal to another reference to the same property. Its `toString()` says "Kotlin reflection is not available".
- The property passed to the delegate of a *local* delegated property is a constant that carries little more than the
  name. Calling `get()` on it throws.
- A reference with no bound receiver has only constant arguments, so
  [StaticCallableReferenceOptimization](StaticCallableReferenceOptimization.md) later turns the whole object into a constant.

**Source:** [PropertyReferenceLowering.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/PropertyReferenceLowering.kt),
shared part in [AbstractPropertyReferenceLowering.kt](../../../../compiler/ir/backend.common/src/org/jetbrains/kotlin/backend/common/lower/AbstractPropertyReferenceLowering.kt)
· **Runs:** after inlining, always.
