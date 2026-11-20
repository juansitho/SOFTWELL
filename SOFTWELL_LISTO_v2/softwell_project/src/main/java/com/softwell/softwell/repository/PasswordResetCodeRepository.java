package com.softwell.softwell.repository;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.softwell.softwell.model.PasswordResetCode;

public interface PasswordResetCodeRepository extends MongoRepository<PasswordResetCode, String> {
}
