package com.yandex.daggerlite.core.model.impl

import com.yandex.daggerlite.Binds
import com.yandex.daggerlite.IntoMap
import com.yandex.daggerlite.base.memoize
import com.yandex.daggerlite.core.model.BindsBindingModel
import com.yandex.daggerlite.core.model.CollectionTargetKind
import com.yandex.daggerlite.core.model.ModuleHostedBindingModel
import com.yandex.daggerlite.core.model.ModuleHostedBindingModel.BindingTargetModel
import com.yandex.daggerlite.core.model.ModuleModel
import com.yandex.daggerlite.core.model.NodeDependency
import com.yandex.daggerlite.core.model.NodeModel
import com.yandex.daggerlite.core.model.ProvidesBindingModel
import com.yandex.daggerlite.lang.AnnotationLangModel
import com.yandex.daggerlite.lang.AnnotationValueVisitorAdapter
import com.yandex.daggerlite.lang.IntoCollectionAnnotationLangModel
import com.yandex.daggerlite.lang.LangModelFactory
import com.yandex.daggerlite.lang.Method
import com.yandex.daggerlite.lang.getCollectionType
import com.yandex.daggerlite.lang.isAnnotatedWith
import com.yandex.daggerlite.lang.isKotlinObject
import com.yandex.daggerlite.validation.MayBeInvalid
import com.yandex.daggerlite.validation.Validator
import com.yandex.daggerlite.validation.format.Strings.Errors
import com.yandex.daggerlite.validation.format.appendChildContextReference
import com.yandex.daggerlite.validation.format.modelRepresentation
import com.yandex.daggerlite.validation.format.reportError
import kotlin.LazyThreadSafetyMode.PUBLICATION

internal abstract class ModuleHostedBindingBase : ModuleHostedBindingModel {
    override val scopes: Set<AnnotationLangModel> by lazy {
        method.annotations.filter { it.isScope() }.toSet()
    }

    override val target: BindingTargetModel by lazy {
        if (method.returnType.isVoid) {
            BindingTargetModel.Plain(NodeModelImpl.Factory.VoidNode())
        } else {
            val target = NodeModelImpl(type = method.returnType, forQualifier = method)
            val intoList = method.intoListAnnotationIfPresent
            val intoSet = method.intoSetAnnotationIfPresent
            when {
                intoList != null -> computeMultiContributionTarget(
                    intoCollection = intoList,
                    kind = CollectionTargetKind.List,
                )

                intoSet != null -> computeMultiContributionTarget(
                    intoCollection = intoSet,
                    kind = CollectionTargetKind.Set,
                )

                method.isAnnotatedWith<IntoMap>() -> {
                    val key = method.annotations.find { it.isMapKey() }
                    val annotationClass = key?.annotationClass
                    val valueAttribute = annotationClass?.attributes?.find { it.name == "value" }
                    val keyValue = valueAttribute?.let { key.getValue(valueAttribute) }
                    BindingTargetModel.MappingContribution(
                        node = target,
                        keyType = valueAttribute?.type,
                        keyValue = keyValue,
                        mapKeyClass = annotationClass,
                    )
                }

                else -> BindingTargetModel.Plain(node = target)
            }
        }
    }

    override fun validate(validator: Validator) {
        validator.child(target.node)

        if (method.intoListAnnotationIfPresent != null && method.intoSetAnnotationIfPresent != null) {
            validator.reportError(Errors.conflictingCollectionBindingAnnotations())
        }

        when (target) {
            is BindingTargetModel.FlattenMultiContribution -> {
                val firstArg = method.returnType.typeArguments.firstOrNull()
                if (firstArg == null || !LangModelFactory.getCollectionType(firstArg)
                        .isAssignableFrom(method.returnType)) {
                    validator.reportError(Errors.invalidFlatteningMultibinding(insteadOf = method.returnType))
                }
            }
            is BindingTargetModel.MappingContribution -> run {
                val keys = method.annotations.filter { it.isMapKey() }.toList()
                if (keys.size != 1) {
                    validator.reportError(if (keys.isEmpty()) Errors.missingMapKey() else Errors.multipleMapKeys())
                    return@run
                }
                val key = keys.first()
                val clazz = key.annotationClass
                val valueAttribute = clazz.attributes.find { it.name == "value" }
                if (valueAttribute == null) {
                    validator.reportError(Errors.missingMapKeyValue(annotationClass = clazz))
                    return@run
                }
                key.getValue(valueAttribute).accept(object : AnnotationValueVisitorAdapter<Unit>() {
                    // Unresolved is not reported here, as it's [:lang]'s problem and will be reported by the
                    //  compiler anyway.
                    override fun visitDefault() = Unit
                    override fun visitAnnotation(value: AnnotationLangModel) {
                        validator.reportError(Errors.unsupportedAnnotationValueAsMapKey(annotationClass = clazz))
                    }
                    override fun visitArray(value: List<AnnotationLangModel.Value>) {
                        validator.reportError(Errors.unsupportedArrayValueAsMapKey(annotationClass = clazz))
                    }
                })
            }
            is BindingTargetModel.DirectMultiContribution, is BindingTargetModel.Plain -> { /*Nothing to validate*/ }
        }
    }

