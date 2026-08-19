delete from bible_embeddings embedding
where embedding.verse_id in (
  select verse.id
  from bible_verses verse
  join bible_books book on book.id = verse.book_id
  where verse.version = 'KOR1910'
    and book.code = '1PE'
    and verse.chapter = 5
    and not exists (select 1 from highlights highlight where highlight.verse_id = verse.id)
    and not exists (select 1 from bookmarks bookmark where bookmark.verse_id = verse.id)
);

delete from bible_verses verse
using bible_books book
where book.id = verse.book_id
  and verse.version = 'KOR1910'
  and book.code = '1PE'
  and verse.chapter = 5
  and not exists (select 1 from highlights highlight where highlight.verse_id = verse.id)
  and not exists (select 1 from bookmarks bookmark where bookmark.verse_id = verse.id);

update bible_chapters chapter
set content_status = 'EMPTY',
    verse_count = 0,
    source_path = null,
    source_checksum = null,
    imported_at = now()
from bible_books book
where book.id = chapter.book_id
  and chapter.version = 'KOR1910'
  and book.code = '1PE'
  and chapter.chapter = 5
  and not exists (
    select 1
    from bible_verses verse
    where verse.version = chapter.version
      and verse.book_id = chapter.book_id
      and verse.chapter = chapter.chapter
  );
