package dev.lukebemish.syringe;

import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

record SuperclassAllowingEventBus(IEventBus delegate) implements IEventBus {
    private static final Map<Class<?>, Function<?, ?>> WRAPPERS = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    @Override
    public void register(Object target) {
        if (!(target instanceof Class<?>)) {
            var wrapper = (Function<Object, Object>) WRAPPERS.computeIfAbsent(target.getClass(), InjectedImplementation::wrap);
            target = wrapper.apply(target);
        }
        delegate.register(target);
    }

    @Override
    public <T extends Event> void addListener(Consumer<T> consumer) {
        delegate.addListener(consumer);
    }

    @Override
    public <T extends Event> void addListener(Class<T> eventType, Consumer<T> consumer) {
        delegate.addListener(eventType, consumer);
    }

    @Override
    public <T extends Event> void addListener(EventPriority priority, Consumer<T> consumer) {
        delegate.addListener(priority, consumer);
    }

    @Override
    public <T extends Event> void addListener(EventPriority priority, Class<T> eventType, Consumer<T> consumer) {
        delegate.addListener(priority, eventType, consumer);
    }

    @Override
    public <T extends Event> void addListener(EventPriority priority, boolean receiveCanceled, Consumer<T> consumer) {
        delegate.addListener(priority, receiveCanceled, consumer);
    }

    @Override
    public <T extends Event> void addListener(EventPriority priority, boolean receiveCanceled, Class<T> eventType, Consumer<T> consumer) {
        delegate.addListener(priority, receiveCanceled, eventType, consumer);
    }

    @Override
    public <T extends Event> void addListener(boolean receiveCanceled, Consumer<T> consumer) {
        delegate.addListener(receiveCanceled, consumer);
    }

    @Override
    public <T extends Event> void addListener(boolean receiveCanceled, Class<T> eventType, Consumer<T> consumer) {
        delegate.addListener(receiveCanceled, eventType, consumer);
    }

    @Override
    public void unregister(Object object) {
        delegate.unregister(object);
    }

    @Override
    public <T extends Event> T post(T event) {
        return delegate.post(event);
    }

    @Override
    public <T extends Event> T post(EventPriority phase, T event) {
        return delegate.post(phase, event);
    }

    @Override
    public void start() {
        delegate.start();
    }
}
