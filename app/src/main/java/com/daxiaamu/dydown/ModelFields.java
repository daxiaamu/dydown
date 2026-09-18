package com.daxiaamu.dydown;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Resolves JSON field names, including fields renamed by the host's obfuscator. */
public final class ModelFields {
    private static final Map<Class<?>, Map<String, Field>> CACHE = new ConcurrentHashMap<>();
    private static Map<String, Field> fields(Class<?> type) {
        return CACHE.computeIfAbsent(type, key -> {
            Map<String, Field> result = new ConcurrentHashMap<>();
            for (Class<?> c = key; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    try {
                        f.setAccessible(true);
                        result.putIfAbsent(f.getName(), f);
                        for (Annotation a : f.getDeclaredAnnotations()) {
                            if (a.annotationType().getName().equals("com.google.gson.annotations.SerializedName")) {
                                String name = (String) a.annotationType().getMethod("value").invoke(a);
                                result.putIfAbsent(name, f);
                            }
                        }
                    } catch (ReflectiveOperationException | RuntimeException ignored) { }
                }
            }
            return result;
        });
    }
    public static Object get(Object object, String name) {
        if (object == null) return null;
        Field field = fields(object.getClass()).get(name);
        if (field == null) return null;
        try { return field.get(object); } catch (IllegalAccessException ignored) { return null; }
    }
    public static boolean set(Object object, String name, Object value) {
        if (object == null) return false;
        Field field = fields(object.getClass()).get(name);
        if (field == null) return false;
        try { field.set(object, value); return true; }
        catch (IllegalAccessException | IllegalArgumentException ignored) { return false; }
    }
    private ModelFields() { }
}
