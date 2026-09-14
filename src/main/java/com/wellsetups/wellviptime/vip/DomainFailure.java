package com.wellsetups.wellviptime.vip;

import java.util.Map;

public final class DomainFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String key;

    private final java.util.HashMap<String, String> values;

    public DomainFailure(String key) {
        this(key, Map.of());
    }

    public DomainFailure(String key, Map<String, String> values) {
        super(key);
        this.key = key;
        this.values = new java.util.HashMap<>(values);
    }

    public String key() {
        return key;
    }

    public Map<String, String> values() {
        return Map.copyOf(values);
    }
}
