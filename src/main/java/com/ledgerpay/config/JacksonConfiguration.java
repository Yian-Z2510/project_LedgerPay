package com.ledgerpay.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

@Configuration
public class JacksonConfiguration {

    @Bean
    JsonMapperBuilderCustomizer strictIntegerCoercionCustomizer() {
        return builder -> builder.withCoercionConfig(
                LogicalType.Integer,
                coercionConfig -> {
                    coercionConfig.setCoercion(
                            CoercionInputShape.Float,
                            CoercionAction.Fail);
                    coercionConfig.setCoercion(
                            CoercionInputShape.String,
                            CoercionAction.Fail);
                    coercionConfig.setCoercion(
                            CoercionInputShape.EmptyString,
                            CoercionAction.Fail);
                });
    }
}
