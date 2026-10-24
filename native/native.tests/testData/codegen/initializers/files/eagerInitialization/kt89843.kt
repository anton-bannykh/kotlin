/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

// KT-89843: eager thread-local initializers also run on the GC finalizer thread. If such an initializer calls GC.collect(),
// it must not wait for the finalizers there, as they would have to run on that very thread: the program used to hang.

// FILE: lib.kt
@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class, kotlin.native.runtime.NativeRuntimeApi::class, ExperimentalStdlibApi::class)

import kotlin.native.concurrent.ThreadLocal
import kotlin.native.ref.WeakReference
import kotlin.native.runtime.GC

// The weak reference makes the GC start the finalizer thread.
private fun weakRef() = WeakReference(Any())

@ThreadLocal
@EagerInitialization
val initResult = run {
    val w = weakRef()
    GC.collect()
    if (w.value == null) "OK" else "FAIL"
}

// FILE: main.kt
fun box(): String = initResult
