/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.transformers.irToJs

import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.ir.backend.js.CompilationOutputs
import org.jetbrains.kotlin.ir.backend.js.CompilerResult
import org.jetbrains.kotlin.ir.backend.js.JsIrBackendContext
import org.jetbrains.kotlin.ir.backend.js.eliminateDeadDeclarations
import org.jetbrains.kotlin.ir.backend.js.export.*
import org.jetbrains.kotlin.ir.backend.js.lower.StaticMembersLowering
import org.jetbrains.kotlin.ir.backend.js.utils.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.util.isEffectivelyExternal
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.js.backend.JsToStringGenerationVisitor
import org.jetbrains.kotlin.js.backend.NoOpSourceLocationConsumer
import org.jetbrains.kotlin.js.backend.ast.*
import org.jetbrains.kotlin.js.config.JSConfigurationKeys
import org.jetbrains.kotlin.js.config.SourceMapSourceEmbedding
import org.jetbrains.kotlin.js.sourceMap.SourceFilePathResolver
import org.jetbrains.kotlin.js.sourceMap.SourceMap3Builder
import org.jetbrains.kotlin.js.sourceMap.SourceMapBuilderConsumer
import org.jetbrains.kotlin.js.util.TextOutputImpl
import org.jetbrains.kotlin.utils.DFS
import java.io.File

