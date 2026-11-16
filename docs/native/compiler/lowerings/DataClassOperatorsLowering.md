# DataClassOperatorsLowering

Finishes the generated `toString()` and `hashCode()` of data classes for properties of an array type: the placeholder
calls left there by the frontend are replaced with the standard library's `contentToString()` and `contentHashCode()`.

**Why:** the generated members of a data class are built by code shared between all backends. For an array property that
code cannot name a function to call, because the right one differs per platform. It emits a placeholder and each backend
substitutes its own.

```kotlin
data class User(val name: String, val tags: Array<String>)

// before
override fun toString(): String =
    "User(name=" + name + ", tags=" + dataClassArrayMemberToString(tags) + ")"
override fun hashCode(): Int {
    var result = name.hashCode()
    result = result * 31 + dataClassArrayMemberHashCode(tags)
    return result
}

// after
override fun toString(): String =
    "User(name=" + name + ", tags=" + tags.contentToString() + ")"
override fun hashCode(): Int {
    var result = name.hashCode()
    result = result * 31 + tags.contentHashCode()
    return result
}
```

**Know this**
- Nothing else in a data class needs this lowering. `equals`, `copy` and `componentN` arrive as ordinary IR, and
  `equals` compares an array property by reference, as the language defines.
- The overload is chosen by the static type of the property: `Array<T>`, `IntArray` and so on.

**Source:** [DataClassOperatorsLowering.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/DataClassOperatorsLowering.kt)
· **Runs:** after inlining, always.
