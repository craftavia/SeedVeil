package net.craftavia.seedveil.packet;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * Copies a record with its single component of a given type replaced. Keeps working when a server version adds or
 * removes record components; if there is no component of that type, or more than one, the record is returned as is.
 */
final class RecordCopier {

    private record Shape(RecordComponent[] components, Constructor<?> constructor, int index) {}

    private static final Map<Class<?>, Map<Class<?>, Shape>> SHAPES = new ConcurrentHashMap<>();

    private RecordCopier() {
    }

    static Object replace(final Object record, final Class<?> type, final UnaryOperator<Object> change) {
        final Shape shape = SHAPES.computeIfAbsent(record.getClass(), c -> new ConcurrentHashMap<>())
                .computeIfAbsent(type, t -> shape(record.getClass(), t));
        if (shape.index < 0) {
            return record;
        }
        try {
            final Object[] args = new Object[shape.components.length];
            for (int i = 0; i < args.length; i++) {
                args[i] = shape.components[i].getAccessor().invoke(record);
            }
            args[shape.index] = change.apply(args[shape.index]);
            return shape.constructor.newInstance(args);
        } catch (final IllegalAccessException | InstantiationException | InvocationTargetException e) {
            throw new IllegalStateException("Could not copy record " + record.getClass().getName(), e);
        }
    }

    private static Shape shape(final Class<?> recordClass, final Class<?> type) {
        final RecordComponent[] components = recordClass.getRecordComponents();
        if (components == null) {
            return new Shape(null, null, -1);
        }
        int index = -1;
        final Class<?>[] types = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
            components[i].getAccessor().setAccessible(true);
            if (types[i] == type) {
                if (index >= 0) {
                    return new Shape(null, null, -1);
                }
                index = i;
            }
        }
        if (index < 0) {
            return new Shape(null, null, -1);
        }
        try {
            final Constructor<?> constructor = recordClass.getDeclaredConstructor(types);
            constructor.setAccessible(true);
            return new Shape(components, constructor, index);
        } catch (final NoSuchMethodException e) {
            return new Shape(null, null, -1);
        }
    }
}
