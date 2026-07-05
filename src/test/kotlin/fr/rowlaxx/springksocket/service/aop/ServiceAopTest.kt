package fr.rowlaxx.springksocket.service.aop

import fr.rowlaxx.springksocket.annotation.AfterHandshake
import fr.rowlaxx.springksocket.annotation.BeforeHandshake
import fr.rowlaxx.springksocket.annotation.OnAvailable
import fr.rowlaxx.springksocket.annotation.OnMessage
import fr.rowlaxx.springksocket.annotation.OnUnavailable
import fr.rowlaxx.springksocket.annotation.WebSocketClient
import fr.rowlaxx.springksocket.annotation.WebSocketHandlerProperties
import fr.rowlaxx.springksocket.core.AutoWebSocketCollection
import fr.rowlaxx.springksocket.data.WebSocketAttributes
import fr.rowlaxx.springksocket.data.WebSocketClientProperties
import fr.rowlaxx.springksocket.model.PerpetualWebSocket
import fr.rowlaxx.springksocket.model.PerpetualWebSocketHandler
import fr.rowlaxx.springksocket.model.WebSocket
import fr.rowlaxx.springksocket.model.WebSocketDeserializer
import fr.rowlaxx.springksocket.model.WebSocketHandler
import fr.rowlaxx.springksocket.model.WebSocketSerializer
import fr.rowlaxx.springksocket.exception.WebSocketException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.support.StaticApplicationContext
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import java.net.URI
import java.net.http.HttpHeaders
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Unit tests for the `service.aop` reflection-driven factories: handler extraction and dispatch
 * (client + perpetual), handshake interception, serializer/deserializer resolution, client-properties
 * building, and the auto-collection / auto-perpetual field managers.
 */
class ServiceAopTest {

    // ------------------------------------------------------------------ fakes

    private class FakeWebSocket : WebSocket {
        override val id = 1L
        override val name = "fake"
        override val uri: URI = URI.create("ws://test")
        override val pingInterval: Duration = Duration.ofSeconds(1)
        override val readTimeout: Duration = Duration.ofSeconds(1)
        override val initTimeout: Duration = Duration.ofSeconds(1)
        override val attributes = WebSocketAttributes()
        override val handlerChain: List<WebSocketHandler> = listOf(object : WebSocketHandler {
            override val serializer = WebSocketSerializer.Passthrough
            override val deserializer = WebSocketDeserializer.Passthrough
        })
        override val currentHandlerIndex = 0
        override val requestHeaders: HttpHeaders = HttpHeaders.of(emptyMap()) { _, _ -> true }
        val sent = ConcurrentLinkedQueue<Any>()
        override fun sendMessageAsync(message: Any): Deferred<Unit> { sent += message; return CompletableDeferred(Unit) }
        override fun completeHandlerAsync(): Job = Job().also { it.complete() }
        override fun closeAsync(reason: String, code: Int): Job = Job().also { it.complete() }
        override fun getClosedReason(): WebSocketException? = null
        override fun hasOpened(): Boolean = true
    }

    private class FakePerpetualWebSocket : PerpetualWebSocket {
        override val id = 1
        override val name = "fake"
        override val switchDuration: Duration = Duration.ofSeconds(1)
        override val shiftDuration: Duration = Duration.ofSeconds(1)
        override val initializers: List<WebSocketHandler> = emptyList()
        override val handler: PerpetualWebSocketHandler get() = throw UnsupportedOperationException()
        override val propertiesFactory: () -> WebSocketClientProperties get() = throw UnsupportedOperationException()
        val sent = ConcurrentLinkedQueue<Any>()
        override fun isConnected(): Boolean = true
        override fun sendMessageAsync(message: Any): Deferred<Unit> { sent += message; return CompletableDeferred(Unit) }
    }

    // ------------------------------------------------------------- WebSocketHandlerFactory

    class ClientHandlerBean {
        val collection = AutoWebSocketCollection()
        val availableCalls = ConcurrentLinkedQueue<WebSocket>()
        val unavailableCalls = ConcurrentLinkedQueue<WebSocket>()
        val stringMsgs = ConcurrentLinkedQueue<String>()

