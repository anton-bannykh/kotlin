/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.backend.common

import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrDeclarationBase
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.visitors.IrElementVisitor
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid

interface FileLoweringPass {
    fun lower(irFile: IrFile)
}

interface ClassLoweringPass : FileLoweringPass {
    fun lower(irClass: IrClass)

    override fun lower(irFile: IrFile) = runOnFilePostfix(irFile)
}

interface DeclarationContainerLoweringPass : FileLoweringPass {
    fun lower(irDeclarationContainer: IrDeclarationContainer)

    override fun lower(irFile: IrFile) = runOnFilePostfix(irFile)
}

interface FunctionLoweringPass : FileLoweringPass {
    fun lower(irFunction: IrFunction)

    override fun lower(irFile: IrFile) = runOnFilePostfix(irFile)
}

interface BodyLoweringPass : FileLoweringPass {
    fun lower(irBody: IrBody, container: IrDeclaration)

    override fun lower(irFile: IrFile) = runOnFilePostfix(irFile)
}

interface NullableBodyLoweringPass {
    fun lower(irBody: IrBody?, container: IrDeclaration)
}

fun FileLoweringPass.lower(moduleFragment: IrModuleFragment) = moduleFragment.files.forEach { lower(it) }

fun ClassLoweringPass.runOnFilePostfix(irFile: IrFile) {
    irFile.acceptVoid(object : IrElementVisitorVoid {
        override fun visitElement(element: IrElement) {
            element.acceptChildrenVoid(this)
        }

        override fun visitClass(declaration: IrClass) {
            declaration.acceptChildrenVoid(this)
            lower(declaration)
        }
    })
}

fun DeclarationContainerLoweringPass.asClassLoweringPass() = object : ClassLoweringPass {
    override fun lower(irClass: IrClass) {
        this@asClassLoweringPass.lower(irClass)
    }
}

fun DeclarationContainerLoweringPass.runOnFilePostfix(irFile: IrFile) {
    this.asClassLoweringPass().runOnFilePostfix(irFile)
    this.lower(irFile as IrDeclarationContainer)
}

fun BodyLoweringPass.runOnFilePostfix(irFile: IrFile) {
    irFile.accept(object : IrElementVisitor<Unit, IrDeclaration?> {
        override fun visitElement(element: IrElement, data: IrDeclaration?) {
            element.acceptChildren(this, data)
        }

        override fun visitDeclaration(declaration: IrDeclaration, data: IrDeclaration?) {
            declaration.acceptChildren(this, declaration)
        }

        override fun visitBody(body: IrBody, data: IrDeclaration?) {
            body.acceptChildren(this, data)
            lower(body, data!!)
        }
    }, null)
}

fun FunctionLoweringPass.runOnFilePostfix(irFile: IrFile) {
    irFile.acceptVoid(object : IrElementVisitorVoid {
        override fun visitElement(element: IrElement) {
            element.acceptChildrenVoid(this)
        }

        override fun visitFunction(declaration: IrFunction) {
            declaration.acceptChildrenVoid(this)
            lower(declaration)
        }
    })
}

interface DeclarationTransformer {
    fun transformFlat(declaration: IrDeclaration): List<IrDeclaration>?
}

fun DeclarationTransformer.toFileLoweringPass(): FileLoweringPass {
    return object : FileLoweringPass {
        override fun lower(irFile: IrFile) {
            irFile.declarations.transformFlat(this@toFileLoweringPass::transformFlat)
        }
    }
}

fun DeclarationTransformer.toDeclarationContainerLoweringPass(): DeclarationContainerLoweringPass {
    return object : DeclarationContainerLoweringPass {
        override fun lower(irDeclarationContainer: IrDeclarationContainer) {
            irDeclarationContainer.declarations.transformFlat(this@toDeclarationContainerLoweringPass::transformFlat)
        }
    }
}

inline fun IrDeclaration.advance() {
    (this as? IrDeclarationBase<*>)?.let {
        it.loweredUpTo = stageController.currentStage
    }
}

fun DeclarationTransformer.runPostfix(): DeclarationTransformer {
    return object : DeclarationTransformer {
        override fun transformFlat(declaration: IrDeclaration): List<IrDeclaration>? {
            declaration.acceptVoid(object : IrElementVisitorVoid {
                override fun visitElement(element: IrElement) {
                    element.acceptChildrenVoid(this)
                }

                override fun visitBody(body: IrBody) {
                    // Stop
                }

                override fun visitClass(declaration: IrClass) {
                    declaration.acceptChildrenVoid(this)
                    declaration.declarations.transformFlat(this@runPostfix::transformFlat)
                    declaration.advance()
                }
            })

            return this@runPostfix.transformFlat(declaration).also {
                declaration.advance()
            }
        }
    }
}

