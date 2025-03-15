package dev.lukebemish.syringe.test;

import dev.lukebemish.syringe.ObjectFactory;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestAbstractInject {
    public static class Bar {}

    public abstract static class Foo {
        @Inject protected abstract Bar getBar();
        @Inject protected abstract Provider<Bar> getBarProvider();
    }

    @Test
    void testAbstractInject() {
        var foo = ObjectFactory.create().instance(Foo.class);
        Assertions.assertNotNull(foo.getBar());
        var provider = foo.getBarProvider();
        Assertions.assertNotNull(provider);
        Assertions.assertNotNull(provider.get());
    }
}