        @OnAvailable
        fun onUp(ws: WebSocket) { availableCalls += ws }

        @OnUnavailable
        fun onDown(ws: WebSocket) { unavailableCalls += ws }

        @OnMessage
        fun onText(ws: WebSocket, msg: String): String { stringMsgs += msg; return "reply:$msg" }
    }

    @Test
    fun `WebSocketHandlerFactory dispatches lifecycle, auto-sends return values, and manages collections`() {
        val bean = ClientHandlerBean()
        val factory = WebSocketHandlerFactory(AutoWebSocketCollectionManager())
        val handler = factory.extract(bean, WebSocketSerializer.Passthrough, WebSocketDeserializer.Passthrough)

        val ws = FakeWebSocket()
        handler.onAvailable(ws)
        assertEquals(1, bean.availableCalls.size)
        assertEquals(listOf(ws), bean.collection.filter { true }, "onAvailable must register into the AutoWebSocketCollection")

        handler.onMessage(ws, "hi")
        assertEquals(listOf("hi"), bean.stringMsgs.toList())
        assertEquals("reply:hi", ws.sent.firstOrNull(), "a returned value must be auto-sent")

        handler.onUnavailable(ws)
        assertEquals(1, bean.unavailableCalls.size)
        assertTrue(bean.collection.filter { true }.isEmpty(), "onUnavailable must deregister from the collection")
    }

    class ThrowingHandlerBean {
        @OnMessage
        fun onText(ws: WebSocket, msg: String): Unit = throw RuntimeException("boom")
    }

    @Test
    fun `a throwing handler method does not propagate out of dispatch`() {
        val bean = ThrowingHandlerBean()
        val handler = WebSocketHandlerFactory(AutoWebSocketCollectionManager())
            .extract(bean, WebSocketSerializer.Passthrough, WebSocketDeserializer.Passthrough)
        // Must not throw.
        handler.onMessage(FakeWebSocket(), "hi")
    }

    class NotAHandlerBean

    @Test
    fun `extracting a bean with no handler methods fails fast`() {
        assertThrows(IllegalArgumentException::class.java) {
            WebSocketHandlerFactory(AutoWebSocketCollectionManager())
                .extract(NotAHandlerBean(), WebSocketSerializer.Passthrough, WebSocketDeserializer.Passthrough)
        }
    }

    // ------------------------------------------------------ PerpetualWebSocketHandlerFactory

    data class Ticker(val symbol: String)

    class PerpHandlerBean {
        val strings = ConcurrentLinkedQueue<String>()
        val tickers = ConcurrentLinkedQueue<Ticker>()

        @OnMessage
        fun onText(ws: PerpetualWebSocket, msg: String) { strings += msg }

        @OnMessage
        fun onTicker(ws: PerpetualWebSocket, t: Ticker): String { tickers += t; return "ack:${t.symbol}" }
    }

    @Test
    fun `PerpetualWebSocketHandlerFactory dispatches by message type and memoizes resolution`() {
        val bean = PerpHandlerBean()
        val handler = PerpetualWebSocketHandlerFactory()
            .extract(bean, WebSocketSerializer.Passthrough, WebSocketDeserializer.Passthrough)

        val ws = FakePerpetualWebSocket()
        handler.onMessage(ws, "hello")
        handler.onMessage(ws, "hello2") // second call of same type exercises the memoized path
        handler.onMessage(ws, Ticker("BTC"))

        assertEquals(listOf("hello", "hello2"), bean.strings.toList())
        assertEquals(listOf(Ticker("BTC")), bean.tickers.toList())
        assertEquals("ack:BTC", ws.sent.firstOrNull(), "a returned value must be auto-sent")
    }

    @Test
    fun `an unhandled message type is dropped without error`() {
        val bean = PerpHandlerBean()
        val handler = PerpetualWebSocketHandlerFactory()
            .extract(bean, WebSocketSerializer.Passthrough, WebSocketDeserializer.Passthrough)
        // Int has no @OnMessage handler; must be a no-op, not a crash.
        handler.onMessage(FakePerpetualWebSocket(), 42)
    }

    // ------------------------------------------------------- HandshakeInterceptorFactory

