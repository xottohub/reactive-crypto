package com.njkim.reactivecrypto.okex

import com.fasterxml.jackson.module.kotlin.readValue
import com.njkim.reactivecrypto.core.ExchangeWebsocketClient
import com.njkim.reactivecrypto.core.common.model.ExchangeVendor
import com.njkim.reactivecrypto.core.common.model.currency.CurrencyPair
import com.njkim.reactivecrypto.core.common.model.order.OrderBook
import com.njkim.reactivecrypto.core.common.model.order.OrderBookUnit
import com.njkim.reactivecrypto.core.common.model.order.TickData
import com.njkim.reactivecrypto.okex.model.OkexOrderBookWrapper
import com.njkim.reactivecrypto.okex.model.OkexTickDataWrapper
import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufInputStream
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageDecoder
import mu.KotlinLogging
import org.apache.commons.compress.compressors.deflate64.Deflate64CompressorInputStream
import org.springframework.util.StreamUtils
import reactor.core.publisher.Flux
import reactor.netty.http.client.HttpClient
import java.math.BigDecimal
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap
import kotlin.streams.toList


class OkexWebsocketClient : ExchangeWebsocketClient {

    private val log = KotlinLogging.logger {}

    private val baseUri = "wss://real.okex.com:10442/ws/v3"

    override fun createDepthSnapshot(subscribeTargets: List<CurrencyPair>): Flux<OrderBook> {
        val subscribeMessages = subscribeTargets.stream()
            .map { "${it.targetCurrency.name}-${it.baseCurrency.name}" }
            .map { "{\"op\": \"subscribe\", \"args\": [\"spot/depth:$it\"]}" }
            .toList()

        val currentOrderBookMap: MutableMap<CurrencyPair, OrderBook> = ConcurrentHashMap()

        return HttpClient.create()
            .wiretap(log.isDebugEnabled)
            .tcpConfiguration { tcp -> tcp.doOnConnected { connection -> connection.addHandler(Deflat64Decoder()) } }
            .websocket()
            .uri(baseUri)
            .handle { inbound, outbound ->
                outbound.sendString(Flux.fromIterable<String>(subscribeMessages))
                    .then()
                    .thenMany(inbound.receive().asString())
            }
            .doOnNext { log.debug { it } }
            .filter { it.contains("\"spot/depth\"") }
            .map { OkexJsonObjectMapper.instance.readValue<OkexOrderBookWrapper>(it) }
            .map { it.data }
            .flatMapIterable {
                it.map { okexTickData ->
                    OrderBook(
                        "${okexTickData.instrumentId}${okexTickData.timestamp.toInstant().toEpochMilli()}",
                        okexTickData.instrumentId,
                        okexTickData.timestamp,
                        ExchangeVendor.OKEX,
                        okexTickData.getBids().toMutableList(),
                        okexTickData.getAsks().toMutableList()
                    )
                }
            }
            .map { orderBook ->
                if (!currentOrderBookMap.containsKey(orderBook.currencyPair)) {
                    currentOrderBookMap[orderBook.currencyPair] = orderBook
                    return@map orderBook
                }

                val prevOrderBook = currentOrderBookMap[orderBook.currencyPair]!!

                val askMap: MutableMap<BigDecimal, OrderBookUnit> = prevOrderBook.asks
                    .map { Pair(it.price.stripTrailingZeros(), it) }
                    .toMap()
                    .toMutableMap()

                orderBook.asks.forEach { updatedAsk ->
                    askMap.compute(updatedAsk.price.stripTrailingZeros()) { _, oldValue ->
                        when {
                            oldValue == null -> updatedAsk
                            updatedAsk.quantity <= BigDecimal.ZERO -> null
                            else -> oldValue.copy(
                                quantity = updatedAsk.quantity,
                                orderNumbers = updatedAsk.orderNumbers
                            )
                        }
                    }
                }

                val bidMap: MutableMap<BigDecimal, OrderBookUnit> = prevOrderBook.bids
                    .map { Pair(it.price.stripTrailingZeros(), it) }
                    .toMap()
                    .toMutableMap()

                orderBook.bids.forEach { updatedBid ->
                    bidMap.compute(updatedBid.price.stripTrailingZeros()) { _, oldValue ->
                        when {
                            oldValue == null -> updatedBid
                            updatedBid.quantity <= BigDecimal.ZERO -> null
                            else -> oldValue.copy(
                                quantity = updatedBid.quantity,
                                orderNumbers = updatedBid.orderNumbers
                            )
                        }
                    }
                }

                val currentOrderBook = prevOrderBook.copy(
                    asks = askMap.values.sortedBy { orderBookUnit -> orderBookUnit.price },
                    bids = bidMap.values.sortedByDescending { orderBookUnit -> orderBookUnit.price }
                )
                currentOrderBookMap[currentOrderBook.currencyPair] = currentOrderBook
                currentOrderBook
            }
    }

    override fun createTradeWebsocket(subscribeTargets: List<CurrencyPair>): Flux<TickData> {
        val subscribeMessages = subscribeTargets.stream()
            .map { "${it.targetCurrency.name}-${it.baseCurrency.name}" }
            .map { "{\"op\": \"subscribe\", \"args\": [\"spot/trade:$it\"]}" }
            .toList()


        return HttpClient.create()
            .wiretap(log.isDebugEnabled)
            .tcpConfiguration { tcp ->
                tcp.doOnConnected { connection ->
                    connection.addHandler(Deflat64Decoder())
                }
            }
            .websocket()
            .uri(baseUri)
            .handle { inbound, outbound ->
                outbound.sendString(Flux.fromIterable<String>(subscribeMessages))
                    .then()
                    .thenMany(inbound.receive().asString())
            }
            .filter { t -> t.contains("\"spot/trade\"") }
            .map { OkexJsonObjectMapper.instance.readValue<OkexTickDataWrapper>(it) }
            .map { it.data }
            .flatMapIterable {
                it.map { okexTickData ->
                    TickData(
                        okexTickData.tradeId,
                        okexTickData.timestamp,
                        okexTickData.price,
                        okexTickData.size,
                        okexTickData.instrumentId,
                        ExchangeVendor.OKEX
                    )
                }
            }
            .doOnError { log.error(it.message, it) }
    }

    private inner class Deflat64Decoder : ByteToMessageDecoder() {
        override fun decode(ctx: ChannelHandlerContext, msg: ByteBuf, out: MutableList<Any>) {
            Deflate64CompressorInputStream(ByteBufInputStream(msg)).use {
                val responseBody = StreamUtils.copyToString(it, Charset.forName("UTF-8"))
                val uncompressed = msg.alloc().buffer().writeBytes(responseBody.toByteArray())
                out.add(uncompressed)
            }
        }
    }
}