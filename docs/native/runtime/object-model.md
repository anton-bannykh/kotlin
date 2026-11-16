# The object model

What a Kotlin object looks like in memory, and how the program finds an object's class, its fields and its virtual
functions. There is no virtual machine to keep this knowledge: the compiler writes a description of every class into the
binary as a constant, and every object starts with a pointer to the description of its class. The generated code and the
C++ [runtime](README.md) both read these structures directly, so they must agree on every offset.

All examples are real compiler output for linux_x64, shortened, for:

```kotlin
interface Shape { fun area(): Double }
open class Circle(val r: Double) : Shape { override fun area() = 3.14 * r * r; open fun name() = "circle" }
```

## An object

```
 reference ──► ┌──────────────────────┐ 0
               │ header: one pointer  │ ───► @"kclass:Circle"   (the TypeInfo of the class, see below)
               ├──────────────────────┤ 8
               │ r: Double            │
               └──────────────────────┘ 16
```

In LLVM IR this is a struct type per class. The header is the runtime's `ObjHeader`, a single pointer; fields of
superclasses come first. A reference is the address of the header, and `null` is the null pointer.

```llvm
%struct.ObjHeader = type { ptr }
%"kclassbody:Circle#internal" = type <{ %struct.ObjHeader, double }>
```

## An array

```
 IntArray(3)   ┌──────────────────────┐ 0
               │ header: one pointer  │ ───► @"kclass:kotlin.IntArray"
               ├──────────────────────┤ 8
               │ length: 32-bit int   │
               ├──────────────────────┤ 16
               │ [0] │ [1] │ [2]      │   elements, stored inline; for Array<T> each element is a reference
               └──────────────────────┘
```

Arrays are the only objects whose size is not fixed by their class. A `String` is such an array too: an array of 16-bit
units, with the hash code and a flags word packed in front of the characters. Characters are normally UTF-16.

## TypeInfo: the description of a class

One **TypeInfo** constant exists per class and per interface. It is the same C++ struct, `TypeInfo`, that the runtime
uses. The table of virtual functions, the **vtable**, is placed directly after it in memory.

```llvm
@"kclass:Circle" = constant { %struct.TypeInfo, [5 x ptr] } {
  %struct.TypeInfo {
    ptr @"kclass:Circle",                  ; pointer to itself (see "Things to know")
    ptr @1854, i32 0,                      ; names and types of the fields, for the debugger; an unused field
    i32 16,                                ; instance size. Negative for arrays: minus the element size (IntArray has -4)
    ptr @"kclass:kotlin.Any",              ; superclass
    ptr null, i32 0,                       ; offsets of the fields that hold references, and how many: none here
    ptr @"kintf:Circle", i32 1,            ; implemented interfaces: [Shape]
    i32 0, ptr @"kifacetable:Circle",      ; the interface table (below)
    ptr @1851, ptr @1855,                  ; package name "" and class name "Circle", as String constants
    i32 770, i32 0, ptr null,              ; flags, class id, associated objects
    ptr @Kotlin_processEmptyObjectInMark,  ; the function the GC calls to visit this object's references
    i32 8 },                               ; alignment of an instance
  [5 x ptr] [                              ; the vtable
    ptr @"kfun:kotlin.Any#equals(kotlin.Any?){}kotlin.Boolean-impl",       ; 0, inherited from Any
    ...hashCode, toString...                                               ; 1, 2
    ptr @"kfun:Circle#area(){}kotlin.Double-impl",                         ; 3
    ptr @"kfun:Circle#name(){}kotlin.String-impl"] }                       ; 4
```

Who uses what:
- **Allocation** reads the instance size and alignment.
- **The GC** needs to know which fields are references. A class with a field `val next: Point?` at offset 8 has
  `@"krefs:Point" = [1 x i32] [i32 8]` there, and `Kotlin_processObjectInMark` as the visiting function.
  → [gc-and-allocator.md](gc-and-allocator.md)
- **Type checks** (`is`, `as`) compare against the superclass, interface and class id data.
  → [optimizations/type-checks.md](../compiler/optimizations/type-checks.md)

## Virtual calls

