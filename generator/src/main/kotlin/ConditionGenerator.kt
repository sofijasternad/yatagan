package com.yandex.daggerlite.generator

import com.squareup.javapoet.TypeName
import com.yandex.daggerlite.core.BindingGraph
import com.yandex.daggerlite.core.ConditionScope
import com.yandex.daggerlite.core.lang.FunctionLangModel
import com.yandex.daggerlite.generator.poetry.ExpressionBuilder
import com.yandex.daggerlite.generator.poetry.TypeSpecBuilder
import javax.lang.model.element.Modifier.FINAL
import javax.lang.model.element.Modifier.PRIVATE

internal class ConditionGenerator(
    fieldsNs: Namespace,
    private val thisGraph: BindingGraph,
) : ComponentGenerator.Contributor {
    private val literalToField: Map<ConditionScope.Literal, String> = run {
        var nextIndex = 0  // TODO: proper condition field naming
        thisGraph.localConditionLiterals.associateWith { fieldsNs.name(nextIndex++.toString()) }
    }

    private fun literalFieldName(literal: ConditionScope.Literal): String? {
        literalToField[literal]?.let {
            return it
        }

        // Check parents first - we need to generate condition as high as it is used.
        val parent = thisGraph.parent?.let(Generators::get)?.conditionGenerator
        if (parent != null) {
            val component = componentInstance(inside = thisGraph, graph = parent.thisGraph)
            val name = parent.literalFieldName(literal)
            if (name != null) {
                return "$component.$name"
            }
        }

        return null
    }

    override fun generate(builder: TypeSpecBuilder) = with(builder) {
        literalToField.forEach { (literal, field) ->
            field(TypeName.BOOLEAN, field) {
                modifiers(PRIVATE, FINAL)
                initializer {
                    if (literal.negated) +"!"
                    val rootType = literal.root.asType()
                    literal.path.first().let { member ->
                        when {
                            member.isStatic ->
                                +"%T.%N".formatCode(rootType.typeName(), member.name)
                            rootType.declaration.isKotlinObject ->
                                +"%T.INSTANCE.%N".formatCode(rootType.typeName(), member.name)
                            else -> throw IllegalStateException(
                                "Member '${member.name}' in $rootType must be accessible from the static context"
                            )
                        }
                        if (member is FunctionLangModel) +"()"
                    }
                    literal.path.asSequence().drop(1).forEach { member ->
                        +".%N".formatCode(member.name)
                        if (member is FunctionLangModel) +"()"
                    }
                }
            }
        }
    }

    fun expression(builder: ExpressionBuilder, conditionScope: ConditionScope) = with(builder) {
        join(conditionScope.expression, separator = " && ") { clause ->
            +"("
            join(clause, separator = " || ") { literal ->
                +checkNotNull(literalFieldName(literal))
            }
            +")"
        }
    }
}
