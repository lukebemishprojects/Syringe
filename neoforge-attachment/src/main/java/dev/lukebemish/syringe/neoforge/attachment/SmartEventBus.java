package dev.lukebemish.syringe.neoforge.attachment;

import com.google.common.collect.MapMaker;
import net.jodah.typetools.TypeResolver;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.IModBusEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

class SmartEventBus implements IEventBus {
    private static final Logger LOGGER = LogManager.getLogger();

    private static final Map<Class<?>, Function<?, ?>> MOD_WRAPPERS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Function<?, ?>> MOD_STATIC_WRAPPERS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Function<?, ?>> GAME_WRAPPERS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Function<?, ?>> GAME_STATIC_WRAPPERS = new ConcurrentHashMap<>();

    private final IEventBus modBus;
    private final IEventBus gameBus;

    private final Map<Object, Object> registeredModWrappers = new MapMaker().weakKeys().makeMap();
    private final Map<Object, Object> registeredGameWrappers = new MapMaker().weakKeys().makeMap();

    public SmartEventBus(IEventBus modBus, IEventBus gameBus) {
        this.modBus = modBus;
        this.gameBus = gameBus;
    }

    @SuppressWarnings("unchecked")
    @Override
    public void register(Object target) {
        Object modTarget;
        Object gameTarget;
        boolean[] hasListeners = {true, true};
        if (!(target instanceof Class<?> targetClass)) {
            var modWrapper = (Function<Object, Object>) MOD_WRAPPERS.computeIfAbsent(target.getClass(), clazz -> BusClassWrapper.makeWrapper(clazz, false, EventBusSubscriber.Bus.MOD, () -> hasListeners[0] = false));
            var gameWrapper = (Function<Object, Object>) GAME_WRAPPERS.computeIfAbsent(target.getClass(), clazz -> BusClassWrapper.makeWrapper(clazz, false, EventBusSubscriber.Bus.GAME, () -> hasListeners[1] = false));
            modTarget = modWrapper.apply(target);
            gameTarget = gameWrapper.apply(target);
        } else {
            var modWrapper = (Function<@Nullable Object, Object>) MOD_STATIC_WRAPPERS.computeIfAbsent(target.getClass(), clazz -> BusClassWrapper.makeWrapper(clazz, true, EventBusSubscriber.Bus.MOD, () -> hasListeners[0] = false));
            var gameWrapper = (Function<@Nullable Object, Object>) GAME_STATIC_WRAPPERS.computeIfAbsent(target.getClass(), clazz -> BusClassWrapper.makeWrapper(clazz, true, EventBusSubscriber.Bus.GAME, () -> hasListeners[1] = false));
            modTarget = modWrapper.apply(null);
            gameTarget = gameWrapper.apply(null);
        }
        if (hasListeners[0] && !hasListeners[1]) {
            modBus.register(modTarget);
            registeredModWrappers.put(target, modTarget);
        } else if (hasListeners[1] && !hasListeners[0]) {
            gameBus.register(gameTarget);
            registeredGameWrappers.put(target, gameTarget);
        } else {
            modBus.register(modTarget);
            gameBus.register(gameTarget);
            registeredModWrappers.put(target, modTarget);
            registeredGameWrappers.put(target, gameTarget);
        }
    }

    @SuppressWarnings("unchecked")
    private <T extends Event> Class<T> getEventClass(Consumer<T> consumer) {
        final Class<T> eventClass = (Class<T>) TypeResolver.resolveRawArgument(Consumer.class, consumer.getClass());
        if ((Class<?>)eventClass == TypeResolver.Unknown.class) {
            LOGGER.error("Failed to resolve handler for \"{}\"", consumer);
            throw new IllegalStateException("Failed to resolve consumer event type: " + consumer);
        }
        return eventClass;
    }

    private <T extends Event> boolean isModListener(Consumer<T> consumer) {
        var type = getEventClass(consumer);
        return IModBusEvent.class.isAssignableFrom(type);
    }

    private <T extends Event> boolean isModType(Class<T> type) {
        return IModBusEvent.class.isAssignableFrom(type);
    }

    @Override
    public <T extends Event> void addListener(Consumer<T> consumer) {
        if (isModListener(consumer)) {
            modBus.addListener(consumer);
        } else {
            gameBus.addListener(consumer);
        }
    }

    @Override
    public <T extends Event> void addListener(Class<T> eventType, Consumer<T> consumer) {
        if (isModType(eventType)) {
            modBus.addListener(eventType, consumer);
        } else {
            gameBus.addListener(eventType, consumer);
        }
    }

    @Override
    public <T extends Event> void addListener(EventPriority priority, Consumer<T> consumer) {
        if (isModListener(consumer)) {
            modBus.addListener(priority, consumer);
        } else {
            gameBus.addListener(priority, consumer);
        }
    }

    @Override
    public <T extends Event> void addListener(EventPriority priority, Class<T> eventType, Consumer<T> consumer) {
        if (isModType(eventType)) {
            modBus.addListener(priority, eventType, consumer);
        } else {
            gameBus.addListener(priority, eventType, consumer);
        }
    }

    @Override
    public <T extends Event> void addListener(EventPriority priority, boolean receiveCanceled, Consumer<T> consumer) {
        if (isModListener(consumer)) {
            modBus.addListener(priority, receiveCanceled, consumer);
        } else {
            gameBus.addListener(priority, receiveCanceled, consumer);
        }
    }

    @Override
    public <T extends Event> void addListener(EventPriority priority, boolean receiveCanceled, Class<T> eventType, Consumer<T> consumer) {
        if (isModType(eventType)) {
            modBus.addListener(priority, receiveCanceled, eventType, consumer);
        } else {
            gameBus.addListener(priority, receiveCanceled, eventType, consumer);
        }
    }

    @Override
    public <T extends Event> void addListener(boolean receiveCanceled, Consumer<T> consumer) {
        if (isModListener(consumer)) {
            modBus.addListener(receiveCanceled, consumer);
        } else {
            gameBus.addListener(receiveCanceled, consumer);
        }
    }

    @Override
    public <T extends Event> void addListener(boolean receiveCanceled, Class<T> eventType, Consumer<T> consumer) {
        if (isModType(eventType)) {
            modBus.addListener(receiveCanceled, eventType, consumer);
        } else {
            gameBus.addListener(receiveCanceled, eventType, consumer);
        }
    }

    @Override
    public void unregister(Object object) {
        var modWrapper = registeredModWrappers.remove(object);
        var gameWrapper = registeredGameWrappers.remove(object);
        if (modWrapper != null) {
            modBus.unregister(modWrapper);
        }
        if (gameWrapper != null) {
            gameBus.unregister(gameWrapper);
        }
        modBus.unregister(object);
        gameBus.unregister(object);
    }

    @Override
    public <T extends Event> T post(T event) {
        if (event instanceof IModBusEvent) {
            return modBus.post(event);
        } else {
            return gameBus.post(event);
        }
    }

    @Override
    public <T extends Event> T post(EventPriority phase, T event) {
        if (event instanceof IModBusEvent) {
            return modBus.post(phase, event);
        } else {
            return gameBus.post(phase, event);
        }
    }

    @Override
    public void start() {
        modBus.start();
        gameBus.start();
    }
}
