package com.example.tokenization.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * JPA entity storing encrypted credit card number material and its derived token.
 *
 * Notes:
 * - Credit card number is never stored in clear; only encrypted bytes + nonce are saved.
 * - ccNumberHash is an HMAC of the credit card number used for deterministic lookups.
 * - token is unique; collisionCounter records how many increments were needed.
 *
 * Observability notes:
 * - Avoid logging this entity directly to prevent leaking binary fields or PII. Prefer explicit, masked logs.
 * - Indexes on token and ccNumberHash support fast lookups; monitor query latencies to validate index efficacy.
 */
@Entity
@Table(name = "CARD_TOKENS", indexes = {
    @Index(name = "IDX_TOKEN", columnList = "TOKEN"),
    @Index(name = "IDX_CC_NUMBER_HASH", columnList = "CC_NUMBER_HASH")
})
@Getter
@Setter
public class CardToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "TOKEN", length = 64, nullable = false, unique = true)
    private String token;

    @Lob
    @Column(name = "ENCRYPTED_CC_NUMBER", nullable = false)
    private byte[] encryptedCcNumber;

    @Column(name = "NONCE", nullable = false)
    private byte[] nonce;

    @Lob
    @Column(name = "ENCRYPTED_DATA_KEY", nullable = false)
    private byte[] encryptedDataKey;

    // Optional Additional Authenticated Data used in AEAD (not used currently)
    @Lob
    @Column(name = "AAD")
    private String aad;

    @Column(name = "MERCHANT_ID")
    private String merchantId;

    @Column(name = "CREATED_AT")
    private OffsetDateTime createdAt;

    @Column(name = "CC_NUMBER_HASH", length = 64, nullable = false)
    private String ccNumberHash;

    @Column(name = "COLLISION_COUNTER", nullable = false)
    private int collisionCounter = 0;

    @PrePersist
    public void prePersist() {
        this.createdAt = OffsetDateTime.now();
    }

    // Lombok generates getters and setters; see @Getter/@Setter annotations above.
}
