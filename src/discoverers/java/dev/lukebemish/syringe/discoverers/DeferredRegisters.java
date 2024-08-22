package dev.lukebemish.syringe.discoverers;

import com.google.auto.service.AutoService;
import dev.lukebemish.syringe.EvaluatedType;
import dev.lukebemish.syringe.InstantiatorDiscoverer;
import dev.lukebemish.syringe.ObjectFactory;
import dev.lukebemish.syringe.annotations.Inject;
import dev.lukebemish.syringe.annotations.ModScope;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforgespi.language.IModInfo;

import java.util.List;

@AutoService(InstantiatorDiscoverer.class)
@ModScope
public class DeferredRegisters implements InstantiatorDiscoverer {
    @Override
    public void configure(ObjectFactory.Configuration configuration) {
        configuration.bindInstantiator(DeferredRegister.class, Instantiator.class);
        configuration.bindInstantiator(DeferredRegister.Blocks.class, Instantiator.Blocks.class);
        configuration.bindInstantiator(DeferredRegister.Items.class, Instantiator.Items.class);
        configuration.bindInstantiator(DeferredRegister.DataComponents.class, Instantiator.DataComponents.class);
    }

    @SuppressWarnings("rawtypes")
    public abstract static class Instantiator implements dev.lukebemish.syringe.Instantiator<DeferredRegister> {
        @Inject
        protected abstract IModInfo getModInfo();
        @Inject
        protected abstract IEventBus getEventBus();

        @Override
        public DeferredRegister newInstance(List<EvaluatedType> typeParameters, Object... args) {
            if (args.length != 1) {
                throw new IllegalArgumentException("Expected 1 argument, got " + args.length);
            }
            var register = obtainKey(args);
            register.register(getEventBus());
            return register;
        }

        @SuppressWarnings("unchecked")
        private <T> DeferredRegister<T> obtainKey(Object[] args) {
            ResourceKey<? extends Registry<T>> registryKey;
            switch (args[0]) {
                case ResourceKey resourceKey when resourceKey.registry().equals(Registries.ROOT_REGISTRY_NAME) ->
                    registryKey = (ResourceKey<Registry<T>>) resourceKey;
                case ResourceLocation resourceLocation -> registryKey = ResourceKey.createRegistryKey(resourceLocation);
                case String string -> {
                    var resourceLocation = ResourceLocation.parse(string);
                    registryKey = ResourceKey.createRegistryKey(resourceLocation);
                }
                default ->
                    throw new IllegalArgumentException("Received " + args[0] + ", which cannot be converted into a registry key");
            }
            return DeferredRegister.create(registryKey, getModInfo().getNamespace());
        }

        public abstract static class Blocks implements dev.lukebemish.syringe.Instantiator<DeferredRegister.Blocks> {
            @Inject
            protected abstract IModInfo getModInfo();
            @Inject
            protected abstract IEventBus getEventBus();

            @Override
            public DeferredRegister.Blocks newInstance(List<EvaluatedType> typeParameters, Object... args) {
                var register = DeferredRegister.createBlocks(getModInfo().getNamespace());
                register.register(getEventBus());
                return register;
            }
        }

        public abstract static class Items implements dev.lukebemish.syringe.Instantiator<DeferredRegister.Items> {
            @Inject
            protected abstract IModInfo getModInfo();
            @Inject
            protected abstract IEventBus getEventBus();

            @Override
            public DeferredRegister.Items newInstance(List<EvaluatedType> typeParameters, Object... args) {
                var register = DeferredRegister.createItems(getModInfo().getNamespace());
                register.register(getEventBus());
                return register;
            }
        }

        public abstract static class DataComponents implements dev.lukebemish.syringe.Instantiator<DeferredRegister.DataComponents> {
            @Inject
            protected abstract IModInfo getModInfo();
            @Inject
            protected abstract IEventBus getEventBus();

            @Override
            public DeferredRegister.DataComponents newInstance(List<EvaluatedType> typeParameters, Object... args) {
                var register = DeferredRegister.createDataComponents(getModInfo().getNamespace());
                register.register(getEventBus());
                return register;
            }
        }
    }
}
