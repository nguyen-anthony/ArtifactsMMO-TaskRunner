package com.artifactsmmo.client.services

import com.artifactsmmo.client.BaseApiService
import com.artifactsmmo.client.models.*
import io.ktor.client.*
import io.ktor.client.request.parameter

/**
 * Service for bank operations
 */
class BankService(client: HttpClient) : BaseApiService(client) {

    /**
     * Get bank details
     */
    suspend fun getBankDetails(): Bank {
        return get<ApiResponse<Bank>>("/my/bank").data
    }

    /**
     * Get all items in your bank
     */
    suspend fun getBankItems(itemCode: String? = null, page: Int = 1, size: Int = 50): DataPage<SimpleItem> {
        return get<DataPage<SimpleItem>>("/my/bank/items") {
            itemCode?.let { parameter("item_code", it) }
            parameter("page", page)
            parameter("size", size)
        }
    }

    /**
     * Deposit gold into the bank
     */
    suspend fun depositGold(characterName: String, quantity: Int): BankGoldTransactionData {
        val body = BankGoldRequest(quantity = quantity)
        return post<ApiResponse<BankGoldTransactionData>>("/my/$characterName/action/bank/deposit/gold", body).data
    }

    /**
     * Withdraw gold from the bank
     */
    suspend fun withdrawGold(characterName: String, quantity: Int): BankGoldTransactionData {
        val body = BankGoldRequest(quantity = quantity)
        return post<ApiResponse<BankGoldTransactionData>>("/my/$characterName/action/bank/withdraw/gold", body).data
    }

    /**
     * Deposit items into the bank
     */
    suspend fun depositItems(characterName: String, items: List<SimpleItem>): BankItemTransactionData {
        return post<ApiResponse<BankItemTransactionData>>("/my/$characterName/action/bank/deposit/item", items).data
    }

    /**
     * Withdraw items from the bank
     */
    suspend fun withdrawItems(characterName: String, items: List<SimpleItem>): BankItemTransactionData {
        return post<ApiResponse<BankItemTransactionData>>("/my/$characterName/action/bank/withdraw/item", items).data
    }

    /**
     * Buy a bank expansion
     */
    suspend fun buyExpansion(characterName: String): Character {
        return post<ApiResponse<Character>>("/my/$characterName/action/bank/buy_expansion").data
    }
}

