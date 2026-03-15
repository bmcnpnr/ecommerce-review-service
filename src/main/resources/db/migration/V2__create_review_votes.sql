CREATE TABLE review_votes (
    id BIGSERIAL PRIMARY KEY,
    review_id BIGINT NOT NULL REFERENCES reviews(id) ON DELETE CASCADE,
    customer_id VARCHAR(255) NOT NULL,
    helpful BOOLEAN NOT NULL,
    voted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_vote_review_customer UNIQUE (review_id, customer_id)
);

CREATE INDEX idx_review_votes_review_id ON review_votes(review_id);
