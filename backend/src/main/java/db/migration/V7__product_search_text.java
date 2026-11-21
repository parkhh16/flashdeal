package db.migration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.needsshop.product.SearchText;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 상품 검색용 문자열 컬럼(search_text)을 추가하고 기존 상품을 채운다.
 * 채우는 규칙(스펙 JSON을 읽어 정규화)이 자바 코드라서 SQL이 아니라 자바 마이그레이션으로 한다.
 * 새로 저장·수정되는 상품은 엔티티가 직접 채운다.
 *
 * <p>varchar(4000): product 행의 varchar 합계가 MySQL 행 크기 제한(65,535바이트, utf8mb4는 글자당 4바이트)을
 * 넘지 않는 크기. text로 하면 H2와 MySQL의 타입이 달라져 엔티티 검증(ddl-auto: validate)이 갈린다.
 */
public class V7__product_search_text extends BaseJavaMigration {

    private static final TypeReference<LinkedHashMap<String, String>> SPECS = new TypeReference<>() {
    };

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        try (Statement ddl = connection.createStatement()) {
            ddl.execute("alter table product add column search_text varchar(" + SearchText.MAX_LENGTH + ")");
        }

        ObjectMapper json = new ObjectMapper();
        try (PreparedStatement select = connection.prepareStatement("select id, name, description, detail, specs from product");
             PreparedStatement update = connection.prepareStatement("update product set search_text = ? where id = ?");
             ResultSet rs = select.executeQuery()) {
            int count = 0;
            while (rs.next()) {
                String specsJson = rs.getString("specs");
                Map<String, String> specs = specsJson == null ? Map.of() : json.readValue(specsJson, SPECS);
                update.setString(1, SearchText.build(rs.getString("name"), rs.getString("description"),
                        rs.getString("detail"), specs));
                update.setLong(2, rs.getLong("id"));
                update.addBatch();
                count++;
            }
            if (count > 0) update.executeBatch();
        }
    }
}
