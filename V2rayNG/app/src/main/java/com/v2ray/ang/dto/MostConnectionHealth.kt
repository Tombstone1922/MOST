package com.v2ray.ang.dto

import java.io.Serializable

enum class MostLinkState { CHECKING, ONLINE, NO_INTERNET, RECOVERING, NO_NETWORK, UNSUPPORTED, SERVER_UNAVAILABLE, NO_ROUTE, WRONG_COUNTRY }
data class MostConnectionHealth(val guid: String = "", val state: MostLinkState = MostLinkState.CHECKING,
    val ip: String = "", val country: String = "", val latencyMs: Long = 0, val attempt: Int = 0, val total: Int = 0, val routeRequestId: String = "") : Serializable
