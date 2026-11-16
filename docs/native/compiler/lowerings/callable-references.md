# Callable references

*Skip this page if you know that a lambda or `::reference` becomes a class, or a constant when it captures nothing.
Reference material.*

A function value is an object that can be stored, passed around and called through `Function1.invoke`, and codegen only
knows classes, constructor calls and virtual calls. So every lambda, function reference and property reference left after
inlining becomes an instance of a class, and a constant object when it has no state:

`PropertyReferenceLowering → NativeFunctionReferenceLowering → NativeSingleAbstractMethodLowering → … →
StaticCallableReferenceOptimization`

The examples share `class Person(val name: String)`, `var global = 1` and `fun twice(x: Int) = x * 2`.

## PropertyReferenceLowering

Turns a property reference into the creation of a `KProperty` object from the runtime, built from the property's name and
function references to its getter and setter. Those references are left for the next lowering.

```kotlin
// before
val unbound = Person::name
val mutable = ::global                      // a bound `p::name` gets a KProperty0Impl whose getter reference holds `p`

// after
val unbound = KProperty1Impl<Person, String>("name", null, getter = { p0: Person -> p0.name })  // null: no linkage error
val mutable = KMutableProperty0Impl<Int>("global", null, getter = { global }, setter = { value: Int -> global = value })
```

- Kotlin/Native has no full reflection. A `KProperty` object knows the property's name, can get and set it, and compares
  equal to another reference to the same property. Its `toString()` says "Kotlin reflection is not available".
- The property that the delegate of a *local* delegated property (`val x by lazy { 1 }`) receives becomes a constant
  `KLocalDelegatedPropertyImpl` that carries little more than the name. Calling `get()` on it throws.

**Source:** [PropertyReferenceLowering.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/PropertyReferenceLowering.kt),
shared part in [AbstractPropertyReferenceLowering.kt](../../../../compiler/ir/backend.common/src/org/jetbrains/kotlin/backend/common/lower/AbstractPropertyReferenceLowering.kt)
· **Runs:** after inlining, always.

## NativeFunctionReferenceLowering

Turns every lambda and function reference into a local class with an `invoke` method, plus a constructor call that creates
the instance. The class is declared in a block right where the value is created.

```kotlin
fun refs(delta: Int, p: Person) {
    local class `<no name provided>` : Function1<Int, Int> {
        override fun invoke(x: Int): Int = x + delta                // still uses the outer variable directly
    }
    val capturing = `<no name provided>`()                          // was: { x: Int -> x + delta }
    local class `$FUNCTION_REFERENCE_FOR$twice$5` : KFunctionImpl<Int>(<constant description>), KFunction1<Int, Int> {
        override fun invoke(p0: Int): Int = twice(p0)
    }
    val ref = `$FUNCTION_REFERENCE_FOR$twice$5`()                   // was: ::twice
    local class `$FUNCTION_REFERENCE_FOR$toString$6`(p0: Person) : KFunctionImpl<String>(<…>), KFunction0<String> {
        private val `f$0`: Person = p0                              // a bound receiver becomes a field
        override fun invoke(): String = `f$0`.toString()
        override fun boundValueAt(index: Int): Any? = `f$0`         // lets equals() compare bound receivers
    }
    val boundRef = `$FUNCTION_REFERENCE_FOR$toString$6`(p)          // was: p::toString
}
```

- Captured variables are not handled here. The local-declaration lowerings (see
  [NativeLocalDeclarationsLowering](classes-and-local-declarations.md#nativelocaldeclarationslowering)) later name the class
  (`refs$capturing$1`), turn `delta` into a constructor parameter and a field, and move the class to the top level.
- A lambda's class extends `Any`. A function reference extends `KFunctionImpl` and carries a constant description (name,
  arity, return type, …), so that `::twice == ::twice` and `.name` work.

**Source:** [NativeFunctionReferenceLowering.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/NativeFunctionReferenceLowering.kt),
shared part in [AbstractFunctionReferenceLowering.kt](../../../../compiler/ir/backend.common/src/org/jetbrains/kotlin/backend/common/lower/AbstractFunctionReferenceLowering.kt)
· **Runs:** after inlining, always.

## NativeSingleAbstractMethodLowering

Most SAM conversions never get here: for a lambda or function reference written where a `fun interface` is expected, the
previous lowering generates a class that implements the interface directly. What is left is a function *value*: a
`Function1` object is not a `Transformer`, so it is wrapped in a generated adapter class.

```kotlin
// before
fun interface Transformer { fun transform(x: Int): Int }
fun runIt(t: Transformer) = t.transform(1)
fun use(f: (Int) -> Int) =
    runIt { it * 3 } + runIt(::twice) +     // written in place: nothing to do here
    runIt(Transformer(f))                   // a function value: in IR the cast-like operation "f as SAM Transformer"

// after
fun use(f: (Int) -> Int) = runIt { it * 3 } + runIt(::twice) + runIt(`sam$Transformer$0`(f))
private class `sam$Transformer$0`(private val function: (Int) -> Int) : Transformer, FunctionAdapter {
    override fun transform(x: Int): Int = function.invoke(x)
    override fun getFunctionDelegate(): Function<*> = function
    // equals() and hashCode(): two adapters are equal when the functions they wrap are equal
}
```

- One adapter class is generated per interface and file, as a private top-level class, and reused for every conversion.

**Source:** [NativeSingleAbstractMethodLowering.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/NativeSingleAbstractMethodLowering.kt),
shared part in [SingleAbstractMethodLowering.kt](../../../../compiler/ir/backend.common/src/org/jetbrains/kotlin/backend/common/lower/SingleAbstractMethodLowering.kt)
· **Runs:** after inlining, always, after `NativeFunctionReferenceLowering`.

## StaticCallableReferenceOptimization

A lambda, function reference or property reference object that captures nothing has no state, so its creation is
replaced with a **constant object**: an instance laid out in the binary's data at compile time, never allocated.

```kotlin
// before (lambdas and references are already top-level classes at this point)
val capturing = `refs$capturing$1`(delta)                          // has a constructor argument
val plain     = `refs$plain$1`()                                   // { x: Int -> x + 1 }
val ref       = `refs$ref$$FUNCTION_REFERENCE_FOR$twice$5`()       // ::twice
val boundRef  = `refs$boundRef$$FUNCTION_REFERENCE_FOR$toString$6`(p)
val unbound   = KProperty1Impl<Person, String>("name", null, `refs$unbound$$FUNCTION_REFERENCE_FOR$<get-name>$1`())

// after: `capturing` and `boundRef` are unchanged and still allocated on every call
val plain     = <constant refs$plain$1>
val ref       = <constant refs$ref$$FUNCTION_REFERENCE_FOR$twice$5>
val unbound   = <constant KProperty1Impl("name", null, <constant …FUNCTION_REFERENCE_FOR$<get-name>$1>)>
```

- The rule for function classes is "the constructor has no parameters". For a `KProperty` object it is "all constructor
  arguments are themselves constants", which holds when the getter and setter references became constants first.

**Source:** [StaticCallableReferenceOptimization.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/StaticCallableReferenceOptimization.kt)
· **Runs:** after inlining, always (not only with `-opt`), after local classes were moved to the top level.
