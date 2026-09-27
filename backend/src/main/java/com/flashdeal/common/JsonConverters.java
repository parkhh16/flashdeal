package com.flashdeal.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 스펙, 이미지 목록처럼 "상품과 항상 같이 읽고 따로 조회하지 않는" 값은 별도 테이블 대신 JSON 문자열 컬럼에 둔다.
 * 조인과 N+1이 사라지고, H2와 MySQL 모두 varchar라서 DB 방언 차이도 없다.
 */
public final class JsonConverters {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonConverters() {
    }

    @Converter
    public static class StringMap implements AttributeConverter<Map<String, String>, String> {
        @Override
        public String convertToDatabaseColumn(Map<String, String> attribute) {
            return write(attribute == null || attribute.isEmpty() ? null : attribute);
        }

        @Override
        public Map<String, String> convertToEntityAttribute(String dbData) {
            return dbData == null ? new LinkedHashMap<>() : read(dbData, new TypeReference<LinkedHashMap<String, String>>() {
            });
        }
    }

    @Converter
    public static class StringList implements AttributeConverter<List<String>, String> {
        @Override
        public String convertToDatabaseColumn(List<String> attribute) {
            return write(attribute == null || attribute.isEmpty() ? null : attribute);
        }

        @Override
        public List<String> convertToEntityAttribute(String dbData) {
            return dbData == null ? List.of() : read(dbData, new TypeReference<List<String>>() {
            });
        }
    }

    private static String write(Object value) {
        if (value == null) return null;
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static <T> T read(String json, TypeReference<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
