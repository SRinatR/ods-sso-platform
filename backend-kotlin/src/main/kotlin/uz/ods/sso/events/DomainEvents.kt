package uz.ods.sso.events

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.domain.PageRequest
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uz.ods.sso.config.OdsProperties
import uz.ods.sso.persistence.DomainOutboxEntity
import uz.ods.sso.persistence.DomainOutboxRepository
import uz.ods.sso.shared.logging.LoggingSanitizer
import tools.jackson.databind.ObjectMapper
import java.time.Instant

@Service
class DomainEventService(
    private val repository: DomainOutboxRepository,
    private val objectMapper: ObjectMapper,
) {
    fun append(tenantId: String, eventType: String, aggregateId: String, payload: Map<String, Any?>) {
        repository.save(
            DomainOutboxEntity(
                tenantId = tenantId,
                eventType = eventType,
                aggregateId = aggregateId,
                payloadJson = objectMapper.writeValueAsString(payload),
            ),
        )
    }
}

@Service
@ConditionalOnProperty(prefix = "ods", name = ["kafka-events-enabled"], havingValue = "true")
class OutboxPublisher(
    private val repository: DomainOutboxRepository,
    private val kafka: KafkaTemplate<String, String>,
    private val properties: OdsProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${OUTBOX_PUBLISH_INTERVAL_MS:1000}")
    @Transactional
    fun publish() {
        if (!properties.kafkaEventsEnabled) return
        repository.findByPublishedAtIsNullOrderByCreatedAt(PageRequest.of(0, 100)).forEach { event ->
            runCatching {
                kafka.send("ods.identity.events", event.aggregateId, event.payloadJson).get()
                event.publishedAt = Instant.now()
            }.onFailure {
                event.attempts += 1
                event.lastError = it.message?.let(LoggingSanitizer::sanitize)?.take(2000)
                log.error(
                    "outbox_publish_failed event_id={} exception_type={}",
                    event.id,
                    it.javaClass.name,
                )
            }
        }
    }
}
