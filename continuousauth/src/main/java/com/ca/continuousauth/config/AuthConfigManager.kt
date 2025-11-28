package com.ca.continuousauth.config

object AuthConfigManager {
    // Default config
    var config: AuthConfig = AuthConfig.Builder().build()

    // Optional: helper to update config
    fun init(configBuilder: AuthConfig.Builder.() -> Unit) {
        config = AuthConfig.Builder().apply(configBuilder).build()
    }
}