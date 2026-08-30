package io.converge.connectors.square;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SquareProperties.class)
class SquareConfiguration {
}

