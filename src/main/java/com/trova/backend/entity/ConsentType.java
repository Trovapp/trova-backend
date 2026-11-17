package com.trova.backend.entity;

// 새 값 추가 시 운영 DB의 enum 검사 제약도 수동 변경해야 한다(ADR 0049).
public enum ConsentType {
    TERMS,
    PRIVACY,
    AGE_OVER_14
}
