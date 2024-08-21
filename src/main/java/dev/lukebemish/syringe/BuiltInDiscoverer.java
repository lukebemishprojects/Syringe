package dev.lukebemish.syringe;

import com.google.auto.service.AutoService;
import dev.lukebemish.syringe.annotations.Inject;

import java.util.List;

@AutoService(InstantiatorDiscoverer.class)
public class BuiltInDiscoverer implements InstantiatorDiscoverer {
    @Override
    public void configure(ObjectFactory.Configuration configuration) {
        configuration.bindInstantiatorType(Provider.class, ProviderInstantiator.class);
    }

    @SuppressWarnings("rawtypes")
    public abstract static class ProviderInstantiator implements Instantiator<Provider> {
        @Inject
        protected abstract ObjectFactory getObjectFactory();

        @Override
        public Provider newInstance(List<EvaluatedType> typeParameters, Object... args) {
            if (typeParameters.isEmpty()) {
                throw new IllegalArgumentException("Provider must have a type parameter to be instantiated");
            }
            var type = typeParameters.getFirst();
            return new LazyProvider<>(() -> getObjectFactory().newInstance(type, args), new EvaluatedType(Provider.class, typeParameters));
        }
    }
}
