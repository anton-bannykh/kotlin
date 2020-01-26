/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js

import org.jetbrains.kotlin.backend.common.DefaultMapping
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrField

class JsMapping : DefaultMapping() {
    val singletonFieldDescriptors = newMapping<IrClass, IrField>()
    val outerThisFieldSymbols = newMapping<IrClass, IrField>()
    val innerClassConstructors = newMapping<IrConstructor, IrConstructor>()
}