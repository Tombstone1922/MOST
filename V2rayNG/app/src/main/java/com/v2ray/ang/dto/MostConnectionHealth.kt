package com.v2ray.ang.dto

import java.io.Serializable

enum class MostLinkState { CHECKING, ONLINE, NO_INTERNET, RECOVERING, NO_NETWORK, UNSUPPORTED }
data class MostConnectionHealth(val guid: String = "", val state: MostLinkState = MostLinkState.CHECKING,
    val ip: String = "", val country: String = "", val latencyMs: Long = 0) : Serializable
