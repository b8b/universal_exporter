package org.cikit.core

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.util.TokenBuffer

class JsonObjectBuffer {

    private var buffer: TokenBuffer? = null
    private var objectDepth = -1
    private var arrayDepth = 0

    private fun createBuffer(p: JsonParser): TokenBuffer {
        objectDepth = -1
        arrayDepth = 0
        TokenBuffer(p).let {
            buffer = it
            return it
        }
    }

    fun processEvent(p: JsonParser): TokenBuffer? {
        var result: TokenBuffer? = null
        val token = p.currentToken()
        when (token) {
            JsonToken.START_OBJECT -> {
                (buffer ?: createBuffer(p)).copyCurrentEvent(p)
                objectDepth++
            }
            JsonToken.END_OBJECT -> {
                buffer?.copyCurrentEvent(p)
                if (objectDepth == 0 && arrayDepth == 0) {
                    buffer?.let {
                        it.flush()
                        result = it
                    }
                    buffer = null
                }
                objectDepth--
            }
            JsonToken.START_ARRAY -> {
                buffer?.copyCurrentEvent(p)
                arrayDepth++
            }
            JsonToken.END_ARRAY -> {
                buffer?.copyCurrentEvent(p)
                arrayDepth--
            }
            else -> buffer?.copyCurrentEvent(p)
        }
        return result
    }

}
