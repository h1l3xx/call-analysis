package com.malikov.service

import com.malikov.db.*
import com.malikov.pipeline.PipelineClient
import com.malikov.pipeline.PipelineResultWriter
import com.malikov.telegram.BatchNotificationService
import io.mockk.*
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.Test

/**
 * Проверяет, что при батчевой обработке звонка выбирается правильный скрипт
 * на основе номера телефона менеджера → менеджер → отдел → политика отдела → скрипт.
 *
 * Логика в BatchProcessingService.evaluateSingle:
 *   1. По managerId загружается менеджер → его departmentId
 *   2. По (departmentId, callDirection) резолвится DepartmentCallPolicy
 *   3. Из политики берётся scriptId → ScriptDetailRow
 *   4. Если политики нет → ScriptRepository.findDefault()
 *   5. Если скрипта нет совсем → generic internalCallEvaluator.evaluate()
 */
class BatchScriptSelectionTest {

    // ─── Shared fixtures ─────────────────────────────────────────────────────

    private val schema     = "tenant_test"
    private val callId     = UUID.randomUUID()
    private val managerId  = UUID.randomUUID()
    private val departmentId = UUID.randomUUID()
    private val scriptId   = UUID.randomUUID()
    private val policyId   = UUID.randomUUID()

    private val transcription = "Здравствуйте, чем могу помочь?"

    // ─── Mocks ────────────────────────────────────────────────────────────────

    private val callRepo              = mockk<CallRepository>(relaxed = true)
    private val managerRepo           = mockk<ManagerRepository>()
    private val policyRepo            = mockk<DepartmentCallPolicyRepository>()
    private val scriptRepo            = mockk<ScriptRepository>()
    private val internalCallEvaluator = mockk<InternalCallEvaluator>(relaxed = true)
    private val resultWriter          = mockk<PipelineResultWriter>(relaxed = true)

    private val service = BatchProcessingService(
        pipelineClient            = mockk(relaxed = true),
        resultWriter              = resultWriter,
        batchRepo                 = mockk(relaxed = true),
        callRepo                  = callRepo,
        managerRepo               = managerRepo,
        scriptRepo                = scriptRepo,
        policyRepo                = policyRepo,
        internalCallEvaluator     = internalCallEvaluator,
        batchSummaryService       = mockk(relaxed = true),
        batchNotificationService  = null,
    )

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun callRow(
        callType: String = "external",
        callDirection: String? = "external_outgoing",
        mgId: UUID? = managerId,
        secondMgId: UUID? = null,
    ) = CallRow(
        id                = callId,
        managerId         = mgId,
        managerName       = "Тест Менеджер",
        secondManagerId   = secondMgId,
        secondManagerName = null,
        scriptId          = null,
        scriptName        = null,
        status            = "transcribed_only",
        source            = "bulk_upload",
        batchId           = UUID.randomUUID(),
        callType          = callType,
        callDirection     = callDirection,
        audioS3Key        = null,
        audioFilename     = "01.01.2024_10-00-00_1640 (796490), 89248330131_Исходящий.mp3",
        durationSeconds   = null,
        failedStep        = null,
        errorMessage      = null,
        createdAt         = System.currentTimeMillis(),
        finishedAt        = null,
    )

    private fun managerRow(deptId: UUID? = departmentId) = ManagerRow(
        id             = managerId,
        userId         = UUID.randomUUID(),
        fullName       = "Тест Менеджер",
        email          = "manager@test.com",
        departmentId   = deptId,
        departmentName = "Отдел продаж",
        extension      = "1640",
        phoneNumber    = null,
        isActive       = true,
        createdAt      = System.currentTimeMillis(),
    )

    private fun policy(sid: UUID? = scriptId) = DepartmentCallPolicyRow(
        id                 = policyId,
        departmentId       = departmentId,
        secondDepartmentId = null,
        callDirection      = "external_outgoing",
        scriptId           = sid,
        promptTemplateId   = "external_eval",
        createdAt          = System.currentTimeMillis(),
        updatedAt          = System.currentTimeMillis(),
    )

