package dev.lukebemish.syringe.neoforge.attachment;

import com.google.common.collect.MapMaker;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

final class SuperclassAllowingEventBus implements IEventBus {
    private static final Map<Class<?>, Function<?, ?>> WRAPPERS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Function<?, ?>> STATIC_WRAPPERS = new ConcurrentHashMap<>();

    private final IEventBus delegate;
    private final Map<Object, Object> registeredWrappers = new MapMaker().weakKeys().makeMap();

    public SuperclassAllowingEventBus(IEventBus delegate) {
        this.delegate = delegate;
    }

    @SuppressWarnings("unchecked")
    @Override
    public void register(Object target) {
        if (!(target instanceof Class<?> targetClass)) {
            var wrapper = (Function<Object, Object>) WRAPPERS.computeIfAbsent(target.getClass(), clazz -> BusClassWrapper.makeWrapper(clazz, false, null, () -> {}));
            var newTarget = wrapper.apply(target);
            registeredWrappers.put(target, newTarget);
            target = newTarget;
        } else {
            var wrapper = (Function<@Nullable Object, Object>) STATIC_WRAPPERS.computeIfAbsent(targetClass, clazz -> BusClassWrapper.makeWrapper(clazz, true, null, () -> {}));
            var newTarget = wrapper.apply(null);
            registeredWrappers.put(targetClass, newTarget);
            target = newTarget;
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
        var wrapper = registeredWrappers.remove(object);
        if (wrapper != null) {
            delegate.unregister(wrapper);
        }
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
