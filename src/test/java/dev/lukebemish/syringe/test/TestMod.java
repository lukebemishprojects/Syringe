package dev.lukebemish.syringe.test;

import dev.lukebemish.syringe.ObjectFactory;
import dev.lukebemish.syringe.annotations.Inject;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

import java.util.Objects;

@Mod("syringe_testmod")
public abstract class TestMod {
    private final ObjectFactory objectFactory;

    @Inject
    protected abstract IEventBus getEventBus();

    @Inject
    public TestMod(ModContainer modContainer, ObjectFactory objectFactory) {
        Objects.requireNonNull(modContainer);
        Objects.requireNonNull(getEventBus());
        Objects.requireNonNull(objectFactory);

        var options = ObjectFactory.Options.create();
        options.bindService(ScopedService.class, new ScopedService());
        this.objectFactory = objectFactory.newObjectFactory(options);

        var innerThingy = this.objectFactory.newInstance(InnerThingy.class, "innerThingy");
        Objects.requireNonNull(innerThingy);
        if (!innerThingy.name.equals("innerThingy")) {
            throw new IllegalStateException("InnerThingy name is not 'innerThingy'");
        }

        getEventBus().register(innerThingy);

        System.out.println("Syringe test mod successfully loaded");
    }

    public static class ScopedService {}

    public abstract static class InnerThingy {
        private final String name;

        @Inject
        protected abstract IEventBus bus();

        @Inject
        protected abstract ScopedService getScopedService();

        @Inject
        public InnerThingy(String name, ObjectFactory factory) {
            Objects.requireNonNull(factory);
            Objects.requireNonNull(bus());
            Objects.requireNonNull(getScopedService());
            this.name = name;
        }

        @Inject
        protected abstract TestMod getModInstance();

        @SubscribeEvent
        public void commonSetup(FMLCommonSetupEvent event) {
            Objects.requireNonNull(getModInstance());
            System.out.println("InnerThingy common setup event");
        }
    }
}
