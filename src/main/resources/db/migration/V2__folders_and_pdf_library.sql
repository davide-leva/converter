CREATE TABLE folders (
    id TEXT PRIMARY KEY,
    owner TEXT NOT NULL,
    parent_id TEXT REFERENCES folders(id) ON DELETE RESTRICT,
    name TEXT NOT NULL,
    created TEXT NOT NULL
);
CREATE UNIQUE INDEX folders_sibling_names ON folders(owner, COALESCE(parent_id, ''), name COLLATE NOCASE);
CREATE INDEX folders_owner ON folders(owner, parent_id);
ALTER TABLE conversions ADD COLUMN folder_id TEXT REFERENCES folders(id) ON DELETE RESTRICT;
ALTER TABLE conversions ADD COLUMN display_name TEXT;
ALTER TABLE conversions ADD COLUMN kind TEXT NOT NULL DEFAULT 'CONVERSION';
ALTER TABLE conversions ADD COLUMN pages INTEGER;
UPDATE conversions SET display_name = name;
CREATE INDEX conversions_folder ON conversions(owner, folder_id);
CREATE TABLE merge_inputs (
    merge_id TEXT NOT NULL REFERENCES conversions(id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    source_id TEXT REFERENCES conversions(id) ON DELETE RESTRICT,
    name TEXT NOT NULL,
    PRIMARY KEY(merge_id, position)
);
