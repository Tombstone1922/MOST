package com.v2ray.ang.handler

import com.tencent.mmkv.MMKV
import java.lang.reflect.Field

/**
 * A test owns its native-handle doubles only until close. Static MMKV provider mocks alone
 * leave MmkvManager's lazy handles cached and make unrelated test classes depend on run order.
 * Replace only the lazy value, retaining its initializer and restoring the exact prior value.
 */
internal class MmkvTestStorage(stores: Map<String, MMKV>) : AutoCloseable {
    private val previous = mutableListOf<Triple<Any, Field, Any?>>()

    init {
        try {
            stores.forEach { (name, store) ->
                val delegateField = MmkvManager::class.java.getDeclaredField("${name}\$delegate")
                delegateField.isAccessible = true
                val delegate = delegateField.get(MmkvManager)
                val valueField = delegate.javaClass.getDeclaredField("_value")
                valueField.isAccessible = true
                previous += Triple(delegate, valueField, valueField.get(delegate))
                valueField.set(delegate, store)
            }
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    override fun close() {
        previous.asReversed().forEach { (delegate, field, value) -> field.set(delegate, value) }
        previous.clear()
    }
}
