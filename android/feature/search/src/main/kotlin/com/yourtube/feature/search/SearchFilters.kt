package com.yourtube.feature.search

/**
 * Immutable snapshot of the active search filter selections.
 *
 * ## Why filter groups are mutually exclusive (MVP constraint)
 *
 * The YouTube InnerTube search API accepts a single `sp` query parameter whose
 * value is a base64-encoded protobuf `SearchFilter` message. Each filter group
 * (duration, upload date, type) maps to a distinct field inside that protobuf.
 * Combining selections from multiple groups therefore requires building a
 * multi-field protobuf payload — encoding that is deferred post-MVP.
 *
 * For MVP, selecting a chip in one group clears all other groups so that at
 * most one non-null `sp` value is ever submitted. The [withDuration],
 * [withUploadDate], and [withType] smart constructors enforce this invariant by
 * resetting the two sibling groups to their `Any` defaults.
 *
 * See `docs/api-contracts.md` (Search filters section) for the cross-platform
 * contract and the post-MVP deferral note.
 */
data class SearchFilters(
    val duration: DurationFilter = DurationFilter.Any,
    val uploadDate: UploadDateFilter = UploadDateFilter.Any,
    val type: TypeFilter = TypeFilter.Any,
) {
    /**
     * Returns the single `sp` query parameter to pass to the YouTube API, or
     * null when no filter is active.
     *
     * Priority: duration → upload-date → type. Because only one group
     * may be non-Any at a time (enforced by [withDuration], [withUploadDate],
     * [withType] smart constructors), at most one non-null value is returned.
     */
    val sp: String?
        get() = when {
            duration != DurationFilter.Any -> duration.sp
            uploadDate != UploadDateFilter.Any -> uploadDate.sp
            type != TypeFilter.Any -> type.sp
            else -> null
        }

    /** Select a [DurationFilter], clearing the upload-date and type groups. */
    fun withDuration(value: DurationFilter): SearchFilters =
        SearchFilters(duration = value)

    /** Select an [UploadDateFilter], clearing the duration and type groups. */
    fun withUploadDate(value: UploadDateFilter): SearchFilters =
        SearchFilters(uploadDate = value)

    /** Select a [TypeFilter], clearing the duration and upload-date groups. */
    fun withType(value: TypeFilter): SearchFilters =
        SearchFilters(type = value)
}

enum class DurationFilter(val sp: String?) {
    Any(null),
    Short("EgIYAQ%3D%3D"),
    Medium("EgIYAw%3D%3D"),
    Long("EgIYAg%3D%3D"),
}

enum class UploadDateFilter(val sp: String?) {
    Any(null),
    Today("CAISAhAB"),
    ThisWeek("CAISAhAC"),
    ThisMonth("CAISAhAD"),
    ThisYear("CAISAhAE"),
}

enum class TypeFilter(val sp: String?) {
    Any(null),
    Video("EgIQAQ%3D%3D"),
    Playlist("EgIQAw%3D%3D"),
    Channel("EgIQAg%3D%3D"),
}
