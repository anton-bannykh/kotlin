[Kotlin/Native guide](../../README.md) › [compiler](../README.md) › [lowerings](README.md) › classes-and-local-declarations

# Classes and local declarations

*Skip this page if you know how captured variables, the outer `this` and generated class members become explicit IR.
Reference material.*

Codegen only knows top-level functions and classes with fields. These lowerings, all after inlining, make explicit what the
language keeps implicit: the variables a local function or class captures, the outer instance of an inner class, and
members generated for data classes, annotations and `@Volatile` properties. Only the first two depend on each other.

## NativeSharedVariablesPrimitiveBoxSpecializationLowering

A local `var` that a lambda modifies cannot stay on the stack, so before inlining the shared `SharedVariablesLowering`
moves it into a small heap object, a **box**. That box is generic, so an `Int` in it would itself be boxed: two
allocations and two indirections for one counter. This lowering switches to `SharedVariableBoxInt`, which has a plain
`Int` field; there is one such class per primitive type.

```kotlin
fun counter(): () -> Int { var n = 0; return { n++ } }      // source

// before (SharedVariablesLowering has already run)
val n: SharedVariableBox<Int> = SharedVariableBox<Int>(element = 0)
return { val old = n.element; n.element = old.inc(); old }

// after: type, constructor and accessors replaced. A `var s = ""` keeps its SharedVariableBox<String>.
val n: SharedVariableBoxInt = SharedVariableBoxInt(element = 0)
return { val old = n.element; n.element = old.inc(); old }
```

- The boxes are ordinary stdlib classes (`kotlin.internal.SharedVariableBox*`). The logic is shared; the Native class
  only supplies the Native symbols.

**Source:** [SharedVariablesPrimitiveBoxSpecializationLowering.kt](../../../../compiler/ir/backend.common/src/org/jetbrains/kotlin/backend/common/lower/SharedVariablesPrimitiveBoxSpecializationLowering.kt),
[SharedVariablesLowering.kt](../../../../compiler/ir/backend.common/src/org/jetbrains/kotlin/backend/common/lower/SharedVariablesLowering.kt)
· **Runs:** after inlining, always.

## NativeLocalDeclarationsLowering

Makes local functions, local classes and anonymous objects independent of the function they are declared in. A function
cannot read the local variables of another function, so every captured variable becomes an explicit parameter (for a
function) or a constructor parameter plus a field (for a class).

```kotlin
// before
fun count(prefix: String, items: List<String>): Int {
    var n = 0                          // already a SharedVariableBoxInt here
    fun visit(s: String) { if (s.startsWith(prefix)) n++ }
    class Counter(val step: Int) { fun bump() { n += step } }
    for (i in items) visit(i)
    Counter(2).bump()
    return n
}

// after NativeLocalDeclarationsLowering + LocalDeclarationPopupLowering
fun count(prefix: String, items: List<String>): Int {
    val n = SharedVariableBoxInt(0)
    for (i in items) `count$visit`(prefix, n, i)       // captured values are passed at every call
    `count$Counter`(n, step = 2).bump()
    return n.element
}
private fun `count$visit`(`$prefix`: String, n: SharedVariableBoxInt, s: String) {
    if (s.startsWith(`$prefix`)) n.element++
}
private class `count$Counter`(`$n`: SharedVariableBoxInt, val step: Int) {
    private val `$n` = `$n`                            // captured value stored in a field
    fun bump() { this.`$n`.element += step }
}
```

It is the third of four steps, all shared code for which Native only fixes the naming scheme and the place in the list:

1. `NativeInventNamesForLocalClasses` names local classes and anonymous objects: `count$Counter`, `count$o$1` for
   `val o = object : ... {}`. An anonymous object's invented name is hidden from reflection.
2. `NativeKlibInventNamesForLocalFunctions` records names for local functions: `count$visit`, and `count$visit$0` for a
   second `visit` in the same function.
3. `NativeLocalDeclarationsLowering` renames the functions and adds the parameters and fields, leaving everything nested.
4. `LocalDeclarationPopupLowering` *(shared)* moves the declarations to the enclosing class or file.

