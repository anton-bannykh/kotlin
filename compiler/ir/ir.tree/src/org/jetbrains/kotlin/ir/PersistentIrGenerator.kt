/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir

import org.jetbrains.kotlin.ir.symbols.IrAnonymousInitializerSymbol
import java.lang.StringBuilder

fun main() {

    val irPkg = "org.jetbrains.kotlin.ir"

    val startOffset = Property(
        "startOffset",
        Type(Symbol("Int")),
        isOverride = true
    )

    val endOffset = Property(
        "endOffset",
        Type(Symbol("Int")),
        isOverride = true
    )

    val origin = ConstructorParameter(
        "origin",
        Type(Symbol("IrDeclarationOrigin", "$irPkg.declarations"))
    )

    val symbol = Property(
        "symbol",
        Type(Symbol("IrAnonymousInitializerSymbol", "$irPkg.symbols")),
        isOverride = true
    )

    val isStatic = Property(
        "isStatic",
        Type(Symbol("Boolean")),
        isOverride = true,
        defaultValue = Expression("false")
    )

    val irAnonymousInitializerType = Type(Symbol("IrAnonymousInitializer", "$irPkg.declarations"))

    val anonymousInitializerCarrierType = Type(Symbol("AnonymousInitializerCarrier", "$irPkg.declarations.persistent.carriers"))

    val persistentIrDeclarationBaseType = Type(Symbol("PersistentIrDeclarationBase"), listOf(anonymousInitializerCarrierType))

    val initBlock = Expression("""
        init {
            symbol.bind(this)
        }
    """.trimIndent())

    val lastModified = Property(
        "lastModified",
        Type(Symbol("Int")),
        isVar = true,
        isOverride = true,
        defaultValue = Expression("stageController.currentStage")
    )

    val loweredUpTo = Property(
        "loweredUpTo",
        Type(Symbol("Int")),
        isVar = true,
        isOverride = true,
        defaultValue = Expression("stageController.currentStage")
    )

    val values = Property(
        "lastModified",
        Type(Symbol("Array"), listOf(Type(Symbol("Carrier", "$irPkg.declarations.persistent.carriers"))), isNullable = true),
        isVar = true,
        isOverride = true,
        defaultValue = Expression("null")
    )

    val createdOn = Property(
        "createdOn",
        Type(Symbol("Int")),
        isVar = true,
        isOverride = true,
        defaultValue = Expression("stageController.currentStage")
    )

    val parentField = Property(
        "parentField",
        Type(Symbol("IrDeclarationParent", "$irPkg.declarations"), isNullable = true),
        isVar = true,
        isOverride = true,
        defaultValue = Expression("null")
    )

    val originField = Property(
        "originField",
        Type(Symbol("IrDeclarationOrigin", "$irPkg.declarations")),
        isVar = true,
        isOverride = true,
        defaultValue = Expression("origin")
    )

    val removedOn = Property(
        "removedOn",
        Type(Symbol("Int")),
        isVar = true,
        isOverride = true,
        defaultValue = Expression("Int.MAX_VALUE")
    )

    val annotationsField = Property(
        "annotationsField",
        Type(Symbol("List"), listOf(Type(Symbol("IrConstructorCall", "$irPkg.expressions")))),
        isVar = true,
        isOverride = true,
        defaultValue = Expression("emptyList()")
    )

    val descriptor = Property(
        "descriptor",
        Type(Symbol("ClassDescriptor", "org.jetbrains.kotlin.descriptors")),
        annotations = listOf(Expression("@ObsoleteDescriptorBasedAPI", listOf(Symbol("ObsoleteDescriptorBasedAPI", "org.jetbrains.kotlin.ir")))),
        getter = Text("get() = symbol.descriptor")
    )

    val body = Property(
        "bodyField",
        Type(Symbol("IrBlockBody", "$irPkg.expressions"), isNullable = true),
        defaultValue = Expression("null"),
        isVar = true
    )

    val carrierModel = MutableStateModel("", listOf(lastModified))

    val declarationModel = MutableStateModel("Declaration", listOf(parentField, originField, annotationsField), carrierModel)

    val anonymousInitializerModel = MutableStateModel("AnonymousInitializer", listOf(body), declarationModel)


    val anonymousInitializerCarrierInterface = CarrierInterface(anonymousInitializerModel)

    val anonymousInitializerCarrierImpl = CarrierImpl(anonymousInitializerModel)

    val anonymousInitializerClass = PersistentClass(
        constructorParameters = listOf(
            startOffset,
            endOffset,
            origin,
            symbol,
            isStatic
        ),
        inheritsFrom = listOf(
            irAnonymousInitializerType,
            persistentIrDeclarationBaseType,
            anonymousInitializerCarrierType
        ),
        initBlock = initBlock,
        properties = listOf(
            lastModified,
            loweredUpTo,
            values,
            createdOn,
            parentField,
            originField,
            removedOn,
            annotationsField,
            descriptor
        ),
        model = anonymousInitializerModel
    )

    RendererImpl().print(FileNode("org.jetbrains.kotlin.ir.declarations.persistent", listOf(anonymousInitializerClass)))
//    RendererImpl().print(FileNode("org.jetbrains.kotlin.ir.declarations.persistent.carriers", listOf(anonymousInitializerCarrierInterface, anonymousInitializerCarrierImpl)))
}

