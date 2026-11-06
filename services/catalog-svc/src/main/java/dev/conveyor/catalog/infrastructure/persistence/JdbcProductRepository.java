package dev.conveyor.catalog.infrastructure.persistence;

import dev.conveyor.catalog.domain.Product;
import dev.conveyor.catalog.domain.ProductRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Plain SQL through JdbcClient: every statement the service runs is visible here. */
@Repository
class JdbcProductRepository implements ProductRepository {

    private static final String COLUMNS =
            "sku, title, brand, category, price_cents, currency, rating, version, updated_at";

    private static final RowMapper<Product> ROW = JdbcProductRepository::map;

    private final JdbcClient jdbc;

    JdbcProductRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Product> findBySku(String sku) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM product WHERE sku = :sku")
                .param("sku", sku)
                .query(ROW)
                .optional();
    }

    @Override
    public Optional<Product> updatePrice(String sku, long expectedVersion, long newPriceCents) {
        return jdbc.sql("UPDATE product SET price_cents = :price, version = version + 1, updated_at = now()"
                        + " WHERE sku = :sku AND version = :expected RETURNING " + COLUMNS)
                .param("price", newPriceCents)
                .param("sku", sku)
                .param("expected", expectedVersion)
                .query(ROW)
                .optional();
    }

    @Override
    public List<String> allSkus() {
        return jdbc.sql("SELECT sku FROM product").query(String.class).list();
    }

    @Override
    public void forEachProduct(int pageSize, Consumer<List<Product>> page) {
        String after = "";
        while (true) {
            // Keyset pagination: stable and index-only, unlike OFFSET which rescans skipped rows.
            List<Product> rows = jdbc.sql(
                            "SELECT " + COLUMNS + " FROM product WHERE sku > :after ORDER BY sku LIMIT :limit")
                    .param("after", after)
                    .param("limit", pageSize)
                    .query(ROW)
                    .list();
            if (rows.isEmpty()) {
                return;
            }
            page.accept(rows);
            after = rows.getLast().sku();
        }
    }

    @Override
    public long count() {
        return jdbc.sql("SELECT count(*) FROM product").query(Long.class).single();
    }

    private static Product map(ResultSet rs, int rowNum) throws SQLException {
        return new Product(
                rs.getString("sku"),
                rs.getString("title"),
                rs.getString("brand"),
                rs.getString("category"),
                rs.getLong("price_cents"),
                rs.getString("currency"),
                rs.getDouble("rating"),
                rs.getLong("version"),
                rs.getTimestamp("updated_at").toInstant());
    }
}
