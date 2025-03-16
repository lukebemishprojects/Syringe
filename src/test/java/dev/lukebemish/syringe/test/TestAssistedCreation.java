package dev.lukebemish.syringe.test;

import dev.lukebemish.syringe.Assisted;
import dev.lukebemish.syringe.ObjectFactory;
import dev.lukebemish.syringe.Provides;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestAssistedCreation {
    public record Foo() {}
    public record Bar() {}

    public record WithConstructor(Bar bar, @Assisted String first, Foo foo, @Assisted String second) {
        @Inject public WithConstructor {

        }
    }

    public record WithProvider(Bar bar, @Assisted String first, Foo foo, @Assisted String second) {
        @Provides public static WithProvider of(Bar bar, @Assisted String first, Foo foo, @Assisted String second) {
            return new WithProvider(bar, first, foo, second);
        }
    }

    @Test
    void testWithConstructor() {
        WithConstructor withConstructor = ObjectFactory.create().instance(WithConstructor.class, "string1", "string2");
        Assertions.assertNotNull(withConstructor);
        Assertions.assertNotNull(withConstructor.bar());
        Assertions.assertNotNull(withConstructor.foo());
        Assertions.assertEquals("string1", withConstructor.first());
        Assertions.assertEquals("string2", withConstructor.second());
    }

    @Test
    void testWithProvider() {
        WithProvider withProvider = ObjectFactory.create().instance(WithProvider.class, "string1", "string2");
        Assertions.assertNotNull(withProvider);
        Assertions.assertNotNull(withProvider.bar());
        Assertions.assertNotNull(withProvider.foo());
        Assertions.assertEquals("string1", withProvider.first());
        Assertions.assertEquals("string2", withProvider.second());
    }
}
