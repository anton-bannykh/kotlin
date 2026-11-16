# NativeAnnotationImplementationLowering

Replaces every constructor call of an annotation class in ordinary code (`Tag("a")`) with a constructor call of a generated
implementation class that extends the annotation class and adds `equals`, `hashCode` and `toString`.

**Why:** an annotation class is declared without a body, yet the language says that two instances with equal arguments are
equal and print as `@Tag(name=a, level=1)`. Some class has to contain that code.

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
    override fun hashCode(): Int =
        ("name".hashCode() * 127 xor name.hashCode()) + ("level".hashCode() * 127 xor level.hashCode())
    override fun toString(): String = "@Tag(name=" + name + ", level=" + level + ")"
}
```

**Know this**
- The implementation class does not evaluate default values itself. It passes on exactly the arguments the call site
  gave, and the annotation class's own constructor fills in the rest. When the annotation comes from another module, its
  default value expressions may not be available (incremental compilation), so they cannot be copied here.
- The class is private to the file, so each file that instantiates `Tag` gets its own copy. Instances from different files
  are still equal, because `equals` checks `other is Tag`, not the implementation class.
- Annotations *applied* to declarations (`@Tag("a") fun f()`) are not touched: this is only about instances created in code.

**Source:** [NativeAnnotationImplementationLowering.kt](../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/NativeAnnotationImplementationLowering.kt),
shared part in [AnnotationImplementationTransformer.kt](../../../compiler/ir/backend.common/src/org/jetbrains/kotlin/backend/common/lower/AnnotationImplementationTransformer.kt)
· **Runs:** after inlining, always.
