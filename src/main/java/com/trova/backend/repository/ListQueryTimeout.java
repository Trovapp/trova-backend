package com.trova.backend.repository;

import jakarta.persistence.QueryHint;
import org.hibernate.jpa.SpecHints;
import org.springframework.data.jpa.repository.QueryHints;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 사용자 목록의 SQL 문마다 JDBC 쿼리 기한을 적용한다. JPA 힌트의 단위는 밀리초다. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@QueryHints(@QueryHint(name = SpecHints.HINT_SPEC_QUERY_TIMEOUT, value = "3000"))
@interface ListQueryTimeout {
}
