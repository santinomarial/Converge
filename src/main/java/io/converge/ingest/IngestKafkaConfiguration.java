package io.converge.ingest;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
class IngestKafkaConfiguration {

    @Bean
    NewTopic inventoryRawTopic() {
        return TopicBuilder.name("inventory.raw").partitions(3).replicas(1).build();
    }
}

