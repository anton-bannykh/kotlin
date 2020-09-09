/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir

import java.lang.StringBuilder

fun main() {

    val startOffset = property("startOffset", type(import("Int", "dsf")), isOverride = true)

    val t = renderFile("org.jetbrains.kotlin.ir.declarations.persistent") {
        indent {
            say {
                + startOffset + "\n"
                + startOffset + "\n"
            }()
        }
    }

    println(t)
}

private class Builder {
    var c = id

    operator fun E.unaryPlus(): Builder {
        c = c + this
        return this@Builder
    }

    operator fun String.unaryPlus(): Builder {
        c = c + this
        return this@Builder
    }

    infix operator fun plus(e: E): Builder {
        c = c + e
        return this
    }

    infix operator fun plus(s: String): Builder {
        c = c + s
        return this
    }
}

private fun say(fn: Builder.() -> Unit): E {
    val b = Builder()
    b.fn()
    return b.c
}

private fun import(name: String, pkg: String): E = { import("$pkg.$name").text(name) }

private interface R {
    fun text(t: String): R

    fun indent(fn: R.() -> Unit): R

    fun import(fqn: String): R
}

private typealias E = R.() -> R

private infix operator fun E.plus(e: E?): E = { this@plus(); e.safe()() }

private infix operator fun E.plus(e: String): E = this + e.e()

private val id: E = { this }

private fun String.e(): E = { text(this@e) }

private fun E?.safe(): E = this ?: id

private fun Array<out E>.join(prefix: String = "", separator: String = "", suffix: String = ""): E =
    join(prefix.e(), separator.e(), suffix.e())

private fun Array<out E>.join(prefix: E = id, separator: E = id, suffix: E = id): E {
    if (this.isEmpty()) return id
    return prefix + interleaveWith(separator) + suffix
}

private fun Array<out E>.interleaveWith(b: E): E {
    return {
        this@interleaveWith.forEachIndexed { i, e ->
            if (i != 0) b()
            e()
        }
        this
    }
}

private fun Boolean.ifTrue(s: String): E = if (this) s.e() else id

private fun type(name: E, vararg parameters: E, isNullable: Boolean = false): E =
    name + parameters.join("<", ", ", ">") + isNullable.ifTrue("?")

private fun param(name: String, type: E, defaultValue: E?): E = id + "$name: " + type + defaultValue

private fun E.prepend(s: String): E = id + s + this

private fun E.indent(): E = {
    val self = this@indent
    indent {
        self()
    }
}

private fun property(
    name: String,
    type: E,
    defaultValue: E? = null,
    isVar: Boolean = false,
    isOverride: Boolean = false,
    annotations: Array<E> = arrayOf(),
    getter: E? = null,
    setter: E? = null
): E = annotations.join(separator = "\n") + isOverride.ifTrue("override ") + (if (isVar) "var " else "val ") + param(name, type, defaultValue) +
        getter?.prepend("\n")?.indent() +
        setter?.prepend("\n")?.indent()

//private class MutableStateModel(
//    val name: String,
//    val properties: List<E>,
//    val parent: MutableStateModel? = null
//) {
//    val allProperties: List<E>
//        get() = (parent?.allProperties ?: emptyList()) + properties
//}
//
//private fun carrierInterface(model: MutableStateModel): E {
//    return with (model) {
//
//        id + "internal interface ${name}Carrier " + parent?.let { ": ${parent.name}Carrier ".e() } + "{\n"
//
//        id
//    }
//}
//
//private class CarrierInterface(
//    val model: MutableStateModel,
//) : Node {
//    override fun render(r: Renderer) {
//        with(model) {
//            r.text("internal interface ${name}Carrier ")
//            if (parent != null) {
//                r.text(": ${parent.name}Carrier ")
//            }
//            r.text("{\n")
//            r.indent {
//                properties.forEach {
//                    it.override(false).makeNoDefault().render(r)
//                    r.text("\n")
//                }
//
//                r.text("\n")
//
//                r.text("override fun clone(): ${name}Carrier {\n")
//                r.indent {
//                    r.text("return ${name}CarrierImpl(\n")
//                    r.indent {
//                        allProperties.forEach {
//                            r.text(it.name)
//                            r.text(",\n")
//                        }
//                    }
//                    r.text(")\n")
//                }
//                r.text("\n")
//            }
//            r.text("}\n")
//        }
//    }
//}
//
//private class CarrierImpl(
//    val model: MutableStateModel
//) : Node {
//    override fun render(r: Renderer) {
//        with(model) {
//            r.text("internal class ${name}CarrierImpl(\n")
//            r.indent {
//                r.indent {
//                    allProperties.forEach {
//                        it.override(true).makeNoDefault().render(r)
//                        r.text(",\n")
//                    }
//                }
//            }
//            r.text(") : ${name}Carrier\n")
//        }
//    }
//}
//
//private class PersistentClass(
//    val constructorParameters: List<ConstructorParameter>,
//    val inheritsFrom: List<Type>,
//    val initBlock: Expression?,
//    val properties: List<Property>,
//    val model: MutableStateModel
//): Node {
//    override fun render(r: Renderer) {
//        r.text("internal class PersistentIr${model.name}(\n")
//        r.indent {
//            constructorParameters.forEach {
//                it.render(r)
//                r.text(",\n")
//            }
//        }
//        r.text(") : ")
//        inheritsFrom.first().render(r)
//        r.indent {
//            inheritsFrom.drop(1).forEach {
//                r.text(",\n")
//                it.render(r)
//            }
//        }
//        r.text(" {\n")
//        r.indent {
//            if (initBlock != null) {
//                initBlock.render(r)
//            }
//            r.text("\n")
//
//            properties.forEach {
//                it.render(r)
//                r.text("\n\n")
//            }
//
//            with(model) {
//                allProperties.forEach {
////                    if (it.defaultValue == null && !it.type.isNullable) {
////                        it.makeNullable().render(r)
////                    } else {
////
////                    }
//                    it.render(r)
//                    r.text("\n\n")
//                }
//            }
//        }
//        r.text("}\n")
//    }
//}


private fun renderFile(pkg: String, fn: R.() -> R): String {
    val sb = StringBuilder()
    val imports: MutableSet<String> = mutableSetOf()

    val renderer = object : R {
        var currentIndent = ""

        var atLineStart = true

        override fun text(t: String): R {
            if (t.isEmpty()) return this

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

            return this
        }

        override fun indent(fn: R.() -> Unit): R {
            val oldIndent = currentIndent
            currentIndent = "$oldIndent    "
            fn()
            currentIndent = oldIndent

            return this
        }

        override fun import(fqn: String): R {
            imports += fqn

            return this
        }
    }

    renderer.fn()

    var result = """
            /*
            * Copyright 2010-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
            * that can be found in the license/LICENSE.txt file.
            */

            package $pkg

    """.trimIndent()

    result += imports.map { "import $it" }.sorted().joinToString(separator = "\n")
    result += "\n"
    result += sb.toString()

    return result
}