package com.bori.core.build.model

data class KeystoreConfig(
    val keystorePath: String,
    val keyAlias: String,
    val keystorePassword: String,
    val keyPassword: String,
)
