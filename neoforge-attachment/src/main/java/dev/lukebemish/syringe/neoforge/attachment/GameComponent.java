package dev.lukebemish.syringe.neoforge.attachment;

import dev.lukebemish.syringe.Component;
import dev.lukebemish.syringe.Instantiator;
import dev.lukebemish.syringe.ObjectFactory;
import dev.lukebemish.syringe.Provides;
import dev.lukebemish.syringe.neoforge.BusType;
import dev.lukebemish.syringe.neoforge.GameScope;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.common.NeoForge;

import java.lang.invoke.MethodHandles;

@Singleton
@Component(scopes = GameScope.class)
public final class GameComponent {
    private final ObjectFactory factory;
    private final IEventBus gameBus;

    @Inject
    public GameComponent(ObjectFactory rootFactory) {
        this.factory = rootFactory.within(this);
        this.gameBus = new SuperclassAllowingEventBus(NeoForge.EVENT_BUS);
    }

    @Provides @BusType(EventBusSubscriber.Bus.GAME)
    public IEventBus gameBus() {
        return gameBus;
    }

    private static final Instantiator instantiator = Instantiator.builder().lookup(MethodHandles.lookup()).build();

    static Instantiator gameLayerInstantiator() {
        return instantiator;
    }

    @Provides
    public Instantiator instantiator() {
        return instantiator;
    }

    @Provides
    public Dist dist() {
        return FMLLoader.getDist();
    }

    public ObjectFactory scopedObjectFactory() {
        return factory;
    }
}
