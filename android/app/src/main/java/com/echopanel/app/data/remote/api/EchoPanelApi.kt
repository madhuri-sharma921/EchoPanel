package com.echopanel.app.data.remote.api

import com.echopanel.app.data.remote.dto.AgoraTokenResponseDto
import com.echopanel.app.data.remote.dto.AgoraTurnRequestDto
import com.echopanel.app.data.remote.dto.AgoraTurnResponseDto
import com.echopanel.app.data.remote.dto.CreateSessionResponseDto
import com.echopanel.app.data.remote.dto.FinalReportDto
import com.echopanel.app.data.remote.dto.StartAgentResponseDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface EchoPanelApi {

    @POST("sessions")
    suspend fun createSession(
        @Query("candidate_name") candidateName: String,
        @Query("active_personas") activePersonas: List<String>,
    ): CreateSessionResponseDto

    @GET("sessions/{sessionId}")
    suspend fun getSession(@Path("sessionId") sessionId: String): CreateSessionResponseDto

    @POST("sessions/{sessionId}/consent")
    suspend fun logConsent(@Path("sessionId") sessionId: String): CreateSessionResponseDto

    @GET("sessions/{sessionId}/report")
    suspend fun getFinalReport(@Path("sessionId") sessionId: String): FinalReportDto

    @POST("agora/turn")
    suspend fun submitTurn(@Body request: AgoraTurnRequestDto): AgoraTurnResponseDto

    @GET("agora/token/{sessionId}")
    suspend fun getAgoraToken(@Path("sessionId") sessionId: String): AgoraTokenResponseDto

    @POST("sessions/{sessionId}/agent/start")
    suspend fun startAgent(@Path("sessionId") sessionId: String): StartAgentResponseDto
}
