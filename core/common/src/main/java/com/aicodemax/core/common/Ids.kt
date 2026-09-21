package com.aicodemax.core.common

import java.util.UUID

object Ids {
    fun newId(prefix: String): String = "${prefix}_${UUID.randomUUID().toString().take(8)}"
    fun newUuid(): String = UUID.randomUUID().toString()
}
