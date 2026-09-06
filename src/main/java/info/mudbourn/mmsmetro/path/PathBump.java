package info.mudbourn.mmsmetro.path;

// A trackside announcement trigger resolved onto the path. When a train's head
// crosses this arc position, it announces the upcoming station: the fields here
// are copied from the next station ahead of the bump at path-build time, so the
// bump stays a pure trigger with the station as the single source of truth.
public record PathBump(
    double arc,
    String stationName,
    String exitDirection,
    boolean hub,
    String transferLine,
    boolean terminal) {
}
