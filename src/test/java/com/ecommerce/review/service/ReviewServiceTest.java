package com.ecommerce.review.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.ecommerce.review.service.ReviewService.HELLO_FROM_REVIEW_SERVICE;
import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(MockitoExtension.class)
class ReviewServiceTest {

    @InjectMocks
    private ReviewService reviewService;

    @Test
    void testGetHelloMessage() {
        // when
        var result = reviewService.getHelloMessage();

        // then
        assertEquals(HELLO_FROM_REVIEW_SERVICE, result);
    }
}
