package dev.lukebemish.syringe.fabric.test;

import dev.lukebemish.syringe.Assisted;
import dev.lukebemish.syringe.ObjectFactory;
import dev.lukebemish.syringe.Provides;
import dev.lukebemish.syringe.common.ModScope;
import jakarta.inject.Inject;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.ModContainer;

import java.util.Objects;

@ModScope
public abstract class TestModInitializer implements ModInitializer {
    @Inject
    protected abstract ObjectFactory objectFactory();

    private final InnerThingy innerThingy;

    @Inject
    public TestModInitializer(ModContainer modContainer) {
        Objects.requireNonNull(modContainer);
        Objects.requireNonNull(objectFactory());

        this.innerThingy = objectFactory().instance(InnerThingy.class, "innerThingy", this);
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
    }

    @Override
    public void onInitialize() {
        innerThingy.setup();
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

    public abstract static class InnerThingy {
        private final String name;
        private final TestModInitializer toCheck;

        @Inject
        protected abstract ScopedService scopedService();

        @Inject
        public InnerThingy(@Assisted String name, @Assisted TestModInitializer toCheck, ObjectFactory factory) {
            Objects.requireNonNull(factory);
            Objects.requireNonNull(scopedService());
            Objects.requireNonNull(modContainer());
            Objects.requireNonNull(name);
            this.name = name;
            this.toCheck = toCheck;
        }

        @Inject
        protected abstract TestModInitializer modInstance();

        @Inject
        protected abstract ModContainer modContainer();

        public void setup() {
            Objects.requireNonNull(modInstance());
            if (modInstance() != toCheck) {
                throw new IllegalStateException("@Mod instance not properly scoped");
            }

            System.out.println("InnerThingy common setup event");
        }
    }
}
