package com.artifactsmmo.client.services

import com.artifactsmmo.client.ApiTransport
import com.artifactsmmo.client.BaseApiService
import com.artifactsmmo.client.models.AcceptOfferingData
import com.artifactsmmo.client.models.ApiResponse
import com.artifactsmmo.client.models.CharacterSeasonalOfferings
import com.artifactsmmo.client.models.CompleteOfferingData
import com.artifactsmmo.client.models.DailySeasonalOfferings
import com.artifactsmmo.client.models.OfferingRequest

/** Season 9 Seasonal Altar offerings (member-only; actions must be done at the altar). */
class OfferingService(transport: ApiTransport) : BaseApiService(transport) {
    /** Today's rotation. 575 when no rotation is available. */
    suspend fun getDaily(): DailySeasonalOfferings =
        get<ApiResponse<DailySeasonalOfferings>>("/seasonal_offerings").data

    /** Today's rotation plus what [characterName] has accepted/completed and its active favors. */
    suspend fun getForCharacter(characterName: String): CharacterSeasonalOfferings =
        get<ApiResponse<CharacterSeasonalOfferings>>("/my/$characterName/offerings").data

    /** Accept [offeringId]; the favor activates immediately. 576 = category already selected today. */
    suspend fun accept(characterName: String, offeringId: String): AcceptOfferingData =
        post<ApiResponse<AcceptOfferingData>>(
            "/my/$characterName/action/offering/accept", OfferingRequest(offeringId)
        ).data

    /** Hand in the requirement items for an accepted offering. 478 = missing items, 577 = not accepted. */
    suspend fun complete(characterName: String, offeringId: String): CompleteOfferingData =
        post<ApiResponse<CompleteOfferingData>>(
            "/my/$characterName/action/offering", OfferingRequest(offeringId)
        ).data
}
