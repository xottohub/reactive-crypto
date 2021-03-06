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

package com.njkim.reactivecrypto.upbit.model

import com.fasterxml.jackson.annotation.JsonProperty
import com.njkim.reactivecrypto.core.common.model.currency.Currency
import java.math.BigDecimal

data class UpbitBalance(
    @get:JsonProperty("currency")
    val currency: Currency,

    @get:JsonProperty("balance")
    val balance: BigDecimal,

    @get:JsonProperty("locked")
    val locked: BigDecimal,

    @get:JsonProperty("avg_buy_price")
    val avgBuyPrice: BigDecimal,

    @get:JsonProperty("avg_buy_price_modified")
    val avgBuyPriceModified: Boolean,

    @get:JsonProperty("unit_currency")
    val unitCurrency: String,

    @get:JsonProperty("avg_krw_buy_price")
    val avgKrwBuyPrice: BigDecimal,

    @get:JsonProperty("modified")
    val modified: Boolean
)