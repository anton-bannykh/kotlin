[Kotlin/Native guide](../README.md) › features

# Language features: where each one is explained

*Skip this page unless you are looking for a language feature. Reference material.*

Most pages of this guide describe one part of Kotlin/Native, so a language feature may be spread over several of them. This
page goes the other way: find the feature, then follow the links in the order given (from the compiler to the runtime).

## Features with a page of their own

Three features need lowerings, code generation and the runtime to work together. Each has a page that follows it end to end:

| Feature | Page | Parts involved |
|---|---|---|
| Top-level properties, objects, companions: when they are initialized | [initialization.md](initialization.md) | lowerings, a check emitted by code generation, a slow path in the runtime |
| `suspend` functions | [coroutines.md](coroutines.md) | several lowerings that build a [state machine](https://github.com/Kotlin/KEEP/blob/master/proposals/coroutines.md#state-machines), a jump emitted by code generation, the stdlib |
| `throw`, `try`, `catch`, `finally` | [exceptions.md](exceptions.md) | a lowering for `finally`, landing pads from code generation, the runtime and the system unwinder |

## Classes and objects

| Feature | Where it is explained |
|---|---|
| A class, its fields, its instances | [what an object looks like in memory](../runtime/object-model.md#an-object) · [reading a field](../compiler/codegen.md#reading-a-field) · [allocation](../compiler/codegen.md#allocating-an-object-returning-it-and-keeping-it-alive) |
| Constructors | [ConstructorsLowering](../compiler/lowerings/functions.md#constructorslowering) |
| `object`, `companion object` | [ObjectClassLowering](initialization.md#objectclasslowering) |
| `enum class`, `when` over an enum | [enums.md](../compiler/lowerings/enums.md) |
| `data class` | [DataClassOperatorsLowering](../compiler/lowerings/classes-and-local-declarations.md#dataclassoperatorslowering) |
| `inner class` | [InnerClassLowering](../compiler/lowerings/classes-and-local-declarations.md#innerclasslowering) |
| Local classes and local functions | [NativeLocalDeclarationsLowering](../compiler/lowerings/classes-and-local-declarations.md#nativelocaldeclarationslowering) |
| `value class` | [boxing.md](../compiler/lowerings/boxing.md) · [boxes in memory](../runtime/object-model.md#boxing) |
| Annotation instances | [NativeAnnotationImplementationLowering](../compiler/lowerings/classes-and-local-declarations.md#nativeannotationimplementationlowering) |
| `@Volatile` and atomic fields | [VolatileFieldsLowering](../compiler/lowerings/classes-and-local-declarations.md#volatilefieldslowering) |

## Calls and functions

| Feature | Where it is explained |
|---|---|
| A function and its name in the binary | [codegen.md](../compiler/codegen.md#a-function-and-its-name) |
| Virtual calls (`open`, `override`) | [in LLVM IR](../compiler/codegen.md#a-virtual-call-and-an-interface-call) · [the vtable](../runtime/object-model.md#virtual-calls) · [devirtualization.md](../compiler/optimizations/devirtualization.md) |
| Interface calls | [in LLVM IR](../compiler/codegen.md#a-virtual-call-and-an-interface-call) · [the interface table](../runtime/object-model.md#interface-calls) · [devirtualization.md](../compiler/optimizations/devirtualization.md) |
| Overrides whose signature differs after erasure | [BridgesBuilding](../compiler/lowerings/boxing.md#bridgesbuilding) |
| Default arguments | [NativeDefaultArgumentStubGenerator](../compiler/lowerings/functions.md#nativedefaultargumentstubgenerator) |
| `vararg` | [VarargInjectionLowering](../compiler/lowerings/functions.md#vararginjectionlowering) |
| `inline` functions, `reified`, `typeOf` | [inlining.md](../compiler/lowerings/inlining.md) |
| `tailrec` | [NativeTailrecLowering](../compiler/lowerings/control-flow.md#nativetailreclowering) |
| `external` functions implemented by the compiler or the runtime | [intrinsics](../compiler/codegen.md#intrinsics) · [calls in both directions](../runtime/README.md#calls-in-both-directions) |

## Lambdas and references

| Feature | Where it is explained |
|---|---|
| Lambdas, function references (`::foo`) | [NativeFunctionReferenceLowering](../compiler/lowerings/callable-references.md#nativefunctionreferencelowering) · [references that capture nothing](../compiler/lowerings/callable-references.md#staticcallablereferenceoptimization) |
| A lambda that changes a captured `var` | [NativeSharedVariablesPrimitiveBoxSpecializationLowering](../compiler/lowerings/classes-and-local-declarations.md#nativesharedvariablesprimitiveboxspecializationlowering) |
| Property references (`::prop`), the property a delegate receives | [PropertyReferenceLowering](../compiler/lowerings/callable-references.md#propertyreferencelowering) |
| `fun interface` (SAM conversion) | [NativeSingleAbstractMethodLowering](../compiler/lowerings/callable-references.md#nativesingleabstractmethodlowering) |
| `Foo::class`, `x::class` | [PostInlineLowering](../compiler/lowerings/inlining.md#postinlinelowering) |

## Types

| Feature | Where it is explained |
|---|---|
| Generics (erased) | [Autoboxing](../compiler/lowerings/boxing.md#autoboxing) · [casts caused by generics](../compiler/optimizations/type-checks.md#casts-caused-by-generics) · [a real case](../first-issue.md) |
| Primitives used as `Any`, `T` or nullable | [boxing.md](../compiler/lowerings/boxing.md) · [boxes in memory](../runtime/object-model.md#boxing) |
| `is`, `as`, `as?`, smart casts | [casts-and-types.md](../compiler/lowerings/casts-and-types.md) · [how the check runs](../compiler/optimizations/type-checks.md) |
| `==`, `===`, comparisons | [BuiltinOperatorLowering](../compiler/lowerings/casts-and-types.md#builtinoperatorlowering) |

## Data and control flow

| Feature | Where it is explained |
|---|---|
| Arrays | [an array in memory](../runtime/object-model.md#an-array) · [`for` over an array](../compiler/lowerings/control-flow.md#nativeforloopslowering) |
| String literals and other constants | [constants](../compiler/codegen.md#constants) · [objects that are constants](../runtime/object-model.md#objects-that-are-constants) |
| String templates, `+` on strings | [StringConcatenationTypeNarrowing](../compiler/lowerings/casts-and-types.md#stringconcatenationtypenarrowing); the shared lowerings are in [all-lowerings.md](../compiler/lowerings/all-lowerings.md) |
| `for` loops, ranges | [NativeForLoopsLowering](../compiler/lowerings/control-flow.md#nativeforloopslowering) |
| `try`/`finally` with `return` or `break` inside | [NativeFinallyBlocksLowering](../compiler/lowerings/control-flow.md#nativefinallyblockslowering) |
| `lateinit`, local delegated properties, `x in a..b`, `expect` declarations | Shared lowerings, one line each in [all-lowerings.md](../compiler/lowerings/all-lowerings.md) |
| `assert` | [NativeAssertionWrapperLowering](../compiler/lowerings/tests-and-assertions.md#nativeassertionwrapperlowering) |
| `@Test` functions | [TestProcessor](../compiler/lowerings/tests-and-assertions.md#testprocessor) · [tests.md](../build-and-tests/tests.md) |

## Memory and threads

| Feature | Where it is explained |
|---|---|
| Allocation, garbage collection | [gc-and-allocator.md](../runtime/gc-and-allocator.md) · [objects that stay on the stack](../compiler/optimizations/escape-analysis.md) |
| Local variables that hold references | [how the GC finds references](../runtime/README.md#how-the-gc-finds-references) |
| Weak references, `Cleaner` | [gc-and-allocator.md](../runtime/gc-and-allocator.md) |
| Threads, `Worker` | [threads and their two states](../runtime/README.md#threads-and-their-two-states) |

## Other languages

| Feature | Where it is explained |
|---|---|
| Calling C and Objective-C | [cinterop.md](../interop/cinterop.md) · [the lowerings](../compiler/lowerings/interop.md) |
| Being called from Objective-C, Swift and C | [export.md](../interop/export.md) |
| `expect`/`actual` over platform libraries | [the commonizer](../interop/cinterop.md#the-commonizer) |
