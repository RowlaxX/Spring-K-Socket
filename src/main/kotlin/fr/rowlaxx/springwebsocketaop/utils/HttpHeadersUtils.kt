package fr.rowlaxx.springwebsocketaop.utils

import org.springframework.http.HttpHeaders

object HttpHeadersUtils {

    fun HttpHeaders.toJavaHeaders(): java.net.http.HttpHeaders {
        val pairs = this.headerSet()
            .flatMap { it.value.map { v -> it.key to v } }
            .groupBy({ it.first }, { it.second })

        return java.net.http.HttpHeaders.of(pairs, { _, _ -> true })
    }

}