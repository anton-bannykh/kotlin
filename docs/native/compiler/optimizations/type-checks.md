# Type checks: `is` and `as`

On the JVM, `x is Dog` is one bytecode instruction and the virtual machine does the rest. Kotlin/Native has to generate the
check itself. Every object starts with a pointer to a constant describing its class, the **type info**
(→ [object-model.md](../../runtime/object-model.md)). A type check reads that pointer and has to answer: is this class `Dog`, or one
of its subclasses? This page shows how the answer is made cheap, and which checks are not generated at all.

```kotlin
open class Animal
open class Dog : Animal()
class Puppy : Dog()
class Cat : Animal()
interface Named
class Robot : Named

fun isDog(x: Any): Boolean = x is Dog
fun isNamed(x: Any): Boolean = x is Named
```

## The general check (debug builds)

Without further knowledge, the only way is to search. The generated code calls the runtime function `IsSubtype`, which
walks up the chain of superclasses of the object's class until it finds `Dog` or reaches the top. For an interface it
scans the list of all interfaces the object's class implements.

```llvm
  %5 = call ptr @Kotlin_Any_getTypeInfo(ptr %0)                         ; the object's class
  %6 = call i1 @IsSubtype(ptr %5, ptr @"kclass:Dog")                    ; a loop inside
```

## Classes: two comparisons

With `-opt` the compiler sees every class of the program, so it can number them. It walks the class tree depth-first and
gives each class the next free number. Then all subclasses of a class have consecutive numbers right after it, and each
class can be described by an interval: its own number up to the number of its last descendant. These are the real numbers
from the sample:

```
Any
└── Animal      id 445    interval [445, 448]
    ├── Dog     id 446    interval [446, 447]
    │   └── Puppy  id 447    interval [447, 447]
    └── Cat     id 448    interval [448, 448]
```

`x is Dog` is now "is the id of x's class between 446 and 447?". The id is stored in the type info; the interval is known
at compile time and written into the code as two constants:

```llvm
  %3 = call ptr @Kotlin_Any_getTypeInfo(ptr %0)
  %4 = call i1 @IsSubclassFast(ptr %3, i32 446, i32 447)                ; typeInfo->classId_ >= 446 && typeInfo->classId_ <= 447
```

`IsSubclassFast` is a one-line function of the runtime. The cost is a load and two comparisons, no matter how deep the
hierarchy is.

## Interfaces: one table lookup

A class can implement many interfaces, so intervals do not work. Instead, every class gets a small hash table of the
interfaces it implements (the same table is used to call interface methods). Each interface gets a number, and the numbers
are chosen for the whole program so that two interfaces implemented by the same class never land in the same slot. A
lookup therefore needs no search: go to the one slot where `Named` would be and see whether it is there.

```llvm
  %5 = load i32, ptr %4                         ; typeInfo->interfaceTableSize_ (stored as size - 1, a bit mask)
  %7 = load ptr, ptr %6                         ; typeInfo->interfaceTable_
  %8 = and i32 %5, 304                          ; 304 is the id of Named; mask it to get the slot index
  %9 = getelementptr %struct.InterfaceTableRecord, ptr %7, i32 %8
  %11 = load i32, ptr %10                       ; the id stored in that slot
  %12 = icmp eq i32 %11, 304                    ; is it Named?
```

Both numberings are computed by a step called **GHA** (global hierarchy analysis), one of the
[whole-program optimizations](README.md). It needs the complete class hierarchy: adding one class later would
shift the numbers. So it runs only with `-opt` and without precompiled [caches](../caches.md). Everywhere else the general
check is used.

## Checks that are never generated

Before any of this, the compiler removes the checks it can decide from static types:

```kotlin
fun upcast(d: Dog): Animal = d as Animal        // Dog is a subclass of Animal: no type check at all

fun twice(x: Any): String {
    if (x is Dog) {                             // one real check
        return x.sound()                        // the smart cast to Dog costs nothing: it was just proven
    }
    return ""
}

fun safe(x: Any): Dog? = x as? Dog              // rewritten by a lowering to: if (x is Dog) x else null
```

After inlining, the compiler also recomputes the types of local variables, because inlined generic code leaves many of
them typed `Any?`; with the precise type, more casts turn out to be upcasts and disappear.

## Casts caused by generics

Generics are erased: `List<Dog>.get()` returns `Any?` at run time. When you write

```kotlin
fun first(list: List<Dog>): Dog = list[0]
```

the frontend has verified the types, but an unchecked cast elsewhere (`list as List<Dog>`) could have put a `Cat` into the
list. The JVM always checks at this point. Kotlin/Native checks **only in debug builds** by default:

```llvm
; debug build
  %5 = call ptr @"kfun:kotlin.collections.List#get(kotlin.Int){}1:0"(ptr %0, i32 0, ptr %4)
  %6 = call ptr @"kfun:kotlin.native.internal#downcast(...)"(ptr %5, ptr @"kclass:Dog", i1 true, ptr %1)
                                                ; downcast throws ClassCastException if %5 is not a Dog

; -opt build: the result of get() is returned as is
```

The option is called `genericSafeCasts` (`-Xbinary=genericSafeCasts=true` or `false`). It is off in `-opt` builds because
of the cost of the checks. When it is on, an extra pass (`CastsOptimization`) follows conditions through each function
and removes checks that an earlier `is` or `as` on the same value has already proven.

## Things to know

- **A wrong unchecked cast behaves differently in debug and `-opt`.** Debug throws `ClassCastException` where the value is
  used. An `-opt` build goes on with an object of the wrong class and may read its fields at the wrong offsets.
- **`as` throws through the runtime.** A failed cast calls `ThrowClassCastException`; `as` to a non-null type applied to
  `null` throws `NullPointerException`. In debug builds the whole cast is a call to the stdlib function `downcast`.
- **Class ids differ from build to build.** They depend on the set of classes in the program. Never rely on a number.
- **Objective-C classes are checked differently**, by asking the Objective-C runtime (→ [cinterop.md](../../interop/cinterop.md)).
- [Devirtualization](devirtualization.md) uses an even cheaper test in its `when`: comparing the type info pointer with
  one class, which is enough when the exact class is known.

## Where the code is

- [ClassLayoutBuilder.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/ir/ClassLayoutBuilder.kt):
  class `GlobalHierarchyAnalysis` assigns the numbers. Its comments explain both schemes.
- [CodeGenerator.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/llvm/CodeGenerator.kt):
  `checkIsSubtype` chooses between the general and the fast check.
- [Types.cpp](../../../../kotlin-native/runtime/src/main/cpp/Types.cpp): `IsSubtype` and `IsSubclassFast` in the runtime.
- [CastsOptimization.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/optimizations/CastsOptimization.kt)
  and [ComputeTypesPass.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/optimizations/ComputeTypesPass.kt):
  removal of redundant checks.
- [NativeSecondStageCompilationConfig.kt](../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/NativeSecondStageCompilationConfig.kt):
  `genericSafeCasts` and its default.