A subclass's vtable starts with its superclass's entries, with overridden ones replaced, and adds its own at the end. So
`name()` is entry 4 in `Circle` and in every subclass of `Circle`, and a call is: load the TypeInfo from the header, step
over it to the vtable, load entry 4, call it. → [codegen.md](../compiler/codegen.md#a-virtual-call-and-an-interface-call)

## Interface calls

A fixed entry number does not work for interfaces: `Shape.area` would need the same number in every class that implements
`Shape`, whatever else the class extends or implements. Instead each class has an **interface table** with one record per
implemented interface. A record holds the interface's id and a small vtable with this class's implementations:

```llvm
@"kifacetable:Circle" = internal constant [1 x %struct.InterfaceTableRecord]
    [{ i32 -2057073250, i32 1, ptr @"kifacevtable:Circle_-2057073250" }]       ; id of Shape, 1 function, its table
@"kifacevtable:Circle_-2057073250" = internal constant [1 x ptr] [ptr @"kfun:Circle#area(){}kotlin.Double-impl"]
```

Finding the record has a fast path and a slow path. Normally the table is a small hash table whose size is chosen so that
no two interfaces of the class collide, and the record is at index `id & (size - 1)`, with no search. If no small enough
size works, the table is sorted by id and the runtime function `LookupInterfaceTableRecord` searches it.

## Boxing

Primitives and value classes are not objects. They are passed as machine values and become an object, a **box**, only where
an object is required: as `Any`, as a generic argument, as a nullable type.

```llvm
define double @"kfun:#twice(Meters){}Meters"(double %m)          ; fun twice(m: Meters): Meters, Meters is a value class
%any = call ptr @"kfun:kotlin#<Int-box>(kotlin.Int){}kotlin.Any"(i32 %x, ptr %slot)     ; fun boxed(x: Int): Any = x
```

A box is an ordinary object of class `kotlin.Int` (or `Meters`): header plus the value. `<Int-box>` allocates one, except
for values from -128 to 127, which come from a table of 256 ready-made boxes in the binary. The lowering that inserts
the box and unbox calls is [Autoboxing](../compiler/lowerings/Autoboxing.md).

## Objects that are constants

Some objects are built by the compiler and placed in the binary's constant data: string literals, the box tables, `Unit`,
constant arrays, `KClass` and `KType` instances. They are called **permanent** objects.

```llvm
@2267 = internal unnamed_addr constant { ptr, i32, i32, i16, [10 x i8] }      ; "hello": header, length, hash, flags, chars
    { ptr getelementptr (i8, ptr @"kclass:kotlin.String", i32 1), i32 6, i32 99162322, i16 0,
      [10 x i8] c"h\00e\00l\00l\00o\00" }
```

The header here is the TypeInfo address **plus 1**. TypeInfos are aligned, so the two lowest bits of the header are free
and are used as a tag: 0 is a normal heap object, 1 is a permanent object, 3 is an object allocated on the stack by
[escape analysis](../compiler/optimizations/escape-analysis.md). The GC never frees a permanent object.

## Things to know

- **The layout is defined once, in the runtime's C++ headers.** The compiler does not repeat the definitions: it loads the
  struct types (`%struct.TypeInfo`, `%struct.ObjHeader`, ...) from the runtime's bitcode. But the code that fills a TypeInfo
  lists the fields by position, so a field added in `TypeInfo.h` must be added in `RTTIGenerator.kt` as well.
- **Never read the header as a TypeInfo pointer directly.** Besides the tag bits, the header can point to a *meta object*:
  extra data the runtime attaches to a few objects on demand, for example for weak references. A meta object also starts
  with a TypeInfo pointer, and a TypeInfo starts with a pointer to itself, so "clear the tag, then load the first word"
  always gives the TypeInfo. That is what `Kotlin_Any_getTypeInfo` does.
- **Fields are reordered.** Within a class they are sorted by size, largest first, to avoid padding. `Point(val x: Int,
  val next: Point?)` is laid out as header, `next`, `x`.

## Where the code is

- [Memory.h](../../../kotlin-native/runtime/src/main/cpp/Memory.h): `ObjHeader`, `ArrayHeader`, the tag bits
- [TypeInfo.h](../../../kotlin-native/runtime/src/main/cpp/TypeInfo.h): `TypeInfo`, `InterfaceTableRecord`. [KString.h](../../../kotlin-native/runtime/src/main/cpp/KString.h): `String`
- [RTTIGenerator.kt](../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/llvm/RTTIGenerator.kt): builds the TypeInfo constants, vtables and interface tables
- [ClassLayoutBuilder.kt](../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/ir/ClassLayoutBuilder.kt): decides field order and vtable entry numbers
