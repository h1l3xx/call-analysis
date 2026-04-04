package com.malikov.config

import io.ktor.server.application.*
import io.ktor.server.metrics.micrometer.*
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.core.instrument.binder.system.UptimeMetrics
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry

object AppMetrics {
    val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

    val callsProcessed: Counter = Counter.builder("malikov_calls_processed_total")
        .description("Total calls processed")
        .register(registry)

    val callsInternal: Counter = Counter.builder("malikov_calls_internal_total")
        .description("Internal calls processed")
        .register(registry)

    val callsExternal: Counter = Counter.builder("malikov_calls_external_total")
        .description("External calls processed")
        .register(registry)

    val batchesCompleted: Counter = Counter.builder("malikov_batches_completed_total")
        .description("Total batches completed")
        .register(registry)

    val batchesFailed: Counter = Counter.builder("malikov_batches_failed_total")
        .description("Total batches failed")
        .register(registry)

    val llmEvaluationTimer: Timer = Timer.builder("malikov_llm_evaluation_seconds")
        .description("LLM evaluation duration")
        .register(registry)

    val transcriptionTimer: Timer = Timer.builder("malikov_pipeline_transcription_seconds")
        .description("Pipeline transcription duration")
        .register(registry)

    val callsFailed: Counter = Counter.builder("malikov_calls_failed_total")
        .description("Total calls that failed processing")
        .register(registry)

    val telegramMessagesSent: Counter = Counter.builder("malikov_telegram_messages_total")
        .description("Total Telegram messages sent")
        .register(registry)
}

fun Application.configureMetrics() {
    install(MicrometerMetrics) {
        registry = AppMetrics.registry
        meterBinders = listOf(
            JvmMemoryMetrics(),
            JvmGcMetrics(),
            JvmThreadMetrics(),
            ClassLoaderMetrics(),
            ProcessorMetrics(),
            UptimeMetrics(),
        )
    }
}
