package com.yandex.daggerlite.generator

import com.squareup.javapoet.ClassName
import com.squareup.javapoet.TypeSpec
import com.yandex.daggerlite.core.lang.FieldLangModel
import com.yandex.daggerlite.core.lang.FunctionLangModel
import com.yandex.daggerlite.core.lang.MemberLangModel
import com.yandex.daggerlite.generator.poetry.TypeSpecBuilder
import com.yandex.daggerlite.generator.poetry.buildClass
import com.yandex.daggerlite.generator.poetry.buildExpression
import com.yandex.daggerlite.graph.BindingGraph
import com.yandex.daggerlite.graph.component1
import com.yandex.daggerlite.graph.component2
import javax.inject.Provider
import javax.lang.model.element.Modifier.FINAL
import javax.lang.model.element.Modifier.PUBLIC
import javax.lang.model.element.Modifier.STATIC

internal class ComponentGenerator(
    private val graph: BindingGraph,
    val generatedClassName: ClassName = graph.model.name.asClassName { "Dagger$$it" },
) {
    interface Contributor {
        fun generate(builder: TypeSpecBuilder)
    }

    private val contributors = mutableListOf<Contributor>()
    private val methodsNs = Namespace()
    private val fieldsNs = Namespace(prefix = "m")
    private val subcomponentNs = Namespace()

    private val slotSwitchingGenerator = lazyProvider {
        SlotSwitchingGenerator(
            thisGraph = graph,
        ).also(this::registerContributor)
    }
    private val unscopedProviderGenerator = lazyProvider {
        UnscopedProviderGenerator(
            componentImplName = generatedClassName,
        ).also(this::registerContributor)
    }
    private val scopedProviderGenerator = lazyProvider {
        ScopedProviderGenerator(
            componentImplName = generatedClassName,
            useDoubleChecking = graph.requiresSynchronizedAccess,
        ).also(this::registerContributor)
    }
    private val lockGeneratorProvider = lazyProvider {
        LockGenerator(
            componentImplName = generatedClassName,
        ).also(this::registerContributor)
    }

    init {
        Generators[graph] = object : GeneratorContainer {
            override val implName: ClassName get() = this@ComponentGenerator.generatedClassName

            override val accessStrategyManager = AccessStrategyManager(
                thisGraph = graph,
                fieldsNs = fieldsNs,
                methodsNs = methodsNs,
                multiFactory = slotSwitchingGenerator,
                unscopedProviderGenerator = unscopedProviderGenerator,
                scopedProviderGenerator = scopedProviderGenerator,
                lockGenerator = lockGeneratorProvider,
            ).also(this@ComponentGenerator::registerContributor)

            override val conditionGenerator = ConditionGenerator(
                fieldsNs = fieldsNs,
                methodsNs = methodsNs,
                thisGraph = graph,
            ).also(this@ComponentGenerator::registerContributor)

            override val multiBindingGenerator = MultiBindingGenerator(
                methodsNs = methodsNs,
                thisGraph = graph,
            ).also(this@ComponentGenerator::registerContributor)

            override val mapBindingGenerator = MapBindingGenerator(
                methodsNs = methodsNs,
                thisGraph = graph,
            ).also(this@ComponentGenerator::registerContributor)

            override val factoryGenerator = ComponentFactoryGenerator(
                thisGraph = graph,
                fieldsNs = fieldsNs,
                componentImplName = implName,
            ).also(this@ComponentGenerator::registerContributor)

            override val assistedInjectFactoryGenerator = AssistedInjectFactoryGenerator(
                thisGraph = graph,
                componentImplName = generatedClassName,
            ).also(this@ComponentGenerator::registerContributor)
        }
    }

    private val childGenerators: Collection<ComponentGenerator> = graph.children.map { childGraph ->
        ComponentGenerator(
            graph = childGraph,
            generatedClassName = generatedClassName.nestedClass(
                subcomponentNs.name(childGraph.model.name, suffix = "Impl", firstCapital = true)
            ),
        )
    }

    fun generate(): TypeSpec = buildClass(generatedClassName) {
        val componentInterface = graph.model.typeName()

        annotation<SuppressWarnings> { stringValues("unchecked", "rawtypes", "NullableProblems") }
        modifiers(FINAL)
        if (!graph.isRoot) {
            modifiers(/*package-private*/ STATIC)
        } else {
            modifiers(PUBLIC)
        }
        implements(componentInterface)

        graph.entryPoints.forEach { (getter, dependency) ->
            // TODO: reuse entry-points as accessors to reduce method count.
            overrideMethod(getter) {
                modifiers(PUBLIC)
                +buildExpression {
                    +"return "
                    graph.resolveBinding(dependency.node)
                        .generateAccess(builder = this, inside = graph, kind = dependency.kind)
                }
            }
        }
        graph.memberInjectors.forEach { membersInjector ->
            overrideMethod(membersInjector.injector) {
                val instanceName = membersInjector.injector.parameters.first().name
                modifiers(PUBLIC)
                membersInjector.membersToInject.forEach { (member, dependency) ->
                    val binding = graph.resolveBinding(dependency.node)
                    +buildExpression {
                        member.accept(object : MemberLangModel.Visitor<Unit> {
                            override fun visitFunction(model: FunctionLangModel) {
                                +"%N.%N(".formatCode(instanceName, member.name)
                                binding.generateAccess(
                                    builder = this@buildExpression, inside = graph, kind = dependency.kind)
                                +")"
                            }

                            override fun visitField(model: FieldLangModel) {
                                +"%N.%N = ".formatCode(instanceName, member.name)
                                binding.generateAccess(
                                    builder = this@buildExpression, inside = graph, kind = dependency.kind)
                            }
                        })
                    }
                }
            }
        }

        childGenerators.forEach { childGenerator ->
            nestedType {
                childGenerator.generate()
            }
        }

        contributors.forEach {
            it.generate(this)
        }
    }

    private fun registerContributor(contributor: Contributor) {
        contributors += contributor
    }

    private class LazyProvider<T : Any>(initializer: () -> T) : Provider<T> {
        private val instance by lazy(initializer)
        override fun get(): T = instance
    }

    private fun <T : Any> lazyProvider(initializer: () -> T): Provider<T> = LazyProvider(initializer)
}

