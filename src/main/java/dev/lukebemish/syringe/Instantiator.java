package dev.lukebemish.syringe;

import java.util.List;

public interface Instantiator<T> {
    T newInstance(List<EvaluatedType> typeParameters, Object... args);
}
