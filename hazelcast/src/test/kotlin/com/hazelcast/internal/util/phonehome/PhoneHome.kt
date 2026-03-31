package com.hazelcast.internal.util.phonehome

import com.hazelcast.instance.impl.Node

class PhoneHome(
    node: Node,
) {
    fun start() {}

    fun shutdown() {}

    fun phoneHome(isStartup: Boolean): Map<String, String> = emptyMap()

    companion object {
        @JvmStatic
        fun isPhoneHomeEnabled(node: Node): Boolean = false
    }
}
