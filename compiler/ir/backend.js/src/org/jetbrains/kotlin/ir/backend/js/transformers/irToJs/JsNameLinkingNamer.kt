/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.transformers.irToJs

import org.jetbrains.kotlin.ir.backend.js.JsIrBackendContext
import org.jetbrains.kotlin.ir.backend.js.utils.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.util.isEffectivelyExternal
import org.jetbrains.kotlin.js.backend.ast.*

class JsNameLinkingNamer(private val context: JsIrBackendContext) : IrNamerBase() {

    val nameMap = mutableMapOf<IrDeclaration, JsName>()

    private fun IrDeclarationWithName.getName(prefix: String = ""): JsName {
        return nameMap.getOrPut(this) { JsName(sanitizeName(prefix + getJsNameOrKotlinName().asString()), true) }
    }

    val importedModules = mutableListOf<JsImportedModule>()
    val imports = mutableMapOf<IrDeclaration, JsExpression>()

    override fun getNameForStaticDeclaration(declaration: IrDeclarationWithName): JsName {

        if (declaration.isEffectivelyExternal()) {
            val jsModule: String? = declaration.getJsModule()
            val maybeParentFile: IrFile? = declaration.parent as? IrFile
            val fileJsModule: String? = maybeParentFile?.getJsModule()
            val jsQualifier: String? = maybeParentFile?.getJsQualifier()

            when {
                jsModule != null -> {
                    val name = declaration.getName()
                    importedModules += JsImportedModule(jsModule, name, name.makeRef())
                    return name
                }

                fileJsModule != null -> {
                    if (declaration !in nameMap) {
                        val moduleName = JsName("\$module\$$jsModule", true)
                        importedModules += JsImportedModule(fileJsModule, moduleName, null)
                        val qualifiedReference = if (jsQualifier == null) moduleName.makeRef() else JsNameRef(jsQualifier, moduleName.makeRef())
                        imports[declaration] = JsNameRef(declaration.getJsNameOrKotlinName().identifier, qualifiedReference)
                        return declaration.getName()
                    }
                }

                else -> {
                    var name = declaration.getJsNameOrKotlinName().identifier
                    if (jsQualifier != null)
                        name = "$jsQualifier.$name"

                    return JsName(name, false)
                }
            }
        }

        return declaration.getName()
    }

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
