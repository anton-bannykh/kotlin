// EXPECTED_REACHABLE_NODES: 1334
// !LANGUAGE: +NewInference +FunctionalInterfaceConversion +SamConversionPerArgument +SamConversionForKotlinFunctions
// IGNORE_BACKEND_FIR: JVM_IR
// IGNORE_BACKEND: JS_IR
// WITH_COROUTINES
// WITH_RUNTIME

import helpers.*
import kotlin.coroutines.startCoroutine

fun interface SuspendRunnable {
    suspend fun invoke()
}

fun run(r: SuspendRunnable) {
    r::invoke.startCoroutine(EmptyContinuation)
}

var result = "initial"

suspend fun bar() {
    result = "OK"
}

fun box(): String {
    run(::bar)
    return result
}