    private class StubRequest(private val attrs: MutableMap<String, Any>) : ServerHttpRequest {
        override fun getURI(): URI = URI.create("ws://localhost/ws")
        override fun getHeaders(): org.springframework.http.HttpHeaders = org.springframework.http.HttpHeaders()
        override fun getMethod(): org.springframework.http.HttpMethod = org.springframework.http.HttpMethod.GET
        override fun getBody(): java.io.InputStream = java.io.ByteArrayInputStream(ByteArray(0))
        override fun getPrincipal(): java.security.Principal? = null
        override fun getLocalAddress(): java.net.InetSocketAddress = java.net.InetSocketAddress(0)
        override fun getRemoteAddress(): java.net.InetSocketAddress = java.net.InetSocketAddress(0)
        override fun getAttributes(): MutableMap<String, Any> = attrs
        override fun getAsyncRequestControl(response: ServerHttpResponse) = throw UnsupportedOperationException()
    }

    private class StubResponse : ServerHttpResponse {
        override fun getBody(): java.io.OutputStream = java.io.ByteArrayOutputStream()
        override fun getHeaders(): org.springframework.http.HttpHeaders = org.springframework.http.HttpHeaders()
        override fun setStatusCode(status: org.springframework.http.HttpStatusCode) {}
        override fun flush() {}
        override fun close() {}
    }

    class HandshakeBean(private val allow: Boolean) {
        var beforeCalled = false
        var afterCalled = false

        @BeforeHandshake
        fun before(request: ServerHttpRequest, response: ServerHttpResponse, attributes: WebSocketAttributes): Boolean {
            beforeCalled = true
            return allow
        }

        @AfterHandshake
        fun after(request: ServerHttpRequest, response: ServerHttpResponse, attributes: WebSocketAttributes) {
            afterCalled = true
        }
    }

    @Test
    fun `HandshakeInterceptorFactory allows or rejects based on the before-handshake result`() {
        val allowingBean = HandshakeBean(allow = true)
        val allowing = HandshakeInterceptorFactory().extract(allowingBean)
        val attrs = HashMap<String, Any>()
        val request = StubRequest(attrs)
        val handler = org.springframework.web.socket.handler.TextWebSocketHandler()

        assertTrue(allowing.beforeHandshake(request, StubResponse(), handler, attrs))
        assertTrue(allowingBean.beforeCalled)
        allowing.afterHandshake(request, StubResponse(), handler, null)
        assertTrue(allowingBean.afterCalled)

        val rejectingBean = HandshakeBean(allow = false)
        val rejecting = HandshakeInterceptorFactory().extract(rejectingBean)
        assertFalse(rejecting.beforeHandshake(StubRequest(HashMap()), StubResponse(), handler, HashMap()))
    }

    // ------------------------------------------------ WebSocketSerializerDeserializerExtractor

    class UpperSerializer : WebSocketSerializer {
        override fun toStringOrByteArray(obj: Any): Any = obj.toString().uppercase()
    }

    class LowerDeserializer : WebSocketDeserializer {
        override fun fromStringOrByteArray(obj: Any): Any = obj.toString().lowercase()
    }

    @WebSocketHandlerProperties(serializer = UpperSerializer::class, deserializer = LowerDeserializer::class)
    class OverridingBean

    @WebSocketClient(url = "ws://x")
    class DefaultingBean

    @Test
    fun `serializer extractor resolves Null, Passthrough, bean-defined, and per-class overrides`() {
        val ctx = StaticApplicationContext()
        ctx.beanFactory.registerSingleton("upper", UpperSerializer())
        ctx.beanFactory.registerSingleton("lower", LowerDeserializer())
        ctx.refresh()
        val extractor = WebSocketSerializerDeserializerExtractor(ctx)

        assertSame(WebSocketSerializer.Passthrough, extractor.getSerializer(WebSocketSerializer.Passthrough::class))
        assertSame(WebSocketSerializer.Null, extractor.getSerializer(WebSocketSerializer.Null::class))
        assertTrue(extractor.getSerializer(UpperSerializer::class) is UpperSerializer)

        // @WebSocketClient defaults (Passthrough) when the bean has no @WebSocketHandlerProperties.
        val client = DefaultingBean::class.annotations.filterIsInstance<WebSocketClient>().first()
        val (defSer, defDes) = extractor.extract(client, DefaultingBean())
        assertSame(WebSocketSerializer.Passthrough, defSer)
        assertSame(WebSocketDeserializer.Passthrough, defDes)

        // @WebSocketHandlerProperties on the bean overrides the annotation defaults.
        val (ovSer, ovDes) = extractor.extract(client, OverridingBean())
        assertTrue(ovSer is UpperSerializer)
        assertTrue(ovDes is LowerDeserializer)
    }