class IrModuleToJsTransformer(
    private val backendContext: JsIrBackendContext,
    private val mainArguments: List<String>?,
    private val generateScriptModule: Boolean = false,
    var namer: NameTables = NameTables(emptyList(), context = backendContext),
    private val fullJs: Boolean = true,
    private val dceJs: Boolean = false,
    private val multiModule: Boolean = false,
    private val relativeRequirePath: Boolean = false,
    private val moduleToName: Map<IrModuleFragment, String> = emptyMap(),
    private val removeUnusedAssociatedObjects: Boolean = true,
) {
    private val generateRegionComments = backendContext.configuration.getBoolean(JSConfigurationKeys.GENERATE_REGION_COMMENTS)

    private val mainModuleName = backendContext.configuration[CommonConfigurationKeys.MODULE_NAME]!!
    private val moduleKind = backendContext.configuration[JSConfigurationKeys.MODULE_KIND]!!

    fun generateModule(modules: Iterable<IrModuleFragment>): CompilerResult {
        val exportModelGenerator = ExportModelGenerator(backendContext)

        val exportData = modules.associate { module ->
            module to module.files.associate { file ->
                file to exportModelGenerator.generateExport(file)
            }
        }

        val dts = wrapTypeScript(mainModuleName, moduleKind, exportData.values.flatMap { it.values.flatMap { it } }.toTypeScript(moduleKind))

        modules.forEach { module ->
            module.files.forEach { StaticMembersLowering(backendContext).lower(it) }
        }

        if (multiModule) {
            breakCrossModuleFieldAccess(backendContext, modules)
        }

        val jsCode = if (fullJs) generateWrappedModuleBody(mainModuleName, modules, generateProgramFragments(modules, exportData)) else null

        val dceJsCode = if (dceJs) {
            eliminateDeadDeclarations(modules, backendContext, removeUnusedAssociatedObjects)

            generateWrappedModuleBody(mainModuleName, modules, generateProgramFragments(modules, exportData))
        } else null

        return CompilerResult(jsCode, dceJsCode, dts)
    }

    private fun generateProgramFragments(
        modules: Iterable<IrModuleFragment>,
        exportData: Map<IrModuleFragment, Map<IrFile, List<ExportedDeclaration>>>,
    ): Map<IrFile, JsIrProgramFragment> {

        val fragments = mutableMapOf<IrFile, JsIrProgramFragment>()
        modules.forEach { m ->
            m.files.forEach { f ->
                val exports = exportData[m]!![f]!! // TODO
                fragments[f] = generateProgramFragment(f, exports)
            }
        }

        return fragments
    }

    private fun generateWrappedModuleBody(
        moduleName: String,
        modules: Iterable<IrModuleFragment>,
        fragments: Map<IrFile, JsIrProgramFragment>,
    ): CompilationOutputs {
        val (moduleBody, callToMain, exportStatements, importStatements, importedJsModules) =
            merge(modules.map { it.files.map { fragments[it]!! } })

        val internalModuleName = JsName("_", false)

        val program = JsProgram()
        if (generateScriptModule) {
            with(program.globalBlock) {
                statements.addWithComment("block: imports", importStatements)
                statements += moduleBody
                statements.addWithComment("block: exports", exportStatements)
            }
        } else {
            val rootFunction = JsFunction(program.rootScope, JsBlock(), "root function").apply {
                parameters += JsParameter(internalModuleName)
                parameters += (importedJsModules).map { JsParameter(it.internalName) }
                with(body) {
                    statements.addWithComment("block: imports", importStatements)
                    statements += moduleBody
                    statements.addWithComment("block: exports", exportStatements)
                    statements += callToMain
                    statements += JsReturn(internalModuleName.makeRef())
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

        program.resolveTemporaryNames()

        val jsCode = TextOutputImpl()

        val configuration = backendContext.configuration
        val sourceMapPrefix = configuration.get(JSConfigurationKeys.SOURCE_MAP_PREFIX, "")
        val sourceMapsEnabled = configuration.getBoolean(JSConfigurationKeys.SOURCE_MAP)

        val sourceMapBuilder = SourceMap3Builder(null, jsCode, sourceMapPrefix)
        val sourceMapBuilderConsumer =
            if (sourceMapsEnabled) {
                val sourceRoots = configuration.get(JSConfigurationKeys.SOURCE_MAP_SOURCE_ROOTS, emptyList<String>()).map(::File)
                val generateRelativePathsInSourceMap = sourceMapPrefix.isEmpty() && sourceRoots.isEmpty()
                val outputDir = if (generateRelativePathsInSourceMap) configuration.get(JSConfigurationKeys.OUTPUT_DIR) else null

                val pathResolver = SourceFilePathResolver(sourceRoots, outputDir)

                val sourceMapContentEmbedding =
                    configuration.get(JSConfigurationKeys.SOURCE_MAP_EMBED_SOURCES, SourceMapSourceEmbedding.INLINING)

                SourceMapBuilderConsumer(
                    File("."),
                    sourceMapBuilder,
                    pathResolver,
                    sourceMapContentEmbedding == SourceMapSourceEmbedding.ALWAYS,
                    sourceMapContentEmbedding != SourceMapSourceEmbedding.NEVER
                )
            } else {
                NoOpSourceLocationConsumer
            }

        program.accept(JsToStringGenerationVisitor(jsCode, sourceMapBuilderConsumer))

        return CompilationOutputs(
            jsCode.toString(),
            if (sourceMapsEnabled) sourceMapBuilder.build() else null
        )
    }

    private data class ModuleBody(
        val statements: List<JsStatement>,
        val mainFunction: JsStatement?,
        val exportStatements: List<JsStatement>,
        val importStatements: MutableList<JsStatement>,
        val importedJsModules: List<JsImportedModule>,
    )

    private val generateFilePaths = backendContext.configuration.getBoolean(JSConfigurationKeys.GENERATE_COMMENTS_WITH_FILE_PATH)
    private val pathPrefixMap = backendContext.configuration.getMap(JSConfigurationKeys.FILE_PATHS_PREFIX_MAP)

    private fun generateProgramFragment(file: IrFile, exports: List<ExportedDeclaration>): JsIrProgramFragment {
        val nameGenerator = JsNameLinkingNamer(backendContext)

        val staticContext = JsStaticContext(
            backendContext = backendContext,
            irNamer = nameGenerator,
            globalNameScope = namer.globalNames
        )

        val result = JsIrProgramFragment(file.name, file.fqName.asString())

        val internalModuleName = JsName("_", false)
        val globalNames = NameTable<String>(namer.globalNames)
        val exportStatements = ExportModelToJsStatements(internalModuleName, staticContext, { globalNames.declareFreshName(it, it) }).generateModuleExport(
            ExportedModule(mainModuleName, moduleKind, exports)
        )

        result.exports.statements += exportStatements

        if (exports.isNotEmpty()) {
            result.dts = exports.toTypeScript(moduleKind)
        }

        val statements = result.declarations.statements


        val fileStatements = file.accept(IrFileToJsTransformer(), staticContext).statements
        if (fileStatements.isNotEmpty()) {
            var startComment = ""

            if (generateRegionComments) {
                startComment = "region "
            }

            if (generateRegionComments || generateFilePaths) {
                val originalPath = file.path
                val path = pathPrefixMap.entries
                    .find { (k, _) -> originalPath.startsWith(k) }
                    ?.let { (k, v) -> v + originalPath.substring(k.length) }
                    ?: originalPath

                startComment += "file: $path"
            }

            if (startComment.isNotEmpty()) {
                statements.add(JsSingleLineComment(startComment))
            }

            statements.addAll(fileStatements)
            statements.endRegion()
        }

        result.classes += staticContext.classModels
        result.initializers.statements += staticContext.initializerBlock.statements

        if (mainArguments != null) {
            JsMainFunctionDetector.getMainFunctionOrNull(file)?.let {
                val jsName = staticContext.getNameForStaticFunction(it)
                result.mainFunction = JsInvocation(jsName.makeRef(), generateMainArguments(it, staticContext)).makeStmt()
            }
        }

        backendContext.testFunsPerFile[file]?.let {
            result.testFunInvocation = JsInvocation(staticContext.getNameForStaticFunction(it).makeRef()).makeStmt()
            result.suiteFn = staticContext.getNameForStaticFunction(backendContext.suiteFun!!.owner).makeRef()
        }


        result.nameBindings += nameGenerator.nameMap

        return result
    }

    private fun merge(fragments: List<List<JsIrProgramFragment>>): ModuleBody {

        fragments.flatMap { it }.linkJsNames()

        val statements = mutableListOf<JsStatement>().also {
            if (!generateScriptModule) it += JsStringLiteral("use strict").makeStmt()
        }

        val preDeclarationBlock = JsGlobalBlock()
        val postDeclarationBlock = JsGlobalBlock()

        statements.addWithComment("block: pre-declaration", preDeclarationBlock)

        val classModels = mutableMapOf<IrClassSymbol, JsIrClassModel>()
        val initializerBlock = JsGlobalBlock()
        fragments.forEach {
            it.forEach {
                statements += it.declarations.statements
                classModels += it.classes
                initializerBlock.statements += it.initializers.statements
            }
        }

        // sort member forwarding code
        processClassModels(classModels, preDeclarationBlock, postDeclarationBlock)

        statements.addWithComment("block: post-declaration", postDeclarationBlock.statements)
        statements.addWithComment("block: init", initializerBlock.statements)

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

            statements.startRegion("block: tests")
            statements += JsInvocation(testFun).makeStmt()
            statements.endRegion()
        }

        val callToMain = lastModuleFragments.sortedBy { it.packageFqn }.firstNotNullOfOrNull { it.mainFunction }

        val exportStatements = fragments.flatMap { it.flatMap { it.exports.statements } }

        return ModuleBody(statements, callToMain, exportStatements, mutableListOf(), emptyList())
    }

    private fun generateMainArguments(mainFunction: IrSimpleFunction, staticContext: JsStaticContext): List<JsExpression> {
        val mainArguments = this.mainArguments!!
        val mainArgumentsArray =
            if (mainFunction.valueParameters.isNotEmpty()) JsArrayLiteral(mainArguments.map { JsStringLiteral(it) }) else null

        val continuation = if (mainFunction.isSuspend) {
            backendContext.coroutineEmptyContinuation.owner
                .let { it.getter!! }
                .let { staticContext.getNameForStaticFunction(it) }
                .let { JsInvocation(it.makeRef()) }
        } else null

        return listOfNotNull(mainArgumentsArray, continuation)
    }

    private fun generateImportStatements(
        getNameForExternalDeclaration: (IrDeclarationWithName) -> JsName,
        declareFreshGlobal: (String) -> JsName
    ): Pair<MutableList<JsStatement>, List<JsImportedModule>> {
        val declarationLevelJsModules =
            backendContext.declarationLevelJsModules.map { externalDeclaration ->
                val jsModule = externalDeclaration.getJsModule()!!
                val name = getNameForExternalDeclaration(externalDeclaration)
                JsImportedModule(jsModule, name, name.makeRef())
            }

        val packageLevelJsModules = mutableListOf<JsImportedModule>()
        val importStatements = mutableListOf<JsStatement>()

        for (file in backendContext.packageLevelJsModules) {
            val jsModule = file.getJsModule()
            val jsQualifier = file.getJsQualifier()

            assert(jsModule != null || jsQualifier != null)

            val qualifiedReference: JsNameRef

            if (jsModule != null) {
                val internalName = declareFreshGlobal("\$module\$$jsModule")
                packageLevelJsModules += JsImportedModule(jsModule, internalName, null)

                qualifiedReference =
                    if (jsQualifier == null)
                        internalName.makeRef()
                    else
                        JsNameRef(jsQualifier, internalName.makeRef())
            } else {
                qualifiedReference = JsNameRef(jsQualifier!!)
            }

            file.declarations
                .asSequence()
                .filterIsInstance<IrDeclarationWithName>()
                .filter { !(it is IrClass && it.isInterface && it.isEffectivelyExternal()) }
                .forEach { declaration ->
                    val declName = getNameForExternalDeclaration(declaration)
                    importStatements.add(
                        JsVars(JsVars.JsVar(declName, JsNameRef(declaration.getJsNameOrKotlinName().identifier, qualifiedReference)))
                    )
                }
        }

        val importedJsModules = (declarationLevelJsModules + packageLevelJsModules).distinctBy { it.key }
        return Pair(importStatements, importedJsModules)
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
