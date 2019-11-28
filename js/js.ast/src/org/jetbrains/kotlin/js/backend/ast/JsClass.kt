// Copyright (c) 2011, the Dart project authors.  Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package org.jetbrains.kotlin.js.backend.ast

import org.jetbrains.kotlin.js.common.Symbol

class JsClass(
    private var name: JsName? = null,
    var baseClass: JsNameRef? = null,
    var constructor: JsFunction? = null,
    val members: MutableList<JsFunction> = mutableListOf()
) : JsLiteral(), HasName {

    override fun getName(): JsName? {
        return name
    }

    override fun getSymbol(): Symbol? {
        return name
    }

    override fun setName(name: JsName?) {
        this.name = name
    }

    override fun accept(v: JsVisitor) {
        v.visitClass(this)
    }

    override fun acceptChildren(visitor: JsVisitor) {
        visitor.accept(constructor)
    }

    override fun traverse(v: JsVisitorWithContext, ctx: JsContext<*>) {
        if (v.visit(this, ctx)) {
            constructor = v.accept(constructor)
            v.acceptList(members)
        }
        v.endVisit(this, ctx)
    }

    override fun deepCopy(): JsClass {
        val classCopy = JsClass(name, baseClass, constructor?.deepCopy(), members.mapTo(mutableListOf()) { it.deepCopy() })

        return classCopy.withMetadataFrom<JsClass>(this)
    }
}
