/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.transformers.irToJs

import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.name
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.js.backend.ast.*

class JsIrProgramFragment(val fileName: String, val packageFqn: String) {
    val nameBindings = mutableMapOf<IrDeclaration, JsName>()
    val declarations = JsGlobalBlock()
    val exports = JsGlobalBlock()
    var dts: String? = null
    val classes = mutableMapOf<IrClassSymbol, JsIrClassModel>()
    val initializers = JsGlobalBlock()
    var mainFunction: JsStatement? = null
    var testFunInvocation: JsStatement? = null
    var suiteFn: JsNameRef? = null
}


fun Iterable<JsIrProgramFragment>.linkJsNames() {
    val nameMap = mutableMapOf<IrDeclaration, JsName>()

    this.forEach { f ->
        f.buildRenames(nameMap).run {
            rename(f.declarations)
            rename(f.exports)
            f.classes.values.forEach {
                rename(it.preDeclarationBlock)
                rename(it.postDeclarationBlock)
            }
            rename(f.initializers)
            f.mainFunction?.let { rename(it) }
            f.testFunInvocation?.let { rename(it) }
            f.suiteFn?.let { rename(it) }
        }
    }
}

private fun JsIrProgramFragment.buildRenames(nameMap: MutableMap<IrDeclaration, JsName>): Map<JsName, JsName> {
    val result = mutableMapOf<JsName, JsName>()

    this.nameBindings.entries.forEach { (declaration, name) ->
        val existingName = nameMap.getOrPut(declaration) { name }
        if (existingName !== name) {
            result[name] = existingName
        }
    }

    return result
}

private fun Map<JsName, JsName>.rename(name: JsName): JsName = getOrElse(name) { name }

private fun <T : JsNode> Map<JsName, JsName>.rename(rootNode: T): T {
    rootNode.accept(object : RecursiveJsVisitor() {
        override fun visitElement(node: JsNode) {
            super.visitElement(node)
            if (node is HasName) {
                node.name = node.name?.let { rename(it) }
            }
        }
    })
    return rootNode
}