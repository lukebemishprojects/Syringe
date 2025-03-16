package dev.lukebemish.syringe.fml.test;

import dev.lukebemish.syringe.Assisted;
import dev.lukebemish.syringe.ObjectFactory;
import dev.lukebemish.syringe.Provides;
import dev.lukebemish.syringe.fml.Game;
import jakarta.inject.Inject;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;

import java.util.Objects;

@Mod("syringe_testmod")
public abstract class TestMod {
    @Inject
    protected abstract IEventBus modBus();

    @Inject @Game
    protected abstract IEventBus gameBus();

    @Inject
    protected abstract ObjectFactory objectFactory();

    @Inject
    public TestMod(ModContainer modContainer) {
        Objects.requireNonNull(modContainer);
        Objects.requireNonNull(modBus());
        Objects.requireNonNull(objectFactory());

        var innerThingy = objectFactory().instance(InnerThingy.class, "innerThingy", this);
        Objects.requireNonNull(innerThingy);
        if (!innerThingy.name.equals("innerThingy")) {
            throw new IllegalStateException("InnerThingy name is not 'innerThingy'");
        }

        modBus().register(innerThingy);

        gameBus().register(objectFactory().instance(GameBusListeners.class));

        System.out.println("Syringe test mod successfully loaded");
    }

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
        protected abstract TestMod testMod();

        @Inject
        protected abstract TestMod getModInstance();

        @SubscribeEvent
        public void commonSetup(FMLCommonSetupEvent event) {
            if (testMod() != toCheck) {
                throw new IllegalStateException("@Mod instance not properly scoped");
            }

            Objects.requireNonNull(getModInstance());
            System.out.println("InnerThingy common setup event");
        }
    }
}
