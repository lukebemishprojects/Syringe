package dev.lukebemish.syringe.test;

import dev.lukebemish.syringe.ObjectFactory;
import dev.lukebemish.syringe.annotations.Inject;
import dev.lukebemish.syringe.annotations.Label;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.Objects;

@Mod("syringe_testmod")
public abstract class TestMod {
    @Inject
    protected abstract IEventBus getEventBus();

    @Label("minecraft:item")
    protected abstract DeferredRegister<Item> getItemRegister();

    @Inject
    public TestMod(ModContainer modContainer, ObjectFactory objectFactory) {
        Objects.requireNonNull(modContainer);
        Objects.requireNonNull(getEventBus());
        Objects.requireNonNull(objectFactory);

        var options = ObjectFactory.Configuration.create();
        options.bindService(ScopedService.class, ScopedService.class, "scopedService");
        var scopedObjectFactory = objectFactory.newObjectFactory(options);

        var innerThingy = scopedObjectFactory.newInstance(InnerThingy.class, "innerThingy");
        Objects.requireNonNull(innerThingy);
        if (!innerThingy.name.equals("innerThingy")) {
            throw new IllegalStateException("InnerThingy name is not 'innerThingy'");
        }

        getEventBus().register(innerThingy);

        getItemRegister().register("testitem", () -> new Item(new Item.Properties()));

        System.out.println("Syringe test mod successfully loaded");
    }

    public abstract static class ScopedService {
        @Inject
        protected abstract ObjectFactory getObjectFactory();

        @Inject
        public ScopedService(String name) {
            System.out.println("Made scoped service with name: " + name);
        }
    }

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