    private fun scriptDetail(sid: UUID = scriptId) = ScriptDetailRow(
        script = ScriptRow(
            id            = sid,
            name          = "Скрипт продаж",
            callType      = "external",
            description   = null,
            isActive      = true,
            criteriaCount = 2,
            createdAt     = System.currentTimeMillis(),
            updatedAt     = System.currentTimeMillis(),
        ),
        criteria = listOf(
            CriterionRow(
                id          = 1,
                orderNum    = 1,
                name        = "Приветствие",
                description = "Менеджер поздоровался",
                groupType   = "required",
                weight      = 1.0,
                scoringType = "binary",
                isActive    = true,
            ),
        ),
    )

    // ─── Тест 1: политика отдела указывает на конкретный скрипт ─────────────

    @Test
    fun `внешний исходящий — политика отдела содержит скрипт — используется этот скрипт`() {
        every { callRepo.findById(schema, callId) } returns callRow()
        every { callRepo.findTranscription(schema, callId) } returns transcription
        every { managerRepo.findById(schema, managerId) } returns managerRow()
        every { policyRepo.resolvePolicy(schema, departmentId, null, "external_outgoing") } returns policy()
        every { scriptRepo.findById(schema, scriptId) } returns scriptDetail()
        every { internalCallEvaluator.evaluateWithCriteria(any(), any(), any(), any(), any()) } returns "{}"

        runBlocking { service.evaluateSingle(schema, callId) }

        verify {
            resultWriter.saveQualityFromJson(schema, callId, scriptId, any())
        }
        verify(exactly = 0) { scriptRepo.findDefault(any()) }
    }

    // ─── Тест 2: нет политики для отдела → глобальная политика со скриптом ──

    @Test
    fun `внешний входящий — нет политики отдела — используется глобальная политика`() {
        val globalScriptId = UUID.randomUUID()
        val globalPolicy = DepartmentCallPolicyRow(
            id                 = UUID.randomUUID(),
            departmentId       = null,     // глобальная (без привязки к отделу)
            secondDepartmentId = null,
            callDirection      = "external_incoming",
            scriptId           = globalScriptId,
            promptTemplateId   = "external_eval",
            createdAt          = System.currentTimeMillis(),
            updatedAt          = System.currentTimeMillis(),
        )

        every { callRepo.findById(schema, callId) } returns callRow(callDirection = "external_incoming")
        every { callRepo.findTranscription(schema, callId) } returns transcription
        every { managerRepo.findById(schema, managerId) } returns managerRow()
        every { policyRepo.resolvePolicy(schema, departmentId, null, "external_incoming") } returns globalPolicy
        every { scriptRepo.findById(schema, globalScriptId) } returns scriptDetail(globalScriptId)
        every { internalCallEvaluator.evaluateWithCriteria(any(), any(), any(), any(), any()) } returns "{}"

        runBlocking { service.evaluateSingle(schema, callId) }

        verify {
            resultWriter.saveQualityFromJson(schema, callId, globalScriptId, any())
        }
    }

    // ─── Тест 3: нет ни одной политики → используется дефолтный скрипт ──────

    @Test
    fun `внешний звонок без политики — используется дефолтный скрипт тенанта`() {
        val defaultScriptId = UUID.randomUUID()

        every { callRepo.findById(schema, callId) } returns callRow()
        every { callRepo.findTranscription(schema, callId) } returns transcription
        every { managerRepo.findById(schema, managerId) } returns managerRow()
        every { policyRepo.resolvePolicy(schema, departmentId, null, "external_outgoing") } returns null
        every { scriptRepo.findDefault(schema) } returns scriptDetail(defaultScriptId)
        every { internalCallEvaluator.evaluateWithCriteria(any(), any(), any(), any(), any()) } returns "{}"

        runBlocking { service.evaluateSingle(schema, callId) }

        verify {
            resultWriter.saveQualityFromJson(schema, callId, defaultScriptId, any())
        }
        verify(exactly = 0) { scriptRepo.findById(any(), any()) }
    }

