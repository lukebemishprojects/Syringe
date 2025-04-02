package dev.lukebemish.syringe.neoforge.test;

import dev.lukebemish.syringe.Assisted;
import dev.lukebemish.syringe.ObjectFactory;
import dev.lukebemish.syringe.Provides;
import dev.lukebemish.syringe.common.ModScope;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.Objects;

@Mod("syringe_testmod")
public abstract class TestMod {
    @Inject
    protected abstract IEventBus eventBus();

    @Inject
    protected abstract ObjectFactory objectFactory();

    @Inject @Named("minecraft:item")
    protected abstract DeferredRegister<Item> itemRegister();

    @Inject
    public TestMod(ModContainer modContainer) {
        Objects.requireNonNull(modContainer);
        Objects.requireNonNull(eventBus());
        Objects.requireNonNull(objectFactory());

        var innerThingy = objectFactory().instance(InnerThingy.class, "innerThingy", this);
        Objects.requireNonNull(innerThingy);
        if (!innerThingy.name.equals("innerThingy")) {
            throw new IllegalStateException("InnerThingy name is not 'innerThingy'");
        }

        var scopedService = objectFactory().instance(ScopedService.class);
        var scopedService2 = objectFactory().instance(ScopedService.class);
        Objects.requireNonNull(scopedService);
        if (scopedService != scopedService2) {
            throw new IllegalStateException("Scoped service instances are not the same, despite it being mod-scoped");
        }

        eventBus().register(innerThingy);

        eventBus().register(objectFactory().instance(GameBusListeners.class));

        System.out.println("Syringe test mod successfully loaded");

        itemRegister().register("test_item", () ->
            new Item(new Item.Properties().setId(ResourceKey.create(
                Registries.ITEM,
                ResourceLocation.fromNamespaceAndPath(
                    modContainer.getModId(),
                    "test_item"
                )
            )))
        );
    }

    @ModScope
    public interface ScopedService {
        String name();

        @Provides
        static ScopedService provideScopedService(ObjectFactory factory) {
            return factory.instance(ScopedServiceImpl.class, "scopedService");
        }
    }

    public abstract static class ScopedServiceImpl implements ScopedService {
        @Inject
        protected abstract ObjectFactory getObjectFactory();

        @Override
        public String name() {
            return name;
        }

        private final String name;

        @Inject
        public ScopedServiceImpl(@Assisted String name) {
            this.name = name;
            Objects.requireNonNull(getObjectFactory());
            System.out.println("Made scoped service with name: " + name);
        }
    }

    public abstract static class GameBusListeners {
        @Inject
        protected abstract ScopedService scopedService();

        @SubscribeEvent
        public void gameShuttingDown(GameShuttingDownEvent event) {
            Objects.requireNonNull(scopedService());
            System.out.println("Syringe test mod game bus event fired!");
        }
    }

    public abstract static class InnerThingy {
        private final String name;
        private final TestMod toCheck;

        @Inject
        protected abstract IEventBus bus();

        @Inject
        protected abstract ScopedService scopedService();

        @Inject
        public InnerThingy(@Assisted String name, @Assisted TestMod toCheck, ObjectFactory factory) {
            Objects.requireNonNull(factory);
            Objects.requireNonNull(bus());
            Objects.requireNonNull(scopedService());
            Objects.requireNonNull(name);
            this.name = name;
            this.toCheck = toCheck;
        }

        @Inject
        protected abstract TestMod modInstance();

        @Inject
        protected abstract ModContainer modContainer();

        @SubscribeEvent
        public void commonSetup(FMLCommonSetupEvent event) {
            Objects.requireNonNull(modInstance());
            if (modInstance() != toCheck) {
                throw new IllegalStateException("@Mod instance not properly scoped");
            }

            System.out.println("Found registered item: "+BuiltInRegistries.ITEM.getValue(ResourceLocation.fromNamespaceAndPath(modContainer().getModId(), "test_item")));

            System.out.println("InnerThingy common setup event");
        }
    }
}
