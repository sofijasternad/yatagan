package com.yandex.daggerlite.core.lang

interface HasPlatformModel {
    /**
     * Underlying implementation-specific model, if any.
     *
     * External clients should not rely on this property yielding a specific type.
     */
    val platformModel: Any?
}