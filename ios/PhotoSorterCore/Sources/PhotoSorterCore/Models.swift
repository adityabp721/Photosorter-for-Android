import Foundation

/// What the app knows about one library photo, independent of PhotoKit.
public struct PhotoInfo: Hashable, Sendable {
    /// PHAsset.localIdentifier on iOS.
    public var id: String
    public var fileName: String
    /// Capture time, nil when unknown.
    public var taken: Date?
    /// EXIF OffsetTimeOriginal in minutes (+09:00 -> 540), nil when the camera didn't record it.
    public var utcOffsetMin: Int?
    /// When the photo entered the library; the original of exact copies was added first.
    public var added: Date?
    public var sizeBytes: Int64
    public var uti: String
    public var isScreenshot: Bool
    public var isWhatsApp: Bool
    /// Lives in one of PhotoSorter's own albums only because the app filed it (never true for originals on iOS,
    /// where albums hold references; kept for parity with Android's copy rules).
    public var isOwnCopy: Bool
    public var latitude: Double?
    public var longitude: Double?

    public init(
        id: String, fileName: String = "", taken: Date? = nil, utcOffsetMin: Int? = nil, added: Date? = nil,
        sizeBytes: Int64 = 0, uti: String = "public.jpeg", isScreenshot: Bool = false, isWhatsApp: Bool = false,
        isOwnCopy: Bool = false, latitude: Double? = nil, longitude: Double? = nil
    ) {
        self.id = id; self.fileName = fileName; self.taken = taken; self.utcOffsetMin = utcOffsetMin
        self.added = added; self.sizeBytes = sizeBytes; self.uti = uti; self.isScreenshot = isScreenshot
        self.isWhatsApp = isWhatsApp; self.isOwnCopy = isOwnCopy; self.latitude = latitude; self.longitude = longitude
    }

    private static let rawExtensions: Set<String> = ["dng", "raw", "cr2", "cr3", "nef", "arw", "orf", "rw2", "raf"]

    /// RAW files (ProRAW DNG, camera RAW): never selected for deletion automatically.
    public var isRaw: Bool {
        let u = uti.lowercased()
        if u.contains("raw") || u.contains("dng") { return true }
        return Self.rawExtensions.contains((fileName as NSString).pathExtension.lowercased())
    }
}

/// One photo's AI answer. Sort: folder + description. Organize: also type and place (a country).
public struct AiResult: Hashable, Sendable, Codable {
    public var folder: String
    public var description: String
    public var type: String
    public var place: String

    public init(folder: String, description: String, type: String = "", place: String = "") {
        self.folder = folder; self.description = description; self.type = type; self.place = place
    }
}

/// "+09:00" / "-07:00" / "Z" -> minutes east of UTC.
public func parseUtcOffset(_ raw: String?) -> Int? {
    guard let s = raw?.trimmingCharacters(in: .whitespaces), !s.isEmpty else { return nil }
    if s == "Z" { return 0 }
    let sign: Int
    switch s.first {
    case "+": sign = 1
    case "-": sign = -1
    default: return nil
    }
    let parts = s.dropFirst().split(separator: ":")
    guard let h = Int(parts.first ?? "") else { return nil }
    let m = parts.count > 1 ? Int(parts[1]) ?? 0 : 0
    return sign * (h * 60 + m)
}

/// Bytes as "888 KB" / "1.5 MB" / "2.10 GB".
public func humanBytes(_ b: Int64) -> String {
    if b <= 0 { return "size unknown" }
    if b < 1 << 20 { return "\(b / 1024) KB" }
    if b < 1 << 30 { return String(format: "%.1f MB", Double(b) / Double(1 << 20)) }
    return String(format: "%.2f GB", Double(b) / Double(1 << 30))
}
