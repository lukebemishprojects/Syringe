package dev.lukebemish.syringe;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.stream.Stream;

public record EvaluatedType(Class<?> rawType, List<EvaluatedType> typeParameters) {
    public EvaluatedType {
        if (rawType.getTypeParameters().length != typeParameters.size() && !typeParameters.isEmpty()) {
            throw new IllegalArgumentException("Type "+rawType+" should either be raw, with no type parameters, or have "+rawType.getTypeParameters().length+" type parameters, but "+typeParameters.size()+" were provided");
        }
    }

    public static EvaluatedType of(Type type) {
        if (type instanceof Class<?> clazz) {
            return new EvaluatedType(clazz, List.of());
        } else if (type instanceof ParameterizedType parameterizedType) {
            var rawType = parameterizedType.getRawType();
            if (rawType instanceof Class<?> rawTypeClass) {
                var typeParameters = Stream.of(parameterizedType.getActualTypeArguments())
                    .map(EvaluatedType::of)
                    .toList();
                return new EvaluatedType(rawTypeClass, typeParameters);
            }
        }
        throw new IllegalArgumentException("Cannot evaluate type: " + type);
    }

    @Override
    public String toString() {
        var builder = new StringBuilder(rawType.getName());
        if (!typeParameters.isEmpty()) {
            builder.append('<');
            for (var i = 0; i < typeParameters.size(); i++) {
                builder.append(typeParameters.get(i));
                if (i < typeParameters.size() - 1) {
                    builder.append(", ");
                }
            }
            builder.append('>');
        }
        return builder.toString();
    }
}
