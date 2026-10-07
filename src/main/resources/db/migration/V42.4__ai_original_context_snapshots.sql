ALTER TABLE ai_answer_history
    ADD COLUMN context_snapshot_ciphertext TEXT,
    ADD COLUMN context_snapshot_expires_at TIMESTAMPTZ;

ALTER TABLE ai_answer_history ADD CONSTRAINT ai_history_context_snapshot_pair
    CHECK ((context_snapshot_ciphertext IS NULL) = (context_snapshot_expires_at IS NULL));

CREATE INDEX ix_ai_history_context_snapshot_expiry
    ON ai_answer_history(context_snapshot_expires_at, user_id)
    WHERE context_snapshot_ciphertext IS NOT NULL;

-- 이전 API revision의 삭제도 복원 문맥을 남기지 않습니다.
-- legacy는 history→user 순서일 수 있으므로 NOWAIT 충돌은 삭제 TX 전체를 실패시킵니다.
-- 예외를 삼켜 삭제만 성공시키지 않으며, 호출자는 삭제 재시도/다음 정리 주기로 복구합니다.
CREATE FUNCTION invalidate_deleted_ai_history_contexts() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    affected_user UUID;
BEGIN
    -- 먼저 모든 영향을 받는 사용자의 fence를 획득합니다. 0-row DELETE는 아무 일도 하지 않습니다.
    FOR affected_user IN SELECT DISTINCT user_id FROM deleted_ai_history_rows ORDER BY user_id
    LOOP
        PERFORM id FROM users WHERE id = affected_user FOR UPDATE NOWAIT;
        -- 계정 CASCADE 삭제로 user가 없어도 아래 문맥 파기는 계속 수행합니다.
    END LOOP;

    UPDATE ai_answer_history
       SET context_snapshot_ciphertext = NULL, context_snapshot_expires_at = NULL
     WHERE user_id IN (SELECT DISTINCT user_id FROM deleted_ai_history_rows)
       AND (context_snapshot_ciphertext IS NOT NULL OR context_snapshot_expires_at IS NOT NULL);

    DELETE FROM ai_conversations
     WHERE user_id IN (SELECT DISTINCT user_id FROM deleted_ai_history_rows);
    RETURN NULL;
END;
$$;

CREATE TRIGGER ai_history_delete_context_guard
AFTER DELETE ON ai_answer_history
REFERENCING OLD TABLE AS deleted_ai_history_rows
FOR EACH STATEMENT EXECUTE FUNCTION invalidate_deleted_ai_history_contexts();
