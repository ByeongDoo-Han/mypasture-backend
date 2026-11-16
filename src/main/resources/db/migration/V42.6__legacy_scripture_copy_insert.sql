-- 이전 서버 image가 end_verse 없이 한 절을 저장하는 롤링/롤백 쓰기를 지원합니다.
-- 명시적 범위와 기존 행은 변경하지 않고 기존 NOT NULL/범위 제약을 유지합니다.
CREATE FUNCTION default_legacy_scripture_copy_end_verse() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.end_verse IS NULL THEN
        NEW.end_verse := NEW.verse;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER scripture_copy_legacy_insert_guard
BEFORE INSERT ON scripture_copies
FOR EACH ROW EXECUTE FUNCTION default_legacy_scripture_copy_end_verse();
