package dev.lukebemish.syringe.test;

import dev.lukebemish.syringe.ObjectFactory;
import dev.lukebemish.syringe.annotations.Inject;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod("syringe_testmod")
public abstract class TestMod {
    @Inject
    protected abstract ObjectFactory getObjectFactory();
    
    @Inject
    protected abstract IEventBus getEventBus();
    
    public TestMod() {
        // Do stuff with injected stuff here
    }
}
