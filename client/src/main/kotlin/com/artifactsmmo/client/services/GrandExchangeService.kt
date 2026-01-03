package com.artifactsmmo.client.services

import com.artifactsmmo.client.BaseApiService
import com.artifactsmmo.client.models.*
import io.ktor.client.*
import io.ktor.client.request.parameter

/**
 * Service for Grand Exchange trading operations
 */
class GrandExchangeService(client: HttpClient) : BaseApiService(client) {

    /**
     * Get all sell orders
     */
    suspend fun getOrders(
        itemCode: String? = null,
        seller: String? = null,
        page: Int = 1,
        size: Int = 50
    ): DataPage<GrandExchangeOrder> {
        return get<DataPage<GrandExchangeOrder>>("/grandexchange/orders") {
            itemCode?.let { parameter("code", it) }
            seller?.let { parameter("seller", it) }
            parameter("page", page)
            parameter("size", size)
        }
    }

    /**
     * Get a specific sell order
     */
    suspend fun getOrder(orderId: String): GrandExchangeOrder {
        return get<ApiResponse<GrandExchangeOrder>>("/grandexchange/orders/$orderId").data
    }

    /**
     * Get your sell orders
     */
    suspend fun getMyOrders(
        itemCode: String? = null,
        page: Int = 1,
        size: Int = 50
    ): DataPage<GrandExchangeOrder> {
        return get<DataPage<GrandExchangeOrder>>("/my/grandexchange/orders") {
            itemCode?.let { parameter("code", it) }
            parameter("page", page)
            parameter("size", size)
        }
    }

    /**
     * Create a sell order
     */
    suspend fun createSellOrder(
        characterName: String,
        itemCode: String,
        quantity: Int,
        price: Int
    ): GECreateOrderData {
        val body = mapOf("code" to itemCode, "quantity" to quantity, "price" to price)
        return post<ApiResponse<GECreateOrderData>>("/my/$characterName/action/grandexchange/sell", body).data
    }

    /**
     * Buy from a sell order
     */
    suspend fun buyOrder(
        characterName: String,
        orderId: String,
        quantity: Int
    ): GETransactionData {
        val body = mapOf("id" to orderId, "quantity" to quantity)
        return post<ApiResponse<GETransactionData>>("/my/$characterName/action/grandexchange/buy", body).data
    }

    /**
     * Cancel a sell order
     */
    suspend fun cancelOrder(characterName: String, orderId: String): GETransactionData {
        val body = mapOf("id" to orderId)
        return post<ApiResponse<GETransactionData>>("/my/$characterName/action/grandexchange/cancel", body).data
    }
}

