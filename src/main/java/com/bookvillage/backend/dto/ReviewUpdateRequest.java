package com.bookvillage.backend.dto;

import lombok.Data;

@Data
public class ReviewUpdateRequest {
    private Integer rating;
    private String content;
}