    private fun computeMultiContributionTarget(
        intoCollection: IntoCollectionAnnotationLangModel,
        kind: CollectionTargetKind,
    ) : BindingTargetModel {
        val target = NodeModelImpl(type = method.returnType, forQualifier = method)
        return if (intoCollection.flatten) {
            BindingTargetModel.FlattenMultiContribution(
                node = target,
                flattened = NodeModelImpl(
                    type = method.returnType.typeArguments.firstOrNull() ?: method.returnType,
                    qualifier = target.qualifier,
                ),
                kind = kind,
            )
        } else BindingTargetModel.DirectMultiContribution(
            node = target,
            kind = kind,
        )
    }
}

internal class BindsImpl(
    override val method: Method,
    override val originModule: ModuleModel,
) : BindsBindingModel, ModuleHostedBindingBase() {

    init {
        assert(canRepresent(method))
    }

    override val sources = method.parameters.map { parameter ->
        NodeModelImpl(type = parameter.type, forQualifier = parameter)
    }.memoize()

    override fun validate(validator: Validator) {
        super.validate(validator)

        for (source in sources) {
            validator.child(source)
        }

        for (param in method.parameters) {
            if (!method.returnType.isAssignableFrom(param.type)) {
                validator.reportError(Errors.inconsistentBinds(
                    param = param.type,
                    returnType = method.returnType,
                ))
            }
        }

        if (!method.isAbstract) {
            validator.reportError(Errors.nonAbstractBinds())
        }
    }

    override fun <R> accept(visitor: ModuleHostedBindingModel.Visitor<R>): R {
        return visitor.visitBinds(this)
    }

    override fun toString(childContext: MayBeInvalid?) = modelRepresentation(
        modelClassName = "@binds",
        representation = {
            append("${originModule.type}::${method.name}(")
            when(childContext ?: Unit) {
                target.node -> {  // return type
                    append("): ")
                    appendChildContextReference(reference = method.returnType)
                }
                sources.singleOrNull() -> {  // alias
                    appendChildContextReference(reference = method.parameters.single())
                    append(")")
                }
                in sources -> {
                    val index = sources.indexOf(childContext)
                    append(".., ")
                    appendChildContextReference(reference = method.parameters.drop(index).first())
                    append(", ..)")
                }
                else -> {
                    append("...)")
                }
            }
        },
    )

    companion object {
        fun canRepresent(method: Method): Boolean {
            return method.isAnnotatedWith<Binds>()
        }
    }
}

internal class ProvidesImpl(
    override val method: Method,
    override val originModule: ModuleModelImpl,
) : ProvidesBindingModel,
    ModuleHostedBindingBase() {

    private val conditionalsModel by lazy {
        ConditionalHoldingModelImpl(checkNotNull(method.providesAnnotationIfPresent) { "Not reached" }.conditionals)
    }

    override val conditionals get() = conditionalsModel.conditionals

    override val inputs: List<NodeDependency> by lazy(PUBLICATION) {
        method.parameters.map { param ->
            NodeDependency(type = param.type, forQualifier = param)
        }.toList()
    }

    override fun validate(validator: Validator) {
        super.validate(validator)

        validator.child(conditionalsModel)

        for (dependency in inputs) {
            validator.child(dependency.node)
        }

        if (method.isAbstract) {
            validator.reportError(Errors.abstractProvides())
        }

        if (!method.isEffectivelyPublic) {
            validator.reportError(Errors.invalidAccessForProvides())
        }
    }

    override val requiresModuleInstance: Boolean
        get() = originModule.mayRequireInstance && !method.isStatic && !method.owner.isKotlinObject

    override fun <R> accept(visitor: ModuleHostedBindingModel.Visitor<R>): R {
        return visitor.visitProvides(this)
    }

    override fun toString(childContext: MayBeInvalid?) = modelRepresentation(
        modelClassName = "@provides",
        representation = {
            append("${originModule.type}::${method.name}(")
            when(childContext) {
                target.node -> {  // return type
                    append("): ")
                    appendChildContextReference(reference = method.returnType)
                }
                is NodeModel -> {
                    val index = inputs.indexOfFirst { it.node == childContext }
                    append(".., ")
                    appendChildContextReference(reference = method.parameters.drop(index).first())
                    append(", ..)")
                }
                else -> {
                    append("...)")
                }
            }
        },
    )

    companion object {
        fun canRepresent(method: Method): Boolean {
            return method.providesAnnotationIfPresent != null
        }
    }
}