private data class Symbol(val name: String, val pkg: String? = null) {
    val fqName get() = if (pkg != null) "${pkg}.$name" else name
}

private interface Renderer {
    fun text(t: String)

    fun indent(fn: Renderer.() -> Unit)

    fun import(s: Symbol)
}

private class RendererImpl : Renderer {
    val imports: MutableSet<Symbol> = mutableSetOf()

    val sb = StringBuilder()

    var currentIndent = ""

    var atLineStart = true

    override fun text(t: String) {
        if (t.isEmpty()) return

        if (atLineStart) {
            sb.append(currentIndent)
            atLineStart = false
        }

        val cr = t.indexOf('\n')
        if (cr >= 0) {
            sb.append(t.substring(0, cr + 1))
            atLineStart = true
            text(t.substring(cr + 1))
        } else {
            sb.append(t)
        }
    }

    override fun indent(fn: Renderer.() -> Unit) {
        val oldIndent = currentIndent
        currentIndent = "$oldIndent    "
        fn()
        currentIndent = oldIndent
    }

    override fun import(s: Symbol) {
        if (s.pkg != null) imports += s
    }

    fun print(n: Node) {
        n.render(this)
        val imports = imports.map { "import ${it.fqName}" }.sorted().joinToString(separator = "\n")

        println(imports)
        println()
        println(sb.toString())
    }
}

private interface Node {
    fun render(r: Renderer)
}

private open class Text(val t: String) : Node {
    override fun render(r: Renderer) {
        r.text(t)
    }
}

private class Type(
    val name: Symbol,
    val parameters: List<Type> = emptyList(),
    val isNullable: Boolean = false
) : Node {
    override fun render(r: Renderer) {
        r.import(name)
        r.text(name.name)
        var delim = "<"
        for (p in parameters) {
            r.text(delim)
            delim = ", "
            p.render(r)
        }
        if (!parameters.isEmpty()) r.text(">")
        if (isNullable) r.text("?")
    }

    fun makeNullable() = Type(name, parameters, true)
}

private class Expression(t: String, val imports: List<Symbol> = emptyList()) : Text(t) {
    override fun render(r: Renderer) {
        imports.forEach { r.import(it) }
        super.render(r)
    }
}

private open class ConstructorParameter(
    val name: String,
    val type: Type,
    val defaultValue: Expression? = null
) : Node {
    override fun render(r: Renderer) {
        r.text("$name: ")
        type.render(r)
        if (defaultValue != null) {
            r.text(" = ")
            defaultValue.render(r)
        }
    }
}

