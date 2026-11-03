// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.boot;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reflection on m2e and Maven objects. The helper is on the boot class path, so it can't name
 * their types; it reaches them by name, and keeps JDK types in its own signatures (design §6).
 * Lookups are cached per class.
 */
final class Reflect {

    private static final ClassValue<Map<String, Method>> METHODS = new ClassValue<>() {
        @Override
        protected Map<String, Method> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };
    private static final ClassValue<Map<String, Field>> FIELDS = new ClassValue<>() {
        @Override
        protected Map<String, Field> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

    private Reflect() {}

    /** Calls a no-argument method, public or not, declared on the object's class or a superclass. */
    static Object call(Object target, String name) {
        return call(target, name, new Class<?>[0]);
    }

    static Object call(Object target, String name, Class<?>[] types, Object... args) {
        if (target == null) {
            return null;
        }
        String key = name + Arrays.toString(types);
        Method method = METHODS.get(target.getClass()).computeIfAbsent(key, k -> findMethod(target.getClass(), name, types));
        try {
            return method.invoke(target, args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(target.getClass().getName() + "." + name, e);
        }
    }

    /** Reads a field, public or not, declared on the object's class or a superclass. */
    static Object field(Object target, String name) {
        Field field = FIELDS.get(target.getClass()).computeIfAbsent(name, k -> findField(target.getClass(), name));
        try {
            return field.get(target);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(target.getClass().getName() + "." + name, e);
        }
    }

    /** An Eclipse {@code IResource} (or a {@code File}) as a {@code File}, via {@code getLocation().toFile()}. */
    static File toFile(Object resource) {
        if (resource == null || resource instanceof File) {
            return (File) resource;
        }
        Object location = call(resource, "getLocation");
        return location == null ? null : (File) call(location, "toFile");
    }

    private static Method findMethod(Class<?> type, String name, Class<?>[] types) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name, types);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException e) {
                // keep walking
            }
        }
        try {
            Method m = type.getMethod(name, types);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(type.getName() + " has no method " + name + Arrays.toString(types), e);
        }
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                // keep walking
            }
        }
        throw new IllegalStateException(type.getName() + " has no field " + name);
    }
}
