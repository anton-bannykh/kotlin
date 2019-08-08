/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.declarations.impl.carriers

import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody
import kotlin.collections.ArrayList

interface CarrierBase<in T : CarrierBase<T>> {
    var parentField: IrDeclarationParent?
    val annotationsField: MutableList<IrExpressionBody>

    fun eq(other: T): Boolean

    fun clone(): CarrierBase<T>
}

fun <T> List<T>.eq(other: List<T>): Boolean {
    if (size != other.size) return false
    for (i in 0 until size) {
        if (this[i] !== other[i]) return false
    }
    return true
}

fun <T> MutableList<T>.clone(): MutableList<T> {
    return ArrayList(this)
}