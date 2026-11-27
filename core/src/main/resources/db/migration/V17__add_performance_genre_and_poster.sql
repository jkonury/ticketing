ALTER TABLE catalog.performance
    ADD COLUMN genre varchar(16) CHECK (genre IN ('CONCERT', 'MUSICAL', 'PLAY', 'CLASSICAL', 'SPORTS', 'EXHIBITION')),
    ADD COLUMN poster_key varchar(160);

CREATE INDEX idx_performance_genre_id ON catalog.performance (genre, id DESC);
