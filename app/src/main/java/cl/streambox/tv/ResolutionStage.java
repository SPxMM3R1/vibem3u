package cl.streambox.tv;

/** Stable, non-sensitive stages emitted while a dynamic source is resolved. */
public enum ResolutionStage {
    SESSION,
    CATALOG_REQUEST,
    CATALOG_PAGE,
    CATALOG_PARSED,
    CATALOG_MATCHING,
    ALIAS_ATTEMPT,
    SOURCE_REQUEST,
    SOURCE_CANDIDATE,
    PAGE_REQUEST,
    PAGE_PARSED,
    TOKEN_REQUEST,
    SOURCE_BUILDING,
    HLS_PLAYLIST,
    HLS_VARIANT,
    HLS_SEGMENT,
    SOURCE_FOUND,
    CACHE_REUSED
}