fun ClassLoweringPass.toDeclarationTransformer(): DeclarationTransformer {
    return object : DeclarationTransformer {
        override fun transformFlat(declaration: IrDeclaration): List<IrDeclaration>? {
            declaration.acceptVoid(object : IrElementVisitorVoid {
                override fun visitElement(element: IrElement) {
                    element.acceptChildrenVoid(this)
                }

                override fun visitBody(body: IrBody) {
                    // Stop
                }

                override fun visitClass(declaration: IrClass) {
                    declaration.acceptChildrenVoid(this)
                    this@toDeclarationTransformer.lower(declaration)
                    declaration.advance()
                }
            })
            declaration.advance()
            return null
        }
    }
}

fun FunctionLoweringPass.toDeclarationTransformer(): DeclarationTransformer {
    return object : DeclarationTransformer {
        override fun transformFlat(declaration: IrDeclaration): List<IrDeclaration>? {
            declaration.acceptVoid(object : IrElementVisitorVoid {
                override fun visitElement(element: IrElement) {
                    element.acceptChildrenVoid(this)
                }

                override fun visitFunction(declaration: IrFunction) {
                    declaration.acceptChildrenVoid(this)
                    this@toDeclarationTransformer.lower(declaration)
                }
            })
            return null
        }
    }
}

// TODO should it really be run recursively?
fun BodyLoweringPass.toDeclarationTransformer(): DeclarationTransformer {
    return object : DeclarationTransformer {
        override fun transformFlat(declaration: IrDeclaration): List<IrDeclaration>? {
            declaration.accept(object : IrElementVisitor<Unit, IrDeclaration> {
                override fun visitElement(element: IrElement, data: IrDeclaration) {
                    element.acceptChildren(this, data)
                }

                override fun visitDeclaration(declaration: IrDeclaration, data: IrDeclaration) {
                    declaration.acceptChildren(this, declaration)
                    declaration.advance()
                }

                override fun visitBody(body: IrBody, data: IrDeclaration) {
//                    body.acceptChildren(this, data)
                    lower(body, data)
                }
            }, declaration)
            declaration.advance()
            return null
        }
    }
}

// TODO should it really be run recursively?
fun NullableBodyLoweringPass.toDeclarationTransformer(): DeclarationTransformer {
    return object : DeclarationTransformer {
        override fun transformFlat(declaration: IrDeclaration): List<IrDeclaration>? {
            declaration.accept(object : IrElementVisitorVoid {
                override fun visitElement(element: IrElement) {
                    element.acceptChildrenVoid(this)
                }

                private fun lowerAnnotations(declaration: IrDeclaration) {
                    declaration.annotations.forEach {
                        lower(it, declaration)
                    }
                    declaration.advance()
                }

                override fun visitDeclaration(declaration: IrDeclaration) {
                    lowerAnnotations(declaration)
                    declaration.acceptChildrenVoid(this)
                    declaration.advance()
                }

                override fun visitAnonymousInitializer(declaration: IrAnonymousInitializer) {
                    lowerAnnotations(declaration)
                    lower(declaration.body, declaration)
                    declaration.advance()
                }

                override fun visitEnumEntry(declaration: IrEnumEntry) {
                    lowerAnnotations(declaration)
                    lower(declaration.initializerExpression, declaration)
                    declaration.correspondingClass?.accept(this, null)
                    declaration.advance()
                }

                override fun visitField(declaration: IrField) {
                    lowerAnnotations(declaration)
                    lower(declaration.initializer, declaration)
                    declaration.advance()
                }

                override fun visitFunction(declaration: IrFunction) {
                    lowerAnnotations(declaration)
                    declaration.valueParameters.forEach { visitValueParameter(it) }
                    lower(declaration.body, declaration)
                    declaration.advance()
                }

                override fun visitValueParameter(declaration: IrValueParameter) {
                    lowerAnnotations(declaration)
                    lower(declaration.defaultValue, declaration)
                    declaration.advance()
                }

                override fun visitBody(body: IrBody) {
                    error("Missed a body")
                }
            }, null)
            declaration.advance()
            return null
        }
    }
}