package dev.lukebemish.syringe.fabric;

import dev.lukebemish.syringe.Instantiator;
import dev.lukebemish.syringe.ObjectFactory;
import net.fabricmc.loader.api.LanguageAdapter;
import net.fabricmc.loader.api.LanguageAdapterException;
import net.fabricmc.loader.api.ModContainer;

public class SyringeLanguageAdapter implements LanguageAdapter {
    private final ObjectFactory syringeObjectFactory = ObjectFactory.create(Instantiator.builder().build());

    @SuppressWarnings("unchecked")
    @Override
    public <T> T create(ModContainer mod, String value, Class<T> type) throws LanguageAdapterException {
        try {
            var clazz = Class.forName(value);
            if (!type.isAssignableFrom(clazz)) {
                throw new LanguageAdapterException("Class " + value + " is not of type " + type.getName());
            }
            var component = ModComponentBuilder.handleFor(mod.getMetadata().getId(), syringeObjectFactory);
            return (T) component.instance(clazz);
        } catch (Exception e) {
            throw new LanguageAdapterException(e);
        }
    }
}
