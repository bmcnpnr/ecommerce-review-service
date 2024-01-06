package com.ecommerce.review.service;

import org.springframework.stereotype.Service;

@Service
public class ReviewService {

    public static final String HELLO_FROM_REVIEW_SERVICE = "Hello from Review Service!";

    public String getHelloMessage() {
        return HELLO_FROM_REVIEW_SERVICE;
    }
}
