package dev.lukebemish.syringe;

import java.lang.annotation.Annotation;
import java.util.Set;

record QualifiedType<T>(Class<T> type, Set<Annotation> qualifiers) {
}
