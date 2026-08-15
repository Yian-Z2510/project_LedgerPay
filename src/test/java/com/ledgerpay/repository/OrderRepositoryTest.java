package com.ledgerpay.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ledgerpay.entity.Merchant;
import com.ledgerpay.entity.MerchantOrder;
import com.ledgerpay.entity.OrderCurrency;
import com.ledgerpay.entity.OrderStatus;

import jakarta.persistence.EntityManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
class OrderRepositoryTest {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Test
    void savesAndFindsOrderById() {
        Merchant merchant = merchantRepository.saveAndFlush(createMerchant("Persistence Merchant"));
        MerchantOrder order = new MerchantOrder(merchant, 1000L);

        MerchantOrder savedOrder = orderRepository.saveAndFlush(order);
        Optional<MerchantOrder> retrievedOrder = orderRepository.findById(savedOrder.getId());

        assertTrue(retrievedOrder.isPresent());
        assertEquals(merchant.getId(), retrievedOrder.orElseThrow().getMerchant().getId());
        assertEquals(1000L, retrievedOrder.orElseThrow().getAmount());
        assertEquals(OrderCurrency.EUR, retrievedOrder.orElseThrow().getCurrency());
        assertEquals(OrderStatus.CREATED, retrievedOrder.orElseThrow().getStatus());
        assertNull(retrievedOrder.orElseThrow().getCancelledAt());
    }

    @Test
    void generatesUuidWhenOrderIsPersisted() {
        Merchant merchant = merchantRepository.saveAndFlush(createMerchant("UUID Merchant"));
        MerchantOrder order = new MerchantOrder(merchant, 1000L);

        assertNull(order.getId());

        MerchantOrder savedOrder = orderRepository.saveAndFlush(order);

        assertNotNull(savedOrder.getId());
    }

    @Test
    void returnsDatabaseGeneratedTimestampsAfterInsert() {
        Merchant merchant = merchantRepository.saveAndFlush(createMerchant("Timestamp Merchant"));
        MerchantOrder order = new MerchantOrder(merchant, 1000L);

        MerchantOrder savedOrder = orderRepository.saveAndFlush(order);

        assertNotNull(savedOrder.getCreatedAt());
        assertNotNull(savedOrder.getUpdatedAt());
    }

    @Test
    void findByIdAndMerchantIdScopesOrderByOwnership() {
        Merchant owner = merchantRepository.saveAndFlush(createMerchant("Owning Merchant"));
        Merchant differentMerchant = merchantRepository.saveAndFlush(createMerchant("Different Merchant"));
        MerchantOrder order = orderRepository.saveAndFlush(new MerchantOrder(owner, 1000L));

        Optional<MerchantOrder> ownerResult = orderRepository.findByIdAndMerchantId(
                order.getId(),
                owner.getId());
        Optional<MerchantOrder> differentMerchantResult = orderRepository.findByIdAndMerchantId(
                order.getId(),
                differentMerchant.getId());

        assertTrue(ownerResult.isPresent());
        assertEquals(order.getId(), ownerResult.orElseThrow().getId());
        assertTrue(differentMerchantResult.isEmpty());
    }

    @Test
    void findByMerchantIdOrderByCreatedAtDescReturnsOnlyOwnedOrders() {
        Merchant owner = merchantRepository.saveAndFlush(createMerchant("Listing Merchant"));
        Merchant differentMerchant = merchantRepository.saveAndFlush(createMerchant("Other Listing Merchant"));
        MerchantOrder olderOrder = orderRepository.saveAndFlush(new MerchantOrder(owner, 1000L));
        MerchantOrder newerOrder = orderRepository.saveAndFlush(new MerchantOrder(owner, 2000L));
        MerchantOrder otherMerchantOrder = orderRepository.saveAndFlush(
                new MerchantOrder(differentMerchant, 3000L));

        Instant olderCreatedAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant newerCreatedAt = olderCreatedAt.plusSeconds(1);
        Instant otherMerchantCreatedAt = newerCreatedAt.plusSeconds(1);
        updateCreatedAt(olderOrder.getId(), olderCreatedAt);
        updateCreatedAt(newerOrder.getId(), newerCreatedAt);
        updateCreatedAt(otherMerchantOrder.getId(), otherMerchantCreatedAt);
        entityManager.clear();

        List<MerchantOrder> orders = orderRepository.findByMerchantIdOrderByCreatedAtDesc(owner.getId());
        List<UUID> orderIds = orders.stream()
                .map(MerchantOrder::getId)
                .toList();

        assertEquals(List.of(newerOrder.getId(), olderOrder.getId()), orderIds);
        assertTrue(orders.get(0).getCreatedAt().isAfter(orders.get(1).getCreatedAt()));
    }

