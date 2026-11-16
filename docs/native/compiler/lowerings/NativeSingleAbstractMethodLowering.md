# NativeSingleAbstractMethodLowering

Handles the SAM conversions that are still left: a function *value* (not a lambda written in place) used where a
`fun interface` is expected. It wraps the value in a generated adapter class that implements the interface.

**Why:** a `Function1` object is not a `Transformer`. Something has to implement `Transformer.transform` and forward to
`invoke`.

```kotlin
// before
fun interface Transformer { fun transform(x: Int): Int }
fun runIt(t: Transformer) = t.transform(1)

fun use(f: (Int) -> Int) =
    runIt { it * 3 } +              // lambda in place: already a class implementing Transformer, nothing to do here
    runIt(::twice) +                // same for a function reference
    runIt(Transformer(f))           // a function value: in IR this is the cast-like operation "f as SAM Transformer"

// after
fun use(f: (Int) -> Int) = runIt { it * 3 } + runIt(::twice) + runIt(`sam$Transformer$0`(f))

private class `sam$Transformer$0`(private val function: (Int) -> Int) : Transformer, FunctionAdapter {
    override fun transform(x: Int): Int = function.invoke(x)
    override fun getFunctionDelegate(): Function<*> = function
    override fun equals(other: Any?) =
        other is Transformer && other is FunctionAdapter && getFunctionDelegate() == other.getFunctionDelegate()
    override fun hashCode() = getFunctionDelegate().hashCode()
}
```

**Know this**
- Most SAM conversions never reach this lowering. For a lambda or a function reference written at the conversion site,
  [NativeFunctionReferenceLowering](NativeFunctionReferenceLowering.md) generates a class that implements the interface
  directly, with no adapter and no extra call.
- One adapter class is generated per interface and file, as a private top-level class, and reused for every conversion.
- Two adapters are equal when the functions they wrap are equal.

**Source:** [NativeSingleAbstractMethodLowering.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/NativeSingleAbstractMethodLowering.kt),
shared part in [SingleAbstractMethodLowering.kt](../../../../compiler/ir/backend.common/src/org/jetbrains/kotlin/backend/common/lower/SingleAbstractMethodLowering.kt)
· **Runs:** after inlining, always, after NativeFunctionReferenceLowering.
