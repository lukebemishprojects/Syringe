package dev.lukebemish.syringe.test;

import dev.lukebemish.syringe.ObjectFactory;
import dev.lukebemish.syringe.annotations.Inject;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

import java.util.Objects;

@Mod("syringe_testmod")
public abstract class TestMod {
    @Inject
    protected abstract ObjectFactory getObjectFactory();

    @Inject
    protected abstract IEventBus getEventBus();

    @Inject
    public TestMod(ModContainer modContainer) {
        Objects.requireNonNull(modContainer);
        Objects.requireNonNull(getEventBus());
        Objects.requireNonNull(getObjectFactory());

        var innerThingy = getObjectFactory().newInstance(InnerThingy.class, "innerThingy");
        Objects.requireNonNull(innerThingy);
        if (!innerThingy.name.equals("innerThingy")) {
            throw new IllegalStateException("InnerThingy name is not 'innerThingy'");
        }

        System.out.println("Syringe test mod successfully loaded");
    }

    public abstract static class InnerThingy {
        private final String name;

        @Inject
        protected abstract IEventBus bus();

        @Inject
        public InnerThingy(String name, ObjectFactory factory) {
            Objects.requireNonNull(factory);
            Objects.requireNonNull(bus());
            this.name = name;
        }
    }
}
