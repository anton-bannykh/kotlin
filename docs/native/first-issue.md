[Kotlin/Native guide](README.md) › first-issue

# Your first issue: a worked example
*Skip this page if you have already traced a wrong program to one compiler phase by comparing IR dumps of two builds.*

A report says: "my program throws `ClassCastException` in a debug build and prints garbage in a release build". This page
follows it from the report to the responsible line of the compiler. Every command and output is real (Linux x64, compiler
from `kotlin-native/dist`, output trimmed); the flags are explained in the [cheat sheet](reference/cheat-sheet.md).

## 1. Reduce to one small file and reproduce with `konanc`

Leave Gradle out. Cut the program down until nothing more can be removed, here to `cast.kt`:

```kotlin
class Box<T>(val value: T)

@Suppress("UNCHECKED_CAST")
fun <T> unsafe(x: Any): Box<T> = Box(x) as Box<T>   // unchecked: T is erased, nothing can be tested here

fun main() {
    val b = unsafe<String>(42)
    val s = b.value                                 // line 8: an Int arrives where a String is expected
    println(s.length)
}
```
```console
$ konanc cast.kt -o debug && ./debug.kexe
Uncaught Kotlin exception: kotlin.ClassCastException: class kotlin.Int cannot be cast to class kotlin.String
    at 1   debug.kexe                          0x24280b           kfun:#main(){} + 635
$ konanc cast.kt -o opt -opt && ./opt.kexe
82
```

The debug build is right: Kotlin promises an exception at the first use as `String`. The `-opt` build reads the `Int` box
as if it were a `String`. With `-g -Xbinary=sourceInfoType=libbacktrace` the debug trace names the place: `cast.kt:8:15`.

## 2. Decide which part is responsible

"Only with `-opt`" suggests an optimization ([debugging.md](reference/debugging.md)). Test that guess first, because it is cheap:
disable the whole-program optimizations and the LLVM optimization phases.

```console
$ konanc cast.kt -o opt2 -opt \
    -Xdisable-phases=Devirtualization,DCEPhase,EscapeAnalysis,ModuleBitcodeOptimization,LTOBitcodeOptimization && ./opt2.kexe
82
```

Still wrong, so no optimization removes the check. Next question: is the check there when the lowerings are done? Dump the
IR after a handful of phases from the second half of the [lowering list](compiler/lowerings/README.md) in both modes, save
the LLVM IR too, and let `diff` find the first phase after which the two builds differ in `cast.kt`.

```bash
P=StaticInitializersLowering,TypeOperatorLowering,BridgesBuilding,GenericCallsReturnTypeEraser,Autoboxing
D=-Dorg.jetbrains.kotlin.compiler.ir.dump.strategy=KotlinLike; mkdir ll-debug ll-opt
konanc cast.kt -o debug $D -Xphases-to-dump-after=$P -Xdump-directory=ir-debug \
    -Xsave-llvm-ir-after=Codegen -Xsave-llvm-ir-directory=ll-debug
konanc cast.kt -o opt -opt $D -Xphases-to-dump-after=$P -Xdump-directory=ir-opt \
    -Xsave-llvm-ir-after=Codegen -Xsave-llvm-ir-directory=ll-opt
for p in ${P//,/ }; do
    diff -q $(grep -l 'FILE: cast.kt' ir-debug/*_AFTER.$p.kt.ir) $(grep -l 'FILE: cast.kt' ir-opt/*_AFTER.$p.kt.ir) \
        > /dev/null && echo "$p: same" || echo "$p: DIFFERENT"
done
```
```
StaticInitializersLowering: same
TypeOperatorLowering: same
BridgesBuilding: same
GenericCallsReturnTypeEraser: same
Autoboxing: DIFFERENT
```

The two builds part at `Autoboxing`. The LLVM IR only repeats it: in `ll-debug/out.Codegen.ll`, `kfun:#main(){}` calls
`kfun:kotlin.native.internal#downcast(...)` with `@"kclass:kotlin.String"`; in `ll-opt/` it has no such call. Code
generation and the runtime do what the IR says, so the suspect is one lowering. Had the dumps been equal to the end, the
next suspects would be the [optimizations](compiler/optimizations/README.md), then [codegen](compiler/codegen.md).

## 3. Read the before and after of the suspect phase

