package fr.rowlaxx.springwebsocketaop.utils

import java.nio.ByteBuffer

object ByteBufferUtils {

    fun ByteBuffer.getBackingArray(): ByteArray {
        if (hasArray() && !isReadOnly()) {
            return array().clone()
        } else {
            val bytes = ByteArray(remaining())
            get(bytes)
            return bytes
        }
    }

}