    // ─── Тест 4: нет политики и нет скриптов → generic-оценка без скрипта ───

    @Test
    fun `внешний звонок без политики и без скриптов — generic-оценка internalCallEvaluator`() {
        every { callRepo.findById(schema, callId) } returns callRow()
        every { callRepo.findTranscription(schema, callId) } returns transcription
        every { managerRepo.findById(schema, managerId) } returns managerRow()
        every { policyRepo.resolvePolicy(schema, departmentId, null, "external_outgoing") } returns null
        every { scriptRepo.findDefault(schema) } returns null

        runBlocking { service.evaluateSingle(schema, callId) }

        verify { internalCallEvaluator.evaluate(schema, callId, transcription) }
        verify(exactly = 0) { resultWriter.saveQualityFromJson(any(), any(), any<UUID>(), any()) }
    }

    // ─── Тест 5: внутренний звонок с политикой — выбирается скрипт ──────────

    @Test
    fun `внутренний звонок — политика отдела содержит скрипт — скрипт используется`() {
        val internalPolicy = policy().copy(callDirection = "internal_outgoing")

        every { callRepo.findById(schema, callId) } returns callRow(callType = "internal", callDirection = "internal_outgoing")
        every { callRepo.findTranscription(schema, callId) } returns transcription
        every { managerRepo.findById(schema, managerId) } returns managerRow()
        every { policyRepo.resolvePolicy(schema, departmentId, null, "internal_outgoing") } returns internalPolicy
        every { scriptRepo.findById(schema, scriptId) } returns scriptDetail()
        every { internalCallEvaluator.evaluateWithCriteria(any(), any(), any(), any(), any()) } returns "{}"

        runBlocking { service.evaluateSingle(schema, callId) }

        verify {
            resultWriter.saveQualityFromJson(schema, callId, scriptId, any())
        }
        verify(exactly = 0) { internalCallEvaluator.evaluate(any(), any<UUID>(), any()) }
    }

    // ─── Тест 6: внутренний звонок без политики → internalCallEvaluator ──────

    @Test
    fun `внутренний звонок без политики — internalCallEvaluator без скрипта`() {
        every { callRepo.findById(schema, callId) } returns callRow(callType = "internal", callDirection = "internal_outgoing")
        every { callRepo.findTranscription(schema, callId) } returns transcription
        every { managerRepo.findById(schema, managerId) } returns managerRow()
        every { policyRepo.resolvePolicy(schema, departmentId, null, "internal_outgoing") } returns null

        runBlocking { service.evaluateSingle(schema, callId) }

        verify { internalCallEvaluator.evaluate(schema, callId, transcription) }
        verify(exactly = 0) { resultWriter.saveQualityFromJson(any(), any(), any<UUID>(), any()) }
    }

    // ─── Тест 7: менеджер без отдела — резолвится только глобальная политика ─

    @Test
    fun `менеджер без отдела — попытка резолвить политику с departmentId=null`() {
        every { callRepo.findById(schema, callId) } returns callRow()
        every { callRepo.findTranscription(schema, callId) } returns transcription
        every { managerRepo.findById(schema, managerId) } returns managerRow(deptId = null)
        every { policyRepo.resolvePolicy(schema, null, null, "external_outgoing") } returns null
        every { scriptRepo.findDefault(schema) } returns scriptDetail()
        every { internalCallEvaluator.evaluateWithCriteria(any(), any(), any(), any(), any()) } returns "{}"

        runBlocking { service.evaluateSingle(schema, callId) }

        verify {
            policyRepo.resolvePolicy(schema, null, null, "external_outgoing")
        }
        verify {
            resultWriter.saveQualityFromJson(schema, callId, scriptId, any())
        }
    }

