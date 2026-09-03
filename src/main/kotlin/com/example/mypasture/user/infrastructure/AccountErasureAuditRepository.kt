package com.example.mypasture.user.infrastructure

import com.example.mypasture.user.domain.AccountErasureAuditEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

/** 개인정보 없이 완료된 계정 파기 작업을 저장하는 감사 원장 repository입니다. */
@Repository
interface AccountErasureAuditRepository : JpaRepository<AccountErasureAuditEntity, UUID>