private class Property(
    name: String,
    type: Type,
    defaultValue: Expression? = null,
    val isVar: Boolean = false,
    val isOverride: Boolean = false,
    val annotations: List<Node> = emptyList(),
    val getter: Node? = null,
    val setter: Node? = null,
) : ConstructorParameter(name, type, defaultValue) {
    override fun render(r: Renderer) {
        if (!annotations.isEmpty()) {
            annotations.forEach {
                it.render(r)
                r.text("\n")
            }
        }
        if (isOverride) r.text("override ")
        r.text(if (isVar) "var " else "val ")
        super.render(r)
        if (getter != null) {
            r.text("\n")
            r.indent {
                getter.render(r)
            }
        }
        if (setter != null) {
            r.text("\n")
            r.indent {
                setter.render(r)
            }
        }
    }

    fun makeNullable() = Property(name, type.makeNullable(), defaultValue, isVar, isOverride, annotations)

    fun makeNoDefault() = Property(name, type, null, isVar, isOverride, annotations)

    fun override(v: Boolean) = Property(name, type, defaultValue, isVar, v, annotations)

    fun makeVar() = Property(name, type, defaultValue, true, isOverride, annotations)
}

private class MutableStateModel(
    val name: String,
    val properties: List<Property>,
    val parent: MutableStateModel? = null
) {
    fun extendWith(otherName: String, additionalProperties: List<Property>): MutableStateModel {
        return MutableStateModel(otherName, additionalProperties, this)
    }

    val allProperties: List<Property>
        get() = (parent?.allProperties ?: emptyList()) + properties
}

private class CarrierInterface(
    val model: MutableStateModel,
) : Node {
    override fun render(r: Renderer) {
        with(model) {
            r.text("internal interface ${name}Carrier ")
            if (parent != null) {
                r.text(": ${parent.name}Carrier ")
            }
            r.text("{\n")
            r.indent {
                properties.forEach {
                    it.override(false).makeNoDefault().render(r)
                    r.text("\n")
                }

                r.text("\n")

                r.text("override fun clone(): ${name}Carrier {\n")
                r.indent {
                    r.text("return ${name}CarrierImpl(\n")
                    r.indent {
                        allProperties.forEach {
                            r.text(it.name)
                            r.text(",\n")
                        }
                    }
                    r.text(")\n")
                }
                r.text("\n")
            }
            r.text("}\n")
        }
    }
}

private class CarrierImpl(
    val model: MutableStateModel
) : Node {
    override fun render(r: Renderer) {
        with(model) {
            r.text("internal class ${name}CarrierImpl(\n")
            r.indent {
                r.indent {
                    allProperties.forEach {
                        it.override(true).makeNoDefault().render(r)
                        r.text(",\n")
                    }
                }
            }
            r.text(") : ${name}Carrier\n")
        }
    }
}

private class PersistentClass(
    val constructorParameters: List<ConstructorParameter>,
    val inheritsFrom: List<Type>,
    val initBlock: Expression?,
    val properties: List<Property>,
    val model: MutableStateModel
): Node {
    override fun render(r: Renderer) {
        r.text("internal class PersistentIr${model.name}(\n")
        r.indent {
            constructorParameters.forEach {
                it.render(r)
                r.text(",\n")
            }
        }
        r.text(") : ")
        inheritsFrom.first().render(r)
        r.indent {
            inheritsFrom.drop(1).forEach {
                r.text(",\n")
                it.render(r)
            }
        }
        r.text(" {\n")
        r.indent {
            if (initBlock != null) {
                initBlock.render(r)
            }
            r.text("\n")

            properties.forEach {
                it.render(r)
                r.text("\n\n")
            }

            with(model) {
                allProperties.forEach {
//                    if (it.defaultValue == null && !it.type.isNullable) {
//                        it.makeNullable().render(r)
//                    } else {
//
//                    }
                    it.render(r)
                    r.text("\n\n")
                }
            }
        }
        r.text("}\n")
    }
}

private class FileNode(
    val pkg: String,
    val nodes: List<Node>
) : Node {
    override fun render(r: Renderer) {
        r.text("""
                  /*
                   * Copyright 2010-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
                   * that can be found in the license/LICENSE.txt file.
                   */
               """.trimIndent()
        )

        r.text("\n")
        r.text("package $pkg\n")

        nodes.forEach {
            r.text("\n")
            it.render(r)
        }
    }
}