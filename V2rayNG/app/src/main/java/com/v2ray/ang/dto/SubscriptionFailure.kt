package com.v2ray.ang.dto

import androidx.annotation.StringRes
import com.v2ray.ang.R
import java.io.IOException

/** Non-secret subscription failure carried from HTTP to every subscription UI. */
enum class SubscriptionFailure(@StringRes val messageRes: Int) {
    HWID_REQUIRED(R.string.most_sub_hwid_required),
    DEVICE_LIMIT(R.string.most_sub_device_limit),
    ACCESS_DENIED(R.string.most_sub_access_denied),
    NOT_FOUND(R.string.most_sub_not_found),
    RATE_LIMIT(R.string.most_sub_rate_limit),
    SERVER_ERROR(R.string.most_sub_server_error),
    HTTP_ERROR(R.string.most_sub_http_error),
    NETWORK(R.string.most_sub_network),
    EMPTY_RESPONSE(R.string.most_sub_empty_response),
    NO_PROFILES(R.string.most_sub_no_profiles),
}

class SubscriptionRequestException(
    val failure: SubscriptionFailure,
    val statusCode: Int? = null,
) : IOException("Subscription request failed: $failure${statusCode?.let { " ($it)" }.orEmpty()}")
