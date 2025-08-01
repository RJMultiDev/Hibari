@file:OptIn(UnsafeDuringIrConstructionAPI::class)

package com.huanli233.hibari.compiler.transformer

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid

class HibariInlineLambdaLocator(private val context: IrPluginContext) {
    private val inlineLambdaToParameter = mutableMapOf<IrFunctionSymbol, IrValueParameter>()
    private val inlineFunctionExpressions = mutableSetOf<IrExpression>()

    fun isInlineLambda(irFunction: IrFunction): Boolean =
        irFunction.symbol in inlineLambdaToParameter.keys

    fun isCrossinlineLambda(irFunction: IrFunction): Boolean =
        inlineLambdaToParameter[irFunction.symbol]?.isCrossinline == true

    fun isInlineFunctionExpression(expression: IrExpression): Boolean =
        expression in inlineFunctionExpressions

    fun preservesComposableScope(irFunction: IrFunction): Boolean =
        inlineLambdaToParameter[irFunction.symbol]?.let {
            !it.isCrossinline && !it.type.hasAnnotation(DISALLOW_TUNABLE_CALLS_ANNOTATION_ID)
        } ?: false

    // Locate all inline lambdas in the scope of the given IrElement.
    fun scan(element: IrElement) {
        element.acceptVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitValueParameter(declaration: IrValueParameter) {
                declaration.acceptChildrenVoid(this)
                val parent = declaration.parent as? IrFunction
                if (parent?.isInlineFunctionCall(context) == true &&
                    declaration.isInlinedFunction()
                ) {
                    declaration.defaultValue?.expression?.unwrapLambda()?.let {
                        inlineLambdaToParameter[it] = declaration
                    }
                }
            }

            override fun visitFunctionAccess(expression: IrFunctionAccessExpression) {
                expression.acceptChildrenVoid(this)
                val function = expression.symbol.owner
                if (function.isInlineFunctionCall(context)) {
                    for (parameter in function.parameters) {
                        if (parameter.isInlinedFunction()) {
                            expression.arguments[parameter.indexInParameters]
                                ?.also { inlineFunctionExpressions += it }
                                ?.unwrapLambda()
                                ?.let { inlineLambdaToParameter[it] = parameter }
                        }
                    }
                }
            }
        })
    }
}

// TODO: There is a Kotlin command line option to disable inlining (-Xno-inline). The code
//       should check for this option.
private fun IrFunction.isInlineFunctionCall(context: IrPluginContext) =
    isInline || isInlineArrayConstructor(context)

// Constructors can't be marked as inline in metadata, hence this hack.
private fun IrFunction.isInlineArrayConstructor(context: IrPluginContext): Boolean =
    this is IrConstructor && parameters.size == 2 && constructedClass.symbol.let {
        it == context.irBuiltIns.arrayClass ||
                it in context.irBuiltIns.primitiveArraysToPrimitiveTypes
    }

fun IrExpression.unwrapLambda(): IrFunctionSymbol? = when {
    this is IrBlock && origin.isLambdaBlockOrigin ->
        (statements.lastOrNull() as? IrFunctionReference)?.symbol

    this is IrFunctionExpression ->
        function.symbol

    else ->
        null
}

private val IrStatementOrigin?.isLambdaBlockOrigin: Boolean
    get() = isLambda || this == IrStatementOrigin.ADAPTED_FUNCTION_REFERENCE ||
            this == IrStatementOrigin.SUSPEND_CONVERSION

// This is copied from JvmIrInlineUtils.kt in the Kotlin compiler, since we
// need to check for synthetic composable functions.
private fun IrValueParameter.isInlinedFunction(): Boolean =
    kind == IrParameterKind.Regular &&
            !isNoinline &&
            (type.isFunction() || type.isSuspendFunction() || type.isSyntheticTunableFunction()) &&
            // Parameters with default values are always nullable, so check the expression too.
            // Note that the frontend has a diagnostic for nullable inline parameters, so actually
            // making this return `false` requires using `@Suppress`.
            (!type.isNullable() || defaultValue?.expression?.type?.isNullable() == false)