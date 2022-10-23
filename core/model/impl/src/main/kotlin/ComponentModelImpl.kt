package com.yandex.daggerlite.core.model.impl

import com.yandex.daggerlite.base.ObjectCache
import com.yandex.daggerlite.core.model.ComponentDependencyModel
import com.yandex.daggerlite.core.model.ComponentFactoryModel
import com.yandex.daggerlite.core.model.ComponentModel
import com.yandex.daggerlite.core.model.ComponentModel.EntryPoint
import com.yandex.daggerlite.core.model.ConditionalHoldingModel
import com.yandex.daggerlite.core.model.HasNodeModel
import com.yandex.daggerlite.core.model.MembersInjectorModel
import com.yandex.daggerlite.core.model.ModuleModel
import com.yandex.daggerlite.core.model.NodeDependency
import com.yandex.daggerlite.core.model.NodeModel
import com.yandex.daggerlite.core.model.Variant
import com.yandex.daggerlite.lang.Annotation
import com.yandex.daggerlite.lang.BuiltinAnnotation
import com.yandex.daggerlite.lang.Method
import com.yandex.daggerlite.lang.Type
import com.yandex.daggerlite.lang.TypeDeclaration
import com.yandex.daggerlite.lang.TypeDeclarationKind
import com.yandex.daggerlite.validation.MayBeInvalid
import com.yandex.daggerlite.validation.Validator
import com.yandex.daggerlite.validation.format.Strings
import com.yandex.daggerlite.validation.format.Strings.Errors
import com.yandex.daggerlite.validation.format.appendChildContextReference
import com.yandex.daggerlite.validation.format.modelRepresentation
import com.yandex.daggerlite.validation.format.reportError

internal class ComponentModelImpl private constructor(
    private val declaration: TypeDeclaration,
) : ComponentModel, ConditionalHoldingModel {
    private val impl = declaration.getAnnotation(BuiltinAnnotation.Component)

    private val conditionalsModel by lazy {
        ConditionalHoldingModelImpl(declaration.getAnnotations(BuiltinAnnotation.Conditional))
    }

    override val conditionals: List<ConditionalHoldingModel.ConditionalWithFlavorConstraintsModel>
        get() = conditionalsModel.conditionals

    override val type: Type
        get() = declaration.asType()

    override fun asNode(): NodeModel = NodeModelImpl(
        type = declaration.asType(),
        forQualifier = null,
    )

    override fun <R> accept(visitor: HasNodeModel.Visitor<R>): R {
        return visitor.visitComponent(this)
    }

    override val scopes: Set<Annotation> by lazy {
        declaration.annotations.filter { it.isScope() }.toSet()
    }

    override val modules: Set<ModuleModel> by lazy {
        val allModules = mutableSetOf<ModuleModel>()
        val moduleQueue: ArrayDeque<ModuleModel> = ArrayDeque(
            impl?.modules?.map(Type::declaration)?.map { ModuleModelImpl(it) }?.toList() ?: emptyList())
        while (moduleQueue.isNotEmpty()) {
            val module = moduleQueue.removeFirst()
            if (!allModules.add(module)) {
                continue
            }
            moduleQueue += module.includes
        }
        allModules
    }

    override val dependencies: Set<ComponentDependencyModel> by lazy {
        impl?.dependencies?.map { ComponentDependencyModelImpl(it) }?.toSet() ?: emptySet()
    }

    override val entryPoints: Set<EntryPoint> by lazy {
        class EntryPointImpl(
            override val getter: Method,
            override val dependency: NodeDependency,
        ) : EntryPoint {
            override fun validate(validator: Validator) {
                validator.child(dependency.node)
            }

            override fun toString(childContext: MayBeInvalid?) = modelRepresentation(
                modelClassName = "entry-point",
                representation = {
                    append("${getter.name}()")
                    if (childContext == dependency.node) {
                        append(": ")
                        appendChildContextReference(reference = getter.returnType)
                    }
                },
            )
        }

        declaration.methods.filter {
            it.isAbstract && it.parameters.none()
        }.map { method ->
            EntryPointImpl(
                dependency = NodeDependency(
                    type = method.returnType,
                    forQualifier = method,
                ),
                getter = method,
            )
        }.toSet()
    }

    override val memberInjectors: Set<MembersInjectorModel> by lazy {
        declaration.methods.filter {
            MembersInjectorModelImpl.canRepresent(it)
        }.map { method ->
            MembersInjectorModelImpl(
                injector = method,
            )
        }.toSet()
    }

    override val factory: ComponentFactoryModel? by lazy {
        declaration.nestedClasses
            .find { ComponentFactoryModelImpl.canRepresent(it) }?.let {
                ComponentFactoryModelImpl(factoryDeclaration = it)
            }
    }

    override val isRoot: Boolean = impl?.isRoot ?: false

    override val requiresSynchronizedAccess: Boolean
        get() = impl?.multiThreadAccess ?: false

    override val variant: Variant by lazy {
        VariantImpl(impl?.variant ?: emptyList())
    }

    override fun validate(validator: Validator) {
        validator.child(conditionalsModel)

        for (module in modules) {
            validator.child(module)
        }
        for (dependency in dependencies) {
            validator.child(dependency)
        }
        for (entryPoint in entryPoints) {
            validator.child(entryPoint)
        }
        for (memberInjector in memberInjectors) {
            validator.child(memberInjector)
        }
        if (declaration.nestedClasses.count(ComponentFactoryModelImpl::canRepresent) > 1) {
            validator.reportError(Errors.multipleCreators()) {
                declaration.nestedClasses.filter(ComponentFactoryModelImpl::canRepresent).forEach {
                    addNote(Strings.Notes.conflictingCreator(it))
                }
            }
        }

        factory?.let(validator::child)

        for (method in declaration.methods) {
            if (!method.isAbstract) continue
            if (method.parameters.count() > 1) {
                validator.reportError(Errors.unknownMethodInComponent(method = method))
            }
        }

        if (impl == null) {
            validator.reportError(Errors.nonComponent())
        }

        if (declaration.kind != TypeDeclarationKind.Interface) {
            validator.reportError(Errors.nonInterfaceComponent())
        }

        if (factory == null) {
            if (!isRoot) {
                validator.reportError(Errors.missingCreatorForNonRoot())
            }

            if (dependencies.isNotEmpty()) {
                validator.reportError(Errors.missingCreatorForDependencies())
            }

            if (modules.any { it.requiresInstance && !it.isTriviallyConstructable }) {
                validator.reportError(Errors.missingCreatorForModules()) {
                    modules.filter {
                        it.requiresInstance && !it.isTriviallyConstructable
                    }.forEach { module ->
                        addNote(Strings.Notes.missingModuleInstance(module))
                    }
                }
            }
        }
    }

    override fun toString(childContext: MayBeInvalid?) = modelRepresentation(
        modelClassName = if (isRoot) "root-component" else "component",
        representation = declaration,
    )

    companion object Factory : ObjectCache<TypeDeclaration, ComponentModelImpl>() {
        operator fun invoke(key: TypeDeclaration) = createCached(key, ::ComponentModelImpl)

        fun canRepresent(declaration: TypeDeclaration): Boolean {
            return declaration.getAnnotation(BuiltinAnnotation.Component) != null
        }
    }
}
