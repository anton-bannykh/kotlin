/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.transformers.irToJs

import org.jetbrains.kotlin.ir.backend.js.JsIrBackendContext
import org.jetbrains.kotlin.ir.backend.js.utils.IrNamerBase
import org.jetbrains.kotlin.ir.backend.js.utils.getJsNameOrKotlinName
import org.jetbrains.kotlin.ir.backend.js.utils.jsFunctionSignature
import org.jetbrains.kotlin.ir.backend.js.utils.sanitizeName
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.js.backend.ast.JsName

class JsNameLinkingNamer(private val context: JsIrBackendContext) : IrNamerBase() {

    val nameMap = mutableMapOf<IrDeclaration, JsName>()

    private fun IrDeclarationWithName.getName(prefix: String = ""): JsName {
        return nameMap.getOrPut(this) { JsName(sanitizeName(prefix + getJsNameOrKotlinName().asString()), true) }
    }

    override fun getNameForStaticDeclaration(declaration: IrDeclarationWithName): JsName = declaration.getName()

    override fun getNameForMemberFunction(function: IrSimpleFunction): JsName {
        require(function.dispatchReceiverParameter != null)
        val signature = jsFunctionSignature(function, context)
        return signature.toJsName()
    }

    override fun getNameForMemberField(field: IrField): JsName {
        require(!field.isStatic)
        return field.getName("___") // TODO resolve name clashes between fields and exported properties
    }
}
