package dev.lukebemish.syringe.test;

import com.google.auto.service.AutoService;
import dev.lukebemish.syringe.InstantiatorDiscoverer;
import dev.lukebemish.syringe.ObjectFactory;
import dev.lukebemish.syringe.annotations.ModScope;
import net.neoforged.neoforge.registries.DeferredRegister;

@AutoService(InstantiatorDiscoverer.class)
@ModScope
public class SharedDiscoverer implements InstantiatorDiscoverer {
    @Override
    public void configure(ObjectFactory.Configuration configuration) {
        configuration.bindInstantiator(DeferredRegister.class, DeferredRegisterInstantiator.class);
    }
}
