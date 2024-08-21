package dev.lukebemish.syringe.test;

import dev.lukebemish.syringe.EvaluatedType;
import dev.lukebemish.syringe.Instantiator;
import dev.lukebemish.syringe.annotations.Inject;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforgespi.language.IModInfo;

import java.util.List;

@SuppressWarnings("rawtypes")
public abstract class DeferredRegisterInstantiator implements Instantiator<DeferredRegister> {
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
}
