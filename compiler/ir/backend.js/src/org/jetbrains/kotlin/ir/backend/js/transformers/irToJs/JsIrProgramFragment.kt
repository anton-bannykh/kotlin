/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.transformers.irToJs

import org.jetbrains.kotlin.ir.backend.js.utils.emptyScope
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.js.backend.ast.*
import org.jetbrains.kotlin.serialization.js.ModuleKind
import org.jetbrains.kotlin.utils.DFS

class JsIrProgramFragment(val fileName: String, val packageFqn: String) {
    val nameBindings = mutableMapOf<IrDeclaration, JsName>()
    val declarations = JsGlobalBlock()
    val exports = JsGlobalBlock()
    val importedModules = mutableListOf<JsImportedModule>()
    val imports = mutableMapOf<IrDeclaration, JsExpression>()
    var dts: String? = null
    val classes = mutableMapOf<IrClassSymbol, JsIrClassModel>()
    val initializers = JsGlobalBlock()
    var mainFunction: JsStatement? = null
    var testFunInvocation: JsStatement? = null
    var suiteFn: JsNameRef? = null
}


class Merger(
    private val moduleName: String,
    private val moduleKind: ModuleKind,
    private val fragments: List<List<JsIrProgramFragment>>,
    private val generateScriptModule: Boolean,
    private val generateRegionComments: Boolean,
) {

    private val importStatements = mutableMapOf<IrDeclaration, JsStatement>()
    private val importedModulesMap = mutableMapOf<JsImportedModuleKey, JsImportedModule>()

    private fun linkJsNames() {
        val nameMap = mutableMapOf<IrDeclaration, JsName>()

        fragments.flatMap { it }.forEach { f ->
            f.buildRenames(nameMap).run {
                rename(f.declarations)
                rename(f.exports)

                f.imports.entries.forEach { (declaration, importExpression) ->
                    val importName = nameMap[declaration]!!
                    importStatements.putIfAbsent(declaration, JsVars(JsVars.JsVar(importName, rename(importExpression))))
                }

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

        this.importedModules.forEach { module ->
            val existingModule = importedModulesMap.getOrPut(module.key) { module }
            if (existingModule !== module) {
                result[module.internalName] = existingModule.internalName
            }
        }

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

    fun merge(): JsProgram {
        linkJsNames()

        val moduleBody = mutableListOf<JsStatement>().also {
            if (!generateScriptModule) it += JsStringLiteral("use strict").makeStmt()
        }

        val preDeclarationBlock = JsGlobalBlock()
        val postDeclarationBlock = JsGlobalBlock()

        moduleBody.addWithComment("block: pre-declaration", preDeclarationBlock)

        val classModels = mutableMapOf<IrClassSymbol, JsIrClassModel>()
        val initializerBlock = JsGlobalBlock()
        fragments.forEach {
            it.forEach {
                moduleBody += it.declarations.statements
                classModels += it.classes
                initializerBlock.statements += it.initializers.statements
            }
        }

        // sort member forwarding code
        processClassModels(classModels, preDeclarationBlock, postDeclarationBlock)

        moduleBody.addWithComment("block: post-declaration", postDeclarationBlock.statements)
        moduleBody.addWithComment("block: init", initializerBlock.statements)

        val lastModuleFragments = fragments.last()

        // Merge test function invocations
        if (lastModuleFragments.any { it.testFunInvocation != null }) {
            val testFunBody = JsBlock()
            val testFun = JsFunction(emptyScope, testFunBody, "root test fun")
            val suiteFunRef = lastModuleFragments.firstNotNullOf { it.suiteFn }

            val tests = lastModuleFragments.filter { it.testFunInvocation != null }
                .groupBy({ it.packageFqn }) { it.testFunInvocation } // String -> [IrSimpleFunction]

            for ((pkg, testCalls) in tests) {
                val pkgTestFun = JsFunction(emptyScope, JsBlock(), "test fun for $pkg")
                pkgTestFun.body.statements += testCalls
                testFun.body.statements += JsInvocation(suiteFunRef, JsStringLiteral(pkg), JsBooleanLiteral(false), pkgTestFun).makeStmt()
            }

            moduleBody.startRegion("block: tests")
            moduleBody += JsInvocation(testFun).makeStmt()
            moduleBody.endRegion()
        }

        val callToMain = lastModuleFragments.sortedBy { it.packageFqn }.firstNotNullOfOrNull { it.mainFunction }

        val exportStatements = fragments.flatMap { it.flatMap { it.exports.statements } }

        val importedJsModules = this.importedModulesMap.values.toList()
        val importStatements = this.importStatements.values.toList()

        val internalModuleName = JsName("_", false)

        val program = JsProgram()

        if (generateScriptModule) {
            with(program.globalBlock) {
                this.statements.addWithComment("block: imports", importStatements)
                this.statements += moduleBody
                this.statements.addWithComment("block: exports", exportStatements)
            }
        } else {
            val rootFunction = JsFunction(program.rootScope, JsBlock(), "root function").apply {
                parameters += JsParameter(internalModuleName)
                parameters += (importedJsModules).map { JsParameter(it.internalName) }
                with(body) {
                    this.statements.addWithComment("block: imports", importStatements)
                    this.statements += moduleBody
                    this.statements.addWithComment("block: exports", exportStatements)
                    callToMain?.let { this.statements += it }
                    this.statements += JsReturn(internalModuleName.makeRef())
                }
            }

            program.globalBlock.statements += ModuleWrapperTranslation.wrap(
                moduleName,
                rootFunction,
                importedJsModules,
                program,
                kind = moduleKind
            )
        }

        return program
    }


    private fun processClassModels(
        classModelMap: Map<IrClassSymbol, JsIrClassModel>,
        preDeclarationBlock: JsBlock,
        postDeclarationBlock: JsBlock
    ) {
        val declarationHandler = object : DFS.AbstractNodeHandler<IrClassSymbol, Unit>() {
            override fun result() {}
            override fun afterChildren(current: IrClassSymbol) {
                classModelMap[current]?.let {
                    preDeclarationBlock.statements += it.preDeclarationBlock.statements
                    postDeclarationBlock.statements += it.postDeclarationBlock.statements
                }
            }
        }

        DFS.dfs(
            classModelMap.keys,
            { klass -> classModelMap[klass]?.superClasses ?: emptyList() },
            declarationHandler
        )
    }

    private fun MutableList<JsStatement>.startRegion(description: String = "") {
        if (generateRegionComments) {
            this += JsSingleLineComment("region $description")
        }
    }

    private fun MutableList<JsStatement>.endRegion() {
        if (generateRegionComments) {
            this += JsSingleLineComment("endregion")
        }
    }

    private fun MutableList<JsStatement>.addWithComment(regionDescription: String = "", block: JsBlock) {
        startRegion(regionDescription)
        this += block
        endRegion()
    }

    private fun MutableList<JsStatement>.addWithComment(regionDescription: String = "", statements: List<JsStatement>) {
        if (statements.isEmpty()) return

        startRegion(regionDescription)
        this += statements
        endRegion()
    }
}