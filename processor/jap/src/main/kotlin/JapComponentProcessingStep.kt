package com.yandex.daggerlite.processor.jap

import com.google.auto.common.BasicAnnotationProcessor
import com.google.common.collect.ImmutableSetMultimap
import com.yandex.daggerlite.Component
import com.yandex.daggerlite.lang.LangModelFactory
import com.yandex.daggerlite.lang.TypeDeclaration
import com.yandex.daggerlite.lang.jap.JavaxModelFactoryImpl
import com.yandex.daggerlite.lang.jap.ProcessingUtils
import com.yandex.daggerlite.lang.jap.TypeDeclaration
import com.yandex.daggerlite.lang.jap.asTypeElement
import com.yandex.daggerlite.lang.use
import com.yandex.daggerlite.processor.common.Logger
import com.yandex.daggerlite.processor.common.Options
import com.yandex.daggerlite.processor.common.ProcessorDelegate
import com.yandex.daggerlite.processor.common.process
import java.io.Writer
import javax.annotation.processing.Filer
import javax.annotation.processing.Messager
import javax.lang.model.element.Element
import javax.lang.model.element.TypeElement
import javax.lang.model.util.Elements
import javax.lang.model.util.Types

internal class JapComponentProcessingStep(
    messager: Messager,
    private val filer: Filer,
    private val types: Types,
    private val elements: Elements,
    options: Map<String, String>,
) : BasicAnnotationProcessor.Step, ProcessorDelegate<TypeElement> {
    override val logger: Logger = JapLogger(messager)

    override val options: Options = Options(options)

    override fun annotations(): Set<String> = setOf(Component::class.qualifiedName!!)

    override fun createDeclaration(source: TypeElement) = TypeDeclaration(source)

    override fun getSourceFor(declaration: TypeDeclaration): TypeElement {
        return declaration.platformModel as TypeElement
    }

    override fun openFileForGenerating(
        sources: Sequence<TypeElement>,
        packageName: String,
        className: String,
    ): Writer {
        val name = if (packageName.isNotEmpty()) "$packageName.$className" else className
        return filer.createSourceFile(name, sources.first()).openWriter().buffered()
    }

    override fun process(elementsByAnnotation: ImmutableSetMultimap<String, Element>): Set<Element> {
        ProcessingUtils(types, elements).use {
            LangModelFactory.use(JavaxModelFactoryImpl()) {
                process(
                    sources = elementsByAnnotation.values()
                        .map(Element::asTypeElement)
                        .asSequence(),
                    delegate = this,
                )
            }
        }
        return emptySet()
    }
}