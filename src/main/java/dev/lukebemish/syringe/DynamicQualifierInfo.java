package dev.lukebemish.syringe;

import java.lang.annotation.Annotation;
import java.util.Set;

record DynamicQualifierInfo<T>(
    ObjectProvider.Creator<T> creator,
    Set<Annotation> existingQualifiers,
    Set<Class<? extends Annotation>> dynamicQualifiers,
    Class<T> type
) {
    boolean matches(QualifiedType<?> qualifiedType) {
        if (!type.equals(qualifiedType.type())) {
            return false;
        }
        // This qualifier info must contain all qualifiers from the provided type, either as existing annotations or dynamic types
        for (Annotation qualifier : qualifiedType.qualifiers()) {
            if (!existingQualifiers.contains(qualifier) && !dynamicQualifiers.contains(qualifier.annotationType())) {
                return false;
            }
        }
        return true;
    }
}
