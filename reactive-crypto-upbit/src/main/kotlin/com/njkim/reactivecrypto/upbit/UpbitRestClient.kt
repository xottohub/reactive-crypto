/*
 * Copyright 2019 namjug-kim
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.njkim.reactivecrypto.upbit

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.njkim.reactivecrypto.core.common.model.account.Balance
import com.njkim.reactivecrypto.core.common.model.currency.Currency
import com.njkim.reactivecrypto.upbit.model.UpbitApiException
import com.njkim.reactivecrypto.upbit.model.UpbitBalance
import com.njkim.reactivecrypto.upbit.model.UpbitErrorResponse
import org.springframework.http.codec.json.Jackson2JsonDecoder
import org.springframework.http.codec.json.Jackson2JsonEncoder
import org.springframework.util.MimeTypeUtils
import org.springframework.web.reactive.function.client.ExchangeFilterFunction
import org.springframework.web.reactive.function.client.ExchangeStrategies
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import org.springframework.web.util.UriComponentsBuilder
import java.time.Instant


class UpbitRestClient {
    private val baseUrl = "https://api.upbit.com"

    inner class privateClient(
        val accessToken: String,
        val secretToken: String
    ) {
        fun getBalance(): Map<Currency, Balance> {
            val uri = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .path("/v1/accounts")
                .encode()
                .build()
                .toUri()

            return createPrivateWebClient()
                .get()
                .uri(uri)
                .retrieve()
                .upbitErrorHandling()
                .bodyToMono<List<UpbitBalance>>()
                .block()!!
                .map {
                    Pair(
                        it.currency,
                        Balance(
                            it.currency,
                            it.balance,
                            it.locked
                        )
                    )
                }
                .toMap()
        }

        private fun createPrivateWebClient(): WebClient {
            val exchangeFilterFunction = ExchangeFilterFunction { request, next ->
                val query = request.url().query

                val algorithm = Algorithm.HMAC256(accessToken)
                val builder = JWT.create()
                    .withClaim("access_key", secretToken)
                    .withClaim("nonce", Instant.now().toEpochMilli().toString())
                    .withClaim("query", query)

                val token = builder.sign(algorithm)

                val headers = request.headers()
                headers.add("Authorization", "Bearer $token")

                next.exchange(request)
            }

            return defaultWebClientBuilder()
                .filter(exchangeFilterFunction)
                .build()
        }
    }

    fun defaultWebClientBuilder(): WebClient.Builder {
        val strategies = ExchangeStrategies.builder()
            .codecs { clientCodecConfigurer ->
                clientCodecConfigurer.defaultCodecs()
                    .jackson2JsonEncoder(
                        Jackson2JsonEncoder(
                            UpbitJsonObjectMapper().objectMapper(),
                            MimeTypeUtils.APPLICATION_JSON
                        )
                    )
                clientCodecConfigurer.defaultCodecs()
                    .jackson2JsonDecoder(
                        Jackson2JsonDecoder(
                            UpbitJsonObjectMapper().objectMapper(),
                            MimeTypeUtils.APPLICATION_JSON
                        )
                    )
            }
            .build()

        return WebClient.builder()
            .exchangeStrategies(strategies)
    }
}

fun WebClient.ResponseSpec.upbitErrorHandling(): WebClient.ResponseSpec = onStatus({ it.isError }, { clientResponse ->
    clientResponse.bodyToMono(UpbitErrorResponse::class.java)
        .map { UpbitApiException(clientResponse.statusCode(), it) }
})