    // ------------------------------------------------------ WebSocketClientPropertiesFactory

    @WebSocketClient(url = "ws://from-annotation:9000", initTimeout = "PT11S", pingInterval = "PT6S", readTimeout = "PT12S")
    class AnnotatedClientBean

    class MethodClientBean {
        fun props(): WebSocketClientProperties = WebSocketClientProperties(
            uri = URI.create("ws://from-method:8000"),
            headers = HttpHeaders.of(emptyMap()) { _, _ -> true },
            initTimeout = Duration.ofSeconds(1),
            pingInterval = Duration.ofSeconds(2),
            readTimeout = Duration.ofSeconds(3),
        )
    }

    @Test
    fun `client properties are built from the annotation`() {
        val ctx = StaticApplicationContext().apply { refresh() }
        val factory = WebSocketClientPropertiesFactory(ctx)
        val props = factory.extract(AnnotatedClientBean())()
        assertEquals(URI.create("ws://from-annotation:9000"), props.uri)
        assertEquals(Duration.ofSeconds(11), props.initTimeout)
        assertEquals(Duration.ofSeconds(6), props.pingInterval)
        assertEquals(Duration.ofSeconds(12), props.readTimeout)
    }

    @Test
    fun `client properties are built from a bean method when present`() {
        val ctx = StaticApplicationContext().apply { refresh() }
        val factory = WebSocketClientPropertiesFactory(ctx)
        val props = factory.extract(MethodClientBean())()
        assertEquals(URI.create("ws://from-method:8000"), props.uri)
        assertEquals(Duration.ofSeconds(2), props.pingInterval)
    }

    // ------------------------------------------------------------ Auto*Manager

    class CollectionBean { val collection = AutoWebSocketCollection() }
    class MutableCollectionBean { var collection = AutoWebSocketCollection() }

    @Test
    fun `AutoWebSocketCollectionManager wires final collection fields and rejects mutable ones`() {
        val manager = AutoWebSocketCollectionManager()
        val bean = CollectionBean()
        manager.initializeIfNotDone(bean)

        val ws = FakeWebSocket()
        manager.onAvailable(bean, ws)
        assertEquals(listOf(ws), bean.collection.filter { true })
        manager.onUnavailable(bean, ws)
        assertTrue(bean.collection.filter { true }.isEmpty())

        assertThrows(IllegalArgumentException::class.java) {
            manager.initializeIfNotDone(MutableCollectionBean())
        }
    }

    class PerpFieldBean { var socket: PerpetualWebSocket? = null }
    class ImmutablePerpFieldBean { val socket: PerpetualWebSocket? = null }

    @Test
    fun `AutoPerpetualWebSocketManager sets mutable perpetual fields and rejects final ones`() {
        val manager = AutoPerpetualWebSocketManager()
        val bean = PerpFieldBean()
        manager.initializeIfNotDone(bean)

        val ws = FakePerpetualWebSocket()
        manager.set(bean, ws)
        assertSame(ws, bean.socket)

        assertThrows(IllegalArgumentException::class.java) {
            manager.initializeIfNotDone(ImmutablePerpFieldBean())
        }
    }

    @Test
    fun `AutoWebSocketCollection send serializes once per distinct serializer and skips disconnected sockets`() {
        // Guard the small allocation optimization in AutoWebSocketCollection.send.
        val collection = AutoWebSocketCollection()
        val ws = FakeWebSocket()
        collection.add(ws)
        collection.sendAll("payload")
        assertEquals("payload", ws.sent.firstOrNull())
        assertNull(collection.filter { false }.firstOrNull())
    }
}
