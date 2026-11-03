package com.dl1991.feiyu.net

import okhttp3.OkHttpClient

/** Single place to route HTTP. v0.1 has no proxy settings, so the caller's client is used as-is. */
object NetworkHttp {
    const val DEFAULT_TAG = "default"
    @Suppress("UNUSED_PARAMETER")
    fun client(tag: String, base: OkHttpClient): OkHttpClient = base
}
