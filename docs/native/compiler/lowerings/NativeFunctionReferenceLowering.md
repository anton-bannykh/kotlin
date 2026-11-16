# NativeFunctionReferenceLowering

Turns every lambda and every function reference (`::twice`, `p::toString`) into a local class with an `invoke` method, plus a
constructor call that creates the instance.

**Why:** a function value is an object: it can be stored, passed around and called through `Function1.invoke`. Codegen only
knows classes and virtual calls, so each lambda needs its own class.

```kotlin
// before
fun twice(x: Int) = x * 2
fun refs(delta: Int, p: Person) {
    val capturing = { x: Int -> x + delta }
    val ref = ::twice
    val boundRef = p::toString
}

// after (each class is declared locally, in a block right where the value is created)
fun refs(delta: Int, p: Person) {
    local class `<no name provided>` : Function1<Int, Int> {
        override fun invoke(x: Int): Int = x + delta                // still uses the outer variable directly
    }
    val capturing = `<no name provided>`()
    local class `$FUNCTION_REFERENCE_FOR$twice$5` : KFunctionImpl<Int>(<constant description>), KFunction1<Int, Int> {
        override fun invoke(p0: Int): Int = twice(p0)
    }
    val ref = `$FUNCTION_REFERENCE_FOR$twice$5`()
    local class `$FUNCTION_REFERENCE_FOR$toString$6`(p0: Person) : KFunctionImpl<String>(<…>), KFunction0<String> {
        private val `f$0`: Person = p0                               // a bound receiver becomes a field
        override fun invoke(): String = `f$0`.toString()
        override fun boundValueAt(index: Int): Any? = `f$0`          // lets equals() compare bound receivers
    }
    val boundRef = `$FUNCTION_REFERENCE_FOR$toString$6`(p)
}
```

**Know this**
- Captured variables are not handled here. The class is local, so its body may still use `delta`. Later lowerings (see
  [NativeLocalDeclarationsLowering](NativeLocalDeclarationsLowering.md)) give it a name (`refs$capturing$1`), turn `delta`
  into a constructor parameter and a field, and move the class to the top level of the file.
- A lambda's class extends `Any`. A function reference extends `KFunctionImpl` and carries a constant description (name,
  arity, return type, …), so that `::twice == ::twice` and `.name` work.
- A lambda converted to a `fun interface` is done here too: its class implements the interface directly.

**Source:** [NativeFunctionReferenceLowering.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/NativeFunctionReferenceLowering.kt),
shared part in [AbstractFunctionReferenceLowering.kt](../../../../compiler/ir/backend.common/src/org/jetbrains/kotlin/backend/common/lower/AbstractFunctionReferenceLowering.kt)
· **Runs:** after inlining, always. Lambdas passed to inline functions are already gone by then.