`-Xdump-fqname` limits a dump to one declaration, which makes it readable:
```console
$ konanc cast.kt -o debug1 $D -Xphases-to-dump=Autoboxing -Xdump-fqname=main -Xdump-directory=one-debug
$ konanc cast.kt -o opt1 -opt $D -Xphases-to-dump=Autoboxing -Xdump-fqname=main -Xdump-directory=one-opt
$ cat $(grep -l 'fun main' one-debug/*) $(grep -l 'fun main' one-opt/*AFTER*)
```
```kotlin
// --- IR for main before Autoboxing            (the same in both modes)
  val s: String = b.<get-value>() /*as String */
// --- IR for main after Autoboxing             (debug)
  val s: String = b.<get-value>() /*as Any? */ as String? /*as String */
// --- IR for main after Autoboxing             (-opt)
  val s: String = b.<get-value>() /*as String */
```

A cast in `/* */` is an implicit cast: a note for the compiler, no code. In debug, `Autoboxing` puts a real `as String?` in
front of it; with `-opt` it does not. That is the whole difference between the two programs.

## 4. Find the code

A phase name is a class name, so the suspect is `lower/Autoboxing.kt` in the backend ([code-map.md](reference/code-map.md) says where
lowerings, optimizations, code generation and the runtime live). Search it for implicit casts and follow the switch:
```console
$ cd kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan
$ grep -n 'insertSafeCasts' lower/Autoboxing.kt
59:    private val insertSafeCasts = context.config.genericSafeCasts
70:        insertSafeCasts && operator == IrTypeOperator.IMPLICIT_CAST -> {
$ grep -rn 'defaultGenericSafeCasts =' .
NativeSecondStageCompilationConfig.kt:333:    private val defaultGenericSafeCasts = !optimizationsEnabled // For now ...
```

The comment on line 333 ends "disabled in -opt due to performance penalty". So this is not a bug in a phase but a decision:
checking implicit generic casts costs time, and `-opt` turns it off. `genericSafeCasts` is a [binary option][binopts], which
gives a way to confirm the diagnosis from outside, in both directions:

```console
$ konanc cast.kt -o opt3 -opt -Xbinary=genericSafeCasts=true && ./opt3.kexe
Uncaught Kotlin exception: kotlin.ClassCastException: class kotlin.Int cannot be cast to class kotlin.String
$ konanc cast.kt -o debug3 -Xbinary=genericSafeCasts=false && ./debug3.kexe
82
```

Many first issues end like this: the answer to the reporter is the option and the reason, and no code changes. Changing
the default would be an edit of line 333 and a product decision. More: [boxing.md](compiler/lowerings/boxing.md#autoboxing).

## 5. Find or add a test, and run it in the mode that shows the problem

Look for existing tests first: `grep -rl genericSafeCasts native/native.tests/testData` finds eight, such as
`codegen/fileCheck/generic_safe_casts.kt`. When there is none, turn the reduced file into a box test
([tests.md](build-and-tests/tests.md)). With a `main` that prints `box()`, this one prints `OK` in debug and with
`-opt -Xbinary=genericSafeCasts=true`, and `FAIL: 82` with plain `-opt`:
```kotlin
// FREE_COMPILER_ARGS: -Xbinary=genericSafeCasts=true
// ... Box and unsafe as in cast.kt ...
fun box(): String = try {
    "FAIL: ${unsafe<String>(42).value.length}"
} catch (e: ClassCastException) {
    "OK"
}
```

The default test mode is debug, where this problem cannot show, so run the test in the mode from step 1. Gradle could not
be run where this page was written: this is the only command here that is not verified.
```bash
./gradlew :native:native.tests:test -Pkn.optimizationMode=OPT \
    --tests "org.jetbrains.kotlin.konan.test.blackbox.FirNativeCodegenLocalTestGenerated\$FileCheck*"
```

## 6. What goes into the commit

- The fix and its test in one commit; a refactoring you needed on the way in a separate commit.
- A subject with the area tag, imperative, at most 72 columns (`[K/N] ...`); a body that says what was wrong, why, and
  how the change fixes it: the result of steps 2 to 4 in a few sentences.
- The issue as the last line: `^KT-XXXXX Fixed`. All rules are in [commit-guidelines.md](../../.ai/commit-guidelines.md).

[binopts]: https://kotlinlang.org/docs/native-binary-options.html
