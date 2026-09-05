package com.echopanel.app.data.remote.api

import com.echopanel.app.data.remote.dto.AddCustomQuestionRequestDto
import com.echopanel.app.data.remote.dto.AgoraTokenResponseDto
import com.echopanel.app.data.remote.dto.AgoraTurnRequestDto
import com.echopanel.app.data.remote.dto.AgoraTurnResponseDto
import com.echopanel.app.data.remote.dto.CreateSessionResponseDto
import com.echopanel.app.data.remote.dto.FinalReportDto
import com.echopanel.app.data.remote.dto.ProctoringStatusResponseDto
import com.echopanel.app.data.remote.dto.ReportSignalRequestDto
import com.echopanel.app.data.remote.dto.ReportSignalResponseDto
import com.echopanel.app.data.remote.dto.ScenarioResponseDto
import com.echopanel.app.data.remote.dto.ScriptResponseDto
import com.echopanel.app.data.remote.dto.StartAgentResponseDto
import com.echopanel.app.data.remote.dto.SuggestQuestionsRequestDto
import com.echopanel.app.data.remote.dto.TurnsResponseDto
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

    @GET("sessions/{sessionId}/scenario")
    suspend fun getLatestScenario(@Path("sessionId") sessionId: String): ScenarioResponseDto

    @GET("sessions/{sessionId}/turns")
    suspend fun getTurns(
        @Path("sessionId") sessionId: String,
        @Query("since_index") sinceIndex: Int,
    ): TurnsResponseDto

    // --- Proctoring / cheating detection ---------------------------

    @POST("proctoring/{sessionId}/signal")
    suspend fun reportCheatSignal(
        @Path("sessionId") sessionId: String,
        @Body request: ReportSignalRequestDto,
    ): ReportSignalResponseDto

    @GET("proctoring/{sessionId}/status")
    suspend fun getProctoringStatus(
        @Path("sessionId") sessionId: String,
    ): ProctoringStatusResponseDto

    @POST("proctoring/{sessionId}/flags/{flagId}/acknowledge")
    suspend fun acknowledgeCheatFlag(
        @Path("sessionId") sessionId: String,
        @Path("flagId") flagId: String,
    )

    // --- Shared live script panel -----------------------------------

    @GET("script/{sessionId}")
    suspend fun getScript(
        @Path("sessionId") sessionId: String,
        @Query("since_index") sinceIndex: Int = 0,
    ): ScriptResponseDto

    @POST("script/{sessionId}/suggest")
    suspend fun suggestScriptQuestions(
        @Path("sessionId") sessionId: String,
        @Body request: SuggestQuestionsRequestDto,
    ): ScriptResponseDto

    @POST("script/{sessionId}/custom")
    suspend fun addCustomScriptQuestion(
        @Path("sessionId") sessionId: String,
        @Body request: AddCustomQuestionRequestDto,
    ): ScriptResponseDto

    @POST("script/{sessionId}/entries/{entryId}/mark_used")
    suspend fun markScriptEntryUsed(
        @Path("sessionId") sessionId: String,
        @Path("entryId") entryId: String,
    )
}