    // ─── Тест 8: нет транскрипции → оценка пропускается ─────────────────────

    @Test
    fun `нет транскрипции — evaluateSingle ничего не вызывает на resultWriter`() {
        // managerRepo/policyRepo вызываются до проверки транскрипции — нужны стабы
        every { callRepo.findById(schema, callId) } returns callRow()
        every { callRepo.findTranscription(schema, callId) } returns null
        every { managerRepo.findById(schema, managerId) } returns managerRow()
        every { policyRepo.resolvePolicy(schema, departmentId, null, "external_outgoing") } returns null

        runBlocking { service.evaluateSingle(schema, callId) }

        verify(exactly = 0) { resultWriter.saveQualityFromJson(any(), any(), any<UUID>(), any()) }
        verify(exactly = 0) { internalCallEvaluator.evaluate(any(), any<UUID>(), any()) }
        verify(exactly = 0) { internalCallEvaluator.evaluateWithCriteria(any(), any(), any(), any(), any()) }
    }

    // ─── Тест 9: политика без scriptId (Без скрипта) — template-only оценка ─

    @Test
    fun `политика с scriptId=null — оценка без скрипта, saveQualityFromJson с null scriptId`() {
        every { callRepo.findById(schema, callId) } returns callRow()
        every { callRepo.findTranscription(schema, callId) } returns transcription
        every { managerRepo.findById(schema, managerId) } returns managerRow()
        every { policyRepo.resolvePolicy(schema, departmentId, null, "external_outgoing") } returns policy(sid = null)
        every { internalCallEvaluator.evaluateWithCriteria(any(), any(), any(), any(), any()) } returns "{}"

        runBlocking { service.evaluateSingle(schema, callId) }

        verify {
            resultWriter.saveQualityFromJson(schema, callId, null, any())
        }
        verify(exactly = 0) { scriptRepo.findById(any(), any()) }
    }

    // ─── Тест 10: внутренний звонок — два менеджера из разных отделов ────────

    @Test
    fun `внутренний звонок двух менеджеров — политика пары отделов применяется`() {
        val secondManagerId = UUID.randomUUID()
        val secondDepartmentId = UUID.randomUUID()
        val pairPolicy = DepartmentCallPolicyRow(
            id                 = UUID.randomUUID(),
            departmentId       = departmentId,
            secondDepartmentId = secondDepartmentId,
            callDirection      = "internal_outgoing",
            scriptId           = scriptId,
            promptTemplateId   = "external_eval",
            createdAt          = System.currentTimeMillis(),
            updatedAt          = System.currentTimeMillis(),
        )
        val secondManager = managerRow().copy(
            id           = secondManagerId,
            departmentId = secondDepartmentId,
        )

        every { callRepo.findById(schema, callId) } returns callRow(
            callType      = "internal",
            callDirection = "internal_outgoing",
            mgId          = managerId,
            secondMgId    = secondManagerId,
        )
        every { callRepo.findTranscription(schema, callId) } returns transcription
        every { managerRepo.findById(schema, managerId) } returns managerRow()
        every { managerRepo.findById(schema, secondManagerId) } returns secondManager
        every { policyRepo.resolvePolicy(schema, departmentId, secondDepartmentId, "internal_outgoing") } returns pairPolicy
        every { scriptRepo.findById(schema, scriptId) } returns scriptDetail()
        every { internalCallEvaluator.evaluateWithCriteria(any(), any(), any(), any(), any()) } returns "{}"

        runBlocking { service.evaluateSingle(schema, callId) }

        verify {
            policyRepo.resolvePolicy(schema, departmentId, secondDepartmentId, "internal_outgoing")
        }
        verify {
            resultWriter.saveQualityFromJson(schema, callId, scriptId, any())
        }
    }
}
