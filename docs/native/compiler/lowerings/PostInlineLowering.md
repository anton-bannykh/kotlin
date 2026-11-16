# PostInlineLowering

Rewrites three kinds of expressions that are only final once inlining is done: class references (`Foo::class`,
`x::class`), `immutableBlobOf(...)`, and constructor calls of the `KType` implementation classes with constant arguments.

**Why:** codegen has no notion of a class reference. A reference to a known class can be a constant object in the binary;
the class of a value has to be read from the object at run time.

```kotlin
// before
fun show(x: Any) {
    println(String::class)
    println(x::class)
}
fun blob() = immutableBlobOf(1, 2, 255)

// after
fun show(x: Any) {
    println(const KClassImpl<String>)                           // a constant object: nothing is allocated
    println(KClassImpl<Any>(typeInfo = getObjectTypeInfo(x)))   // a new wrapper around the object's type information
}
fun blob() = immutableBlobOfImpl(data = "\u0001\u0002ÿ")        // the bytes, packed into one string constant
```

`getObjectTypeInfo` reads the pointer to the class description that every object starts with, see
[object-model.md](../../runtime/object-model.md).

**Know this**
- `immutableBlobOf` takes a `vararg`. It is rewritten here so that
  [VarargInjectionLowering](VarargInjectionLowering.md) never sees it and does not build an array at run time. Every
  element must be a constant between 0 and 255.
- `typeOf` is not handled here but in [TypeOfProcessingLowering](TypeOfProcessingLowering.md). This lowering only folds
  an explicit `KTypeImpl(...)` call (and the like) whose arguments are all constants into a constant object.
- For some interop types (Objective-C protocols, Kotlin subclasses of Objective-C classes, C structs and other
  `NativePointed` types) `Foo::class` becomes a constant "unsupported" object that carries a message, not a real `KClass`.

**Source:** [PostInlineLowering.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/PostInlineLowering.kt)
· **Runs:** after inlining, always.
