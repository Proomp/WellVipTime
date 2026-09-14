package com.wellsetups.wellviptime.configuration;

public final class ConfigError extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    public ConfigError(String file, String path, Object value, String expected) {
        super(file + " :: " + path + " = " + value + "; expected " + expected);
    }
}
