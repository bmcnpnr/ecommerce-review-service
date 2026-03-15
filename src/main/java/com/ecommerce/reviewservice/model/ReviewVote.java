package com.ecommerce.reviewservice.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "review_votes",
    uniqueConstraints = @UniqueConstraint(columnNames = {"review_id", "customer_id"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ReviewVote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "review_id", nullable = false)
    private Review review;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Column(nullable = false)
    private Boolean helpful;

    @Column(name = "voted_at", nullable = false)
    @Builder.Default
    private LocalDateTime votedAt = LocalDateTime.now();
}
