package com.v2ray.ang.dto

data class CheckUpdateResult(

    val hasUpdate: Boolean,
    val latestVersion: String? = null,
    val releaseNotes: String? = null,
    val downloadUrl: String? = null,
    val error: String? = null,
    val isPreRelease: Boolean = false,
    val versionCode: Int = 0,
    val sha256: String = "",
    val sizeBytes: Long = 0,
)