    @Test
    void rejectsZeroAmount() {
        Merchant merchant = merchantRepository.saveAndFlush(createMerchant("Zero Amount Merchant"));

        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertOrder(UUID.randomUUID(), merchant.getId(), 0L, "EUR", "CREATED", null));
    }

    @Test
    void rejectsNegativeAmount() {
        Merchant merchant = merchantRepository.saveAndFlush(createMerchant("Negative Amount Merchant"));

        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertOrder(UUID.randomUUID(), merchant.getId(), -1L, "EUR", "CREATED", null));
    }

    @Test
    void rejectsUnsupportedCurrency() {
        Merchant merchant = merchantRepository.saveAndFlush(createMerchant("Currency Merchant"));

        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertOrder(UUID.randomUUID(), merchant.getId(), 1000L, "GBP", "CREATED", null));
    }

    @Test
    void rejectsUnknownStatus() {
        Merchant merchant = merchantRepository.saveAndFlush(createMerchant("Status Merchant"));

        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertOrder(UUID.randomUUID(), merchant.getId(), 1000L, "EUR", "UNKNOWN", null));
    }

    @Test
    void rejectsCancelledStatusWithoutCancelledAt() {
        Merchant merchant = merchantRepository.saveAndFlush(createMerchant("Cancelled Merchant"));

        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertOrder(UUID.randomUUID(), merchant.getId(), 1000L, "EUR", "CANCELLED", null));
    }

    @Test
    void rejectsNonCancelledStatusWithCancelledAt() {
        Merchant merchant = merchantRepository.saveAndFlush(createMerchant("Active Order Merchant"));

        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertOrder(
                        UUID.randomUUID(),
                        merchant.getId(),
                        1000L,
                        "EUR",
                        "CREATED",
                        Instant.now()));
    }

    @Test
    void rejectsUnknownMerchant() {
        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertOrder(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        1000L,
                        "EUR",
                        "CREATED",
                        null));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void databaseRefreshesUpdatedAtWithoutChangingCreatedAt() throws InterruptedException {
        Merchant savedMerchant = merchantRepository.saveAndFlush(createMerchant("Updated Timestamp Merchant"));
        MerchantOrder savedOrder = null;

        try {
            savedOrder = orderRepository.saveAndFlush(new MerchantOrder(savedMerchant, 1000L));
            Instant originalCreatedAt = savedOrder.getCreatedAt();
            Instant originalUpdatedAt = savedOrder.getUpdatedAt();

            Thread.sleep(10);
            savedOrder.setAmount(1200L);
            orderRepository.saveAndFlush(savedOrder);
            MerchantOrder reloadedOrder = orderRepository.findById(savedOrder.getId())
                    .orElseThrow();

            assertEquals(originalCreatedAt, reloadedOrder.getCreatedAt());
            assertTrue(reloadedOrder.getUpdatedAt().isAfter(originalUpdatedAt));
        } finally {
            if (savedOrder != null) {
                orderRepository.deleteById(savedOrder.getId());
            }
            merchantRepository.deleteById(savedMerchant.getId());
        }
    }

    private Merchant createMerchant(String name) {
        String uniqueValue = UUID.randomUUID().toString().replace("-", "");
        return new Merchant(
                name,
                uniqueValue + "@example.com",
                uniqueValue.repeat(2));
    }

    private void insertOrder(
            UUID orderId,
            UUID merchantId,
            long amount,
            String currency,
            String status,
            Instant cancelledAt) {
        jdbcTemplate.update(
                """
                INSERT INTO merchant_order (
                    id,
                    merchant_id,
                    amount,
                    currency,
                    status,
                    cancelled_at
                )
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                orderId,
                merchantId,
                amount,
                currency,
                status,
                cancelledAt == null ? null : Timestamp.from(cancelledAt));
    }

    private void updateCreatedAt(UUID orderId, Instant createdAt) {
        jdbcTemplate.update(
                "UPDATE merchant_order SET created_at = ? WHERE id = ?",
                Timestamp.from(createdAt),
                orderId);
    }
}