- The classes that [NativeFunctionReferenceLowering](callable-references.md#nativefunctionreferencelowering) made from
  lambdas are local classes too, and get their captured variables here.

**Source:** [NativeLowerLocalDeclarations.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/NativeLowerLocalDeclarations.kt),
[LocalDeclarationsLowering.kt](../../../../compiler/ir/backend.common/src/org/jetbrains/kotlin/backend/common/lower/LocalDeclarationsLowering.kt) (shared)
· **Runs:** after inlining, always.

## InnerClassLowering

Gives every `inner class` a field that holds the instance of the outer class, and rewrites each use of the outer `this`
into a read of that field.

```kotlin
// before
class Outer(val x: Int) {
    inner class Inner(val y: Int) {
        fun sum() = x + y                          // `x` means this@Outer.x
    }
}

// after
class Outer(val x: Int) {
    inner class Inner {
        protected val `$this$0`: Outer
        constructor(y: Int) {
            this.`$this$0` = <outer>                // first statement, before the super constructor call
            super(); this.y = y
        }
        fun sum() = this.`$this$0`.x + this.y       // from a doubly nested inner class: this.`$this$0`.`$this$0`.x
    }
}
```

- Call sites such as `o.Inner(2)` do not change: in IR the constructor of an inner class already has a receiver, the outer
  instance (`<outer>` above), and the lowering simply stores it.
- Because the field is assigned first, a base-class constructor that calls an overridden method already sees a valid outer
  instance.

**Source:** [InnerClassLowering.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/InnerClassLowering.kt) · **Runs:** after inlining, always.

## DataClassOperatorsLowering

The generated members of a data class are built by code shared between all backends. For an array property that code
cannot name the function to call in `toString()` and `hashCode()`, because it differs per platform, so it leaves a
placeholder. This lowering substitutes the stdlib function.

```kotlin
data class User(val name: String, val tags: Array<String>)

// before, inside toString() and hashCode()
"User(name=" + name + ", tags=" + dataClassArrayMemberToString(tags) + ")"
result = result * 31 + dataClassArrayMemberHashCode(tags)

// after
"User(name=" + name + ", tags=" + tags.contentToString() + ")"
result = result * 31 + tags.contentHashCode()
```

- Nothing else in a data class needs this lowering. `equals`, `copy` and `componentN` arrive as ordinary IR, and `equals`
  compares an array property by reference, as the language defines.

**Source:** [DataClassOperatorsLowering.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/DataClassOperatorsLowering.kt) · **Runs:** after inlining, always.

## NativeAnnotationImplementationLowering

An annotation class is declared without a body, yet two instances with equal arguments must be equal and print as
`@Tag(name=a, level=1)`. So every constructor call of an annotation class in ordinary code becomes a constructor call of
a generated subclass with `equals`, `hashCode` and `toString`.

```kotlin
// before
annotation class Tag(val name: String, val level: Int = 1)
fun makeTag(): Tag = Tag("a")
fun makeTag2(): Tag = Tag("b", 2)

// after
fun makeTag(): Tag = `annotationImpl$Tag$0`(name = "a")
fun makeTag2(): Tag = `annotationImpl$Tag$0`(name = "b", level = 2)
private class `annotationImpl$Tag$0` : Tag {
    constructor(name: String) : super(name = name)                          // one constructor per set of arguments
    constructor(name: String, level: Int) : super(name = name, level = level)   // that some call in this file passes
    override fun equals(other: Any?) = other is Tag && name == other.name && level == other.level
    override fun hashCode(): Int = ...                                      // combines names and values
    override fun toString(): String = "@Tag(name=" + name + ", level=" + level + ")"
}
```

- The implementation class passes on only the arguments of the call site; the annotation class's own constructor fills in
  the defaults. The default value expressions of an annotation from another module may not be available (incremental
  compilation), so they cannot be copied here.
- The class is private to the file, so each file that instantiates `Tag` gets its own copy. Instances from different files
  are still equal, because `equals` checks `other is Tag`. Annotations *applied* to declarations are not touched.

**Source:** [NativeAnnotationImplementationLowering.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/NativeAnnotationImplementationLowering.kt),
shared part in [AnnotationImplementationTransformer.kt](../../../../compiler/ir/backend.common/src/org/jetbrains/kotlin/backend/common/lower/AnnotationImplementationTransformer.kt)
· **Runs:** after inlining, always.

## VolatileFieldsLowering

The stdlib atomics (`kotlin.concurrent.atomics.AtomicInt` and friends) are ordinary classes with one `@Volatile` field,
and their operations are intrinsics on a property reference, which must not become a real reference object. For every
`@Volatile` property this lowering adds member functions for compare-and-set, exchange and add, and rewrites the
intrinsics into calls of them. Codegen turns each into a single atomic instruction on the field.

```kotlin
// before
class AtomicInt(@Volatile var value: Int) {                             // from the stdlib, shortened
    fun load(): Int = this::value.atomicGetField()
    fun compareAndSet(expected: Int, new: Int): Boolean = this::value.compareAndSetField(expected, new)
}
class Job { @Volatile var done: Boolean = false }

// after
class AtomicInt(@Volatile var value: Int) {
    @TypedIntrinsic("COMPARE_AND_SET") external fun `<compareAndSet-value>`(expectedValue: Int, newValue: Int): Boolean
    // likewise `<compareAndExchange-value>`, `<getAndSet-value>` and, for integer fields only, `<getAndAdd-value>`
    fun load(): Int = this.value                                        // the ordinary getter
    fun compareAndSet(expected: Int, new: Int): Boolean = this.`<compareAndSet-value>`(expected, new)
}
class Job { @Volatile var done: Byte = if (false) 1 else 0 }   // a Boolean is stored as a Byte, converted at every access
```

- Plain reads and writes of a `@Volatile` field are not touched: codegen sees the annotation and emits an atomic load or
  store. That is why `atomicGetField` can simply become the getter.
- The intrinsics only work when the receiver is visibly a reference to a `@Volatile` property (`this::value`). Anything
  else is replaced by a call that throws `IllegalArgumentException` at run time; it is not a compile error.

**Source:** [VolatileFieldsLowering.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/VolatileFieldsLowering.kt)
· **Runs:** after inlining, always, before [PropertyReferenceLowering](callable-references.md#propertyreferencelowering),
which would otherwise turn `this::value` into an object.
