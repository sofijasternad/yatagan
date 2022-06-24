package com.yandex.daggerlite.testing

import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import javax.inject.Provider

@RunWith(Parameterized::class)
class CoreBindingsTest(
    driverProvider: Provider<CompileTestDriverBase>
) : CompileTestDriver by driverProvider.get() {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun parameters() = compileTestDrivers()
    }


    private lateinit var classes: SourceSet
    private lateinit var apiImpl: SourceSet

    @Before
    fun setUp() {
        classes = SourceSet {
            givenJavaSource(
                "test.MyScopedClass", """
            import javax.inject.Inject;
            import javax.inject.Singleton;
            
            @Singleton public class MyScopedClass {
                @Inject public MyScopedClass() {}
            }
        """.trimIndent()
            )

            givenJavaSource(
                "test.MySimpleClass", """
            import javax.inject.Inject;
            
            public class MySimpleClass {
                @Inject public MySimpleClass(MyScopedClass directDep) {}
            }
        """.trimIndent()
            )
        }

        apiImpl = SourceSet {
            givenJavaSource(
                "test.Api", """
        public interface Api {}    
        """.trimIndent()
            )
            givenJavaSource(
                "test.Impl", """
        import javax.inject.Inject;
            
        public class Impl implements Api {
          @Inject public Impl() {}
        }
        """.trimIndent()
            )
        }
    }

    @Test
    fun `basic component - direct, Provider and Lazy entry points`() {
        includeFromSourceSet(classes)

        givenJavaSource(
            "test.TestComponent", """
            import javax.inject.Singleton;
            import com.yandex.daggerlite.Component;
            import javax.inject.Provider;
            import com.yandex.daggerlite.Lazy;
                        
            @Component @Singleton
            public interface TestComponent {
                MySimpleClass getMySimpleClass();
                MyScopedClass getMyScopedClass();
                Provider<MySimpleClass> getMySimpleClassProvider();
                Lazy<MySimpleClass> getMySimpleClassLazy();
                Provider<MyScopedClass> getMyScopedClassProvider();
                Lazy<MyScopedClass> getMyScopedClassLazy();
            }
        """.trimIndent()
        )

        expectSuccessfulValidation()
    }

    @Test
    fun `basic component - simple @Binds`() {
        includeFromSourceSet(apiImpl)

        givenJavaSource(
            "test.MyModule", """
        import com.yandex.daggerlite.Binds;
        import com.yandex.daggerlite.Module;
                    
        @Module
        public interface MyModule {
          @Binds Api bind(Impl i);
        }
        """.trimIndent()
        )

        givenJavaSource(
            "test.TestComponent", """
            import javax.inject.Singleton;
            import com.yandex.daggerlite.Component;
            import javax.inject.Provider;
            import com.yandex.daggerlite.Lazy;
            
            @Component(modules = {MyModule.class}) @Singleton
            public interface TestComponent {
                Api get();
                Provider<Api> getProvider();
                Lazy<Api> getLazy();
            }
        """.trimIndent()
        )

        givenKotlinSource("test.TestCase", """
            import com.yandex.daggerlite.Dagger
            fun test() {
                val c = Dagger.create(TestComponent::class.java)
                assert(c.get() is Impl)
            }
        """.trimIndent()
        )

        expectSuccessfulValidation()
    }

    @Test
    fun `basic component - simple @Provides`() {
        includeFromSourceSet(apiImpl)

        givenJavaSource(
            "test.MyModule", """
        import com.yandex.daggerlite.Provides;
        import com.yandex.daggerlite.Module;
        
        @Module
        public interface MyModule {
          @Provides static Api provides() {
            return new Impl();
          }
        }
        """
        )
        givenJavaSource(
            "test.TestComponent", """
            import javax.inject.Singleton;
            import com.yandex.daggerlite.Component;
            import javax.inject.Provider;
            import com.yandex.daggerlite.Lazy;

            @Component(modules = {MyModule.class}) @Singleton
            public interface TestComponent {
                Api get();
                Provider<Api> getProvider();
                Lazy<Api> getLazy();
            }
        """
        )

        givenKotlinSource("test.TestCase", """
            import com.yandex.daggerlite.Dagger
            fun test() {
                val c = Dagger.create(TestComponent::class.java)
                assert(c.get() is Impl)
            }
        """
        )

        expectSuccessfulValidation()
    }

    @Test
    fun `basic component - @Provides with dependencies`() {
        includeFromSourceSet(classes)
        includeFromSourceSet(apiImpl)

        givenJavaSource(
            "test.MyModule", """
        import javax.inject.Provider;
        import com.yandex.daggerlite.Provides;
        import com.yandex.daggerlite.Module;

        @Module
        public interface MyModule {
          @Provides static Api provides(Provider<MyScopedClass> dep, MySimpleClass dep2) {
            return new Impl();
          }
        }
        """
        )
        givenJavaSource(
            "test.TestComponent", """
            import javax.inject.Singleton;
            import com.yandex.daggerlite.Component;
            import javax.inject.Provider;
            import com.yandex.daggerlite.Lazy;

            @Component(modules = {MyModule.class}) @Singleton
            public interface TestComponent {
                Api get();
                Provider<Api> getProvider();
                Lazy<Api> getLazy();
            }
        """
        )

        givenKotlinSource("test.TestCase", """
            import com.yandex.daggerlite.Dagger
            fun test() {
                val c = Dagger.create(TestComponent::class.java)
                assert(c.get() is Impl)
            }
        """
        )

        expectSuccessfulValidation()
    }

    @Test(timeout = 10_000)
    fun `basic component - cyclic reference with Provider edge`() {
        includeFromSourceSet(classes)
        includeFromSourceSet(apiImpl)

        givenJavaSource("test.MyClassA", """
            public class MyClassA { public @javax.inject.Inject MyClassA(MyClassB dep) {} }
        """.trimIndent())
        givenJavaSource("test.MyClassB", """
            import javax.inject.Provider;
            public class MyClassB { public @javax.inject.Inject MyClassB(Provider<MyClassA> dep) {} }
        """.trimIndent())

        givenJavaSource("test.TestComponent", """
            import javax.inject.Singleton;
            import com.yandex.daggerlite.Component;
            
            @Component @Singleton
            public interface TestComponent {
                MyClassA get();
            }
        """)

        expectSuccessfulValidation()
    }

    @Test
    fun `basic component - add imports to generated component`() {
        givenJavaSource("utils.MySimpleClass", """ 
            import javax.inject.Inject;

            public class MySimpleClass {
                @Inject
                public MySimpleClass() {}
            }
        """)

        givenJavaSource("test.MyProvider", """
            import javax.inject.Inject;
            import utils.MySimpleClass;
            
            public class MyProvider {
                @Inject
                public MyProvider(MySimpleClass i) {}
            }
        """
        )

        givenJavaSource("test.TestComponent", """
            import com.yandex.daggerlite.Component;
            
            @Component
            interface TestComponent {
                MyProvider get();
            }
        """)

        expectSuccessfulValidation()
    }

    @Test
    fun `basic component - module includes inherited methods`() {
        includeFromSourceSet(apiImpl)

        givenJavaSource("test.MyModule", """
            import com.yandex.daggerlite.Module;
            import com.yandex.daggerlite.Binds;

            @Module
            interface MyModule {
                @Binds
                Api binds(Impl i);
            }
        """)

        givenJavaSource("test.MySubModule", """
            import com.yandex.daggerlite.Module;
            
            @Module
            interface MySubModule extends MyModule {}
        """)

        givenJavaSource("test.TestComponent", """
            import com.yandex.daggerlite.Component;

            @Component(modules = MySubModule.class)
            public interface TestComponent {
                Api get();
            }
        """)

        expectSuccessfulValidation()
    }

    @Test
    fun `basic component - provide primitive type`() {
        givenJavaSource("test.MyModule", """
            import com.yandex.daggerlite.Provides;
            import com.yandex.daggerlite.Module;
            
            @Module
            public interface MyModule {
                @Provides
                static int provides() {
                    return 1;
                }
            }
        """)

        givenJavaSource("test.TestComponent", """
            import javax.inject.Provider;
            import com.yandex.daggerlite.Component;
            import com.yandex.daggerlite.Lazy;
            
            @Component(modules = MyModule.class)
            public interface TestComponent {
                int get();
                Lazy<Integer> getIntLazy();
                Provider<Integer> getIntProvider();
            }
        """)

        expectSuccessfulValidation()
    }

    @Test
    fun `basic component - convert class to primitive type`() {
        givenJavaSource("test.MyModule", """
            import com.yandex.daggerlite.Provides;
            import com.yandex.daggerlite.Module;
            
            @Module
            public interface MyModule {
                @Provides
                static Integer provides() {
                    return 1;
                }
            }
        """)

        givenJavaSource("test.TestComponent", """
            import com.yandex.daggerlite.Component;

            @Component(modules = MyModule.class)
            public interface TestComponent {
                int get();
            }
        """)

        expectSuccessfulValidation()
    }

    @Test
    fun `basic component - provide array type`() {
        givenJavaSource("test.MyModule", """
            import com.yandex.daggerlite.Provides;
            import com.yandex.daggerlite.Module;
            
            @Module
            public interface MyModule {
                @Provides
                static int[] providesIntArray() {
                    return new int[] { 228, };
                }
                
                @Provides
                static Double[] providesDoubleArray() {
                    return new Double[] { 7.40, };
                }
                
                @Provides
                static String[] providesStringArray() {
                    return new String[] { "foo", "bar" };
                }
            }
        """)

        givenJavaSource("test.Consumer", """
            import javax.inject.Provider;
            import javax.inject.Inject;

            public class Consumer<T> {
                @Inject
                public Consumer(int[] i1, Provider<int[]> i2, T[] i3, Provider<T[]> i4, String[] i5,
                                Provider<String[]> i6) {}
            }
        """.trimIndent())

        givenJavaSource("test.TestComponent", """
            import javax.inject.Inject;
            import javax.inject.Provider;
            import com.yandex.daggerlite.Component;
            import com.yandex.daggerlite.Lazy;
            
            @Component(modules = MyModule.class)
            public interface TestComponent {
                int[] getInt();
                Lazy<int[]> getIntLazy();
                Provider<int[]> getIntProvider();

                Provider<Double[]> getDoubleProvider();
                Double[] getDouble();
                
                String[] getString();
                Lazy<String[]> getStringLazy();
                Provider<String[]> getStringProvider();
                
                Consumer<Double> c();
            }
        """)

        expectSuccessfulValidation()
    }

    @Test
    fun `basic component - provide Object`() {
        givenJavaSource("test.MyModule", """
            import com.yandex.daggerlite.Provides;
            import com.yandex.daggerlite.Module;

            @Module
            public interface MyModule {
                @Provides
                static Object provides() {
                    return "object";
                }
            }
        """.trimIndent())

        givenJavaSource("test.TestComponent", """
            import com.yandex.daggerlite.Component;
            
            @Component(modules = MyModule.class)
            public interface TestComponent {
                Object get();
            }
        """.trimIndent())

        expectSuccessfulValidation()
    }

    @Test
    fun `java component interface extends kotlin one with properties`() {
        givenPrecompiledModule(SourceSet {
            givenKotlinSource("mod.Precompiled", """
                import com.yandex.daggerlite.*
                import javax.inject.*

                class SomeClass @Inject constructor()
                interface KotlinInterface {
                    val someClass: SomeClass
                }
            """.trimIndent())
        })
        givenJavaSource("test.TestComponent", """
            import com.yandex.daggerlite.Component;
            import mod.KotlinInterface;
            @Component
            public interface TestComponent extends KotlinInterface {
                
            }
        """.trimIndent())

        expectSuccessfulValidation()
    }

    @Test
    fun `basic members inject`() {
        givenJavaSource("test.ClassA", """
            import javax.inject.Inject;
            public class ClassA { public @Inject ClassA() {} }
        """.trimIndent())
        givenJavaSource("test.ClassB", """
            import javax.inject.Inject;
            public class ClassB { public @Inject ClassB() {} }
        """.trimIndent())
        givenJavaSource("test.MyModule", """
            import com.yandex.daggerlite.Module;
            import com.yandex.daggerlite.Binds;
            import javax.inject.Named;
            
            @Module
            public interface MyModule {
                @Named("hello") @Binds ClassA classAHello(ClassA i);
                @Named("bye") @Binds ClassA classABye(ClassA i);
            }
        """.trimIndent())
        givenJavaSource("test.Foo", """
            import javax.inject.Inject;
            import javax.inject.Named;

            public class Foo {
                @Inject @Named("hello")
                public ClassA helloA;
                
                private ClassA bye;
                private ClassB b;
                
                @Inject
                public void setClassB(ClassB classB) { b = classB; }
            
                @Inject @Named("bye")
                public void setClassA(ClassA classA) { bye = classA; }
            }
        """.trimIndent())
        givenJavaSource("test.TestCase", """
            import com.yandex.daggerlite.Component;
            
            @Component(modules = {MyModule.class})
            interface TestComponent {
                void injectFoo(Foo foo);
            }
        """.trimIndent())
        givenKotlinSource("test.TestCase", """
            fun test() {
                val c = com.yandex.daggerlite.Dagger.create(TestComponent::class.java)
                c.injectFoo(Foo())
            }
        """.trimIndent())

        expectSuccessfulValidation()
    }

    @Test
    fun `trivially constructable module`() {
        givenJavaSource("test.MyModule", """
            import com.yandex.daggerlite.Module;
            import com.yandex.daggerlite.Provides;
            
            @Module
            public class MyModule {
                private final Object mObj = new Object();
                @Provides
                public Object provides() { return mObj; }
            }
        """.trimIndent())

        givenJavaSource("test.MyComponent", """
            import com.yandex.daggerlite.Component;

            @Component(modules = MyModule.class)
            interface MyComponent {
                Object get();
            }
        """.trimIndent())

        expectSuccessfulValidation()
    }

    @Test
    fun `type parameters and multi-bindings`() {
        givenPrecompiledModule(SourceSet {
            givenKotlinSource("test.Deferred", """
                import javax.inject.*
                class Deferred<out T> @Inject constructor(val provider: Provider<out T>)
            """.trimIndent())
        })
        givenJavaSource("test.MyModule", """
            import java.util.Collection;
            import java.util.Collections;
            import javax.inject.Provider;
            import javax.inject.Singleton;
            import com.yandex.daggerlite.Binds;
            import com.yandex.daggerlite.Provides;
            import com.yandex.daggerlite.Module;
            import com.yandex.daggerlite.IntoList;

            @Module
            public interface MyModule {
                @IntoList @Binds Deferred<? extends MySpecificDeferredEvent> foo1(Deferred<MyClass1> i);
                @IntoList @Binds Deferred<? extends MySpecificDeferredEvent> foo2(Deferred<MyClass2> i);
                @IntoList @Binds Deferred<? extends MySpecificDeferredEvent> foo3(Deferred<MyClass3> i);
                @IntoList @Binds Deferred<? extends MySpecificDeferredEvent> foo4();
                @IntoList @Provides static Deferred<? extends MySpecificDeferredEvent> foo5(Provider<MyClass3> p) {
                    return new Deferred<>(p);
                }
                @IntoList(flatten = true) @Singleton
                @Provides static Collection<Deferred<? extends MySpecificDeferredEvent>> collection1() {
                    return Collections.emptyList();
                }
                @IntoList(flatten = true)
                @Provides static Collection<Deferred<? extends MySpecificDeferredEvent>> collection2() {
                    return Collections.emptyList();
                }
            }
        """.trimIndent())
        givenJavaSource("test.MyClass1", """
            public class MyClass1 implements MySpecificDeferredEvent { public @javax.inject.Inject MyClass1 () {} }
        """.trimIndent())
        givenJavaSource("test.MyClass2", """
            @javax.inject.Singleton 
            public class MyClass2 implements MySpecificDeferredEvent { public @javax.inject.Inject MyClass2 () {} }
        """.trimIndent())
        givenJavaSource("test.MyClass3", """
            public class MyClass3 implements MySpecificDeferredEvent { public @javax.inject.Inject MyClass3 () {} }
        """.trimIndent())
//        givenJavaSource("test.Deferred", """
//            import javax.inject.Provider;
//            public class Deferred<T> { public @javax.inject.Inject Deferred (Provider<T> provider) {} }
//        """.trimIndent())

        givenJavaSource("test.TestCase", """
            import java.util.Collections;
            import java.util.Collection;
            import java.util.List;
            import javax.inject.Provider;
            import javax.inject.Inject;
            import javax.inject.Singleton;
            import com.yandex.daggerlite.Component;
    
            interface MySpecificDeferredEvent {}

            @Singleton
            @Component(modules = MyModule.class)
            interface MyComponent {
                List<Deferred<? extends MySpecificDeferredEvent>> deferred();
                Provider<List<Deferred<? extends MySpecificDeferredEvent>>> deferredProvider();
            }
        """.trimIndent())

        givenKotlinSource("test.TestCase", """
            fun test() {
                val c = com.yandex.daggerlite.Dagger.create(MyComponent::class.java)
                c.deferred();
                c.deferredProvider().get();
            }
        """.trimIndent())

        expectSuccessfulValidation()
    }

    @Test
    fun `creator inputs are null-checked`() {
        givenJavaSource("test.MyComponentBase", """
            import com.yandex.daggerlite.BindsInstance;
            
            public interface MyComponentBase {
                char getChar();
                double getDouble();
                int getInt();
                long getLong();
                interface Builder<T extends MyComponentBase> {
                    @BindsInstance void setChar(char c);
                    @BindsInstance void setDouble(Double d);
                    T create(@BindsInstance int i1, @BindsInstance long i2);
                }
            }
        """.trimIndent())
        givenKotlinSource("test.TestCase", """
            import com.yandex.daggerlite.*

            @Component interface MyComponent : MyComponentBase {
                @Component.Builder interface Builder : MyComponentBase.Builder<MyComponent>
            }

            fun test() {
                fun builder() = Dagger.builder(MyComponent.Builder::class.java)
                // Creates normally
                builder().run { 
                    setChar('A')
                    setDouble(0.0)
                    create(1, 2L)
                }
                // Explicit null
                builder().run {
                    setChar('A')
                    try {
                        // Implementations are free to throw on either setter or creation invocation. 
                        setDouble(null)
                        create(1, 2L)
                        throw AssertionError("Fail expected, but not occurred")
                    } catch (e: IllegalStateException) { 
                        // Ok
                    }
                }
                // Input omitted
                builder().run { 
                    setDouble(0.0)
                    try {
                        create(1, 2L)
                        throw AssertionError("Fail expected, but not occurred")
                    } catch (e: IllegalStateException) {
                        // Ok
                    }
                }
            }
        """.trimIndent())

        expectSuccessfulValidation()
    }

    @Test
    fun `provision results are null-checked`() {
        givenJavaSource("test.MyModule", """
            @com.yandex.daggerlite.Module
            public interface MyModule {
                @com.yandex.daggerlite.Provides
                static Integer provideNullInt() {
                    return null;
                }
            }
        """.trimIndent())
        givenKotlinSource("test.TestCase", """
            import com.yandex.daggerlite.*

            @Component(modules = [MyModule::class])
            interface TestComponent {
                val integer: Int
            }

            fun test() {
                val c = Dagger.create(TestComponent::class.java)
                try { 
                    c.integer
                    throw AssertionError("Fail expected, but not occurred")
                } catch (e: IllegalStateException) {
                    // Ok
                }
            }
        """.trimIndent())

        expectSuccessfulValidation()
    }

    @Test
    fun `basic assisted inject`() {
        givenJavaSource("test.BarFactory", """
            import com.yandex.daggerlite.AssistedFactory;
            import com.yandex.daggerlite.Assisted;
            @AssistedFactory
            public interface BarFactory {
                Bar buildBar(@Assisted("c2") int count2, @Assisted("c1") int count1, String value);
            }
        """.trimIndent())
        givenJavaSource("test.FooFactory", """
            import com.yandex.daggerlite.AssistedFactory;
            import com.yandex.daggerlite.Assisted;
            @AssistedFactory
            public interface FooFactory {
                Foo createFoo(@Assisted("c1") int count1, @Assisted("c2") int count2, String value);
            }
        """.trimIndent())
        givenJavaSource("test.Foo", """
            import com.yandex.daggerlite.AssistedInject;
            import com.yandex.daggerlite.Assisted;
            
            public class Foo { 
                public final Bar bar;
                @AssistedInject public Foo(
                    @Assisted("c2") int c2,
                    BarFactory factory,
                    @Assisted("c1") int c1,
                    @Assisted String v
                ) {
                    bar = factory.buildBar(c2, c1, v);
                }
            }
        """.trimIndent())
        givenJavaSource("test.Bar", """
            import com.yandex.daggerlite.AssistedInject;
            import com.yandex.daggerlite.Assisted;            

            public class Bar { 
                public final int c1;
                public final int c2;
                public final String v;
                @AssistedInject public Bar(
                    @Assisted("c1") int c1,
                    @Assisted("c2") int c2,
                    @Assisted String v
                ) {
                    this.c1 = c1;
                    this.c2 = c2;
                    this.v = v;
                }
            }
        """.trimIndent())

        givenKotlinSource("test.TestCase", """
            import com.yandex.daggerlite.*
            
            @Module(subcomponents = [SubComponent::class])
            interface TestModule

            @Component(modules = [TestModule::class])
            interface TestComponent {
                fun fooFactory(): FooFactory 
            }
            
            fun test() {
                val c: TestComponent = Dagger.create(TestComponent::class.java)
                val f = c.fooFactory().createFoo(1, 2, "hello")
                assert(f.bar.c1 == 1)
                assert(f.bar.c2 == 2)
                assert(f.bar.v == "hello")
            }
        """.trimIndent())
        givenKotlinSource("test.SubComponent", """
            import com.yandex.daggerlite.*
            @Component(isRoot = false)
            interface SubComponent {
                fun fooFactory(): FooFactory
                @Component.Builder interface Builder { fun create(): SubComponent }
            }
        """.trimIndent())

        expectSuccessfulValidation()
    }

    @Test
    fun `component inherits the same method from multiple interfaces`() {
        givenJavaSource("test.ClassA", """
            import javax.inject.Inject;
            public class ClassA { @Inject public ClassA() {} }
        """.trimIndent())
        givenJavaSource("test.MyDependencies0", """
            public interface MyDependencies0 {
                ClassA classA();
            }
        """.trimIndent())
        givenKotlinSource("test.TestCase", """
            import com.yandex.daggerlite.*

            interface MyDependencies1 { fun classA(): ClassA }
            interface MyDependencies2 { fun classA(): ClassA }
            @Component interface MyComponent : MyDependencies0, MyDependencies1, MyDependencies2
        """.trimIndent())

        expectSuccessfulValidation()
    }
}

