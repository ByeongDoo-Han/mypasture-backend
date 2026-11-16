ALTER TABLE scripture_copies ADD COLUMN end_verse INTEGER;

UPDATE scripture_copies SET end_verse = verse;

ALTER TABLE scripture_copies ALTER COLUMN end_verse SET NOT NULL;
ALTER TABLE scripture_copies ADD CONSTRAINT scripture_copies_range_valid
    CHECK (end_verse > 0 AND end_verse >= verse AND end_verse - verse < 20);
