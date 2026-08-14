-- 같은 장에 대한 AI 생성 작업은 대기/처리 상태에서 하나만 존재할 수 있습니다.
create unique index ux_bible_commentary_generation_jobs_active
    on bible_commentary_generation_jobs (version, book_code, chapter)
    where status in ('PENDING', 'PROCESSING');
