import Foundation

/// Turns per-photo AI answers (type + place) into folders (albums on iOS):
/// - screenshots, WhatsApp images and documents always go to their own folder;
/// - photos taken AWAY from home (EXIF time-zone offset differs from the home zone at that date,
///   or GPS country differs from the home country) are split into trips - runs of away photos with
///   no gap over `tripGap` - and every photo of a trip goes to "<country>-trip", the country voted
///   from GPS lookups (double weight) and the AI's place answers, else "trip-<mon>-<yyyy>";
/// - everything else goes to its type folder (food, people, pets...).
///
/// Same rules as the Android app's `SmartFolders` (Trips.kt).
public enum SmartFolders {

    public static let types = [
        "travel", "food", "people", "pets", "nature", "city", "home", "shopping",
        "documents", "screenshots", "vehicles", "events", "art", "other",
    ]
    private static let synonyms: [String: String] = [
        "screenshot": "screenshots", "document": "documents", "pet": "pets", "animals": "pets",
        "animal": "pets", "person": "people", "family": "people", "selfie": "people",
        "landscape": "nature", "scenery": "nature", "car": "vehicles", "vehicle": "vehicles",
        "event": "events", "concert": "events", "work": "documents", "receipt": "documents",
        "drink": "food", "restaurant": "food", "product": "shopping", "products": "shopping",
        "architecture": "city", "building": "city", "fashion": "people", "sports": "events",
    ]
    private static let countrySlugs: [String: String] = [
        "united states": "usa", "united states of america": "usa", "us": "usa", "u.s.": "usa",
        "united kingdom": "uk", "great britain": "uk", "england": "uk",
        "united arab emirates": "uae", "south korea": "korea", "republic of korea": "korea",
    ]

    public static let tripGap: TimeInterval = 2 * 24 * 3600

    /// Model answer -> one of `types` ("Screenshot", "pets." -> "screenshots", "pets").
    public static func normalizeType(_ raw: String) -> String {
        var t = raw.lowercased().trimmingCharacters(in: .whitespacesAndNewlines)
            .trimmingCharacters(in: CharacterSet(charactersIn: ".\"'*"))
        if let i = t.firstIndex(where: { $0 == " " || $0 == "," }) { t = String(t[..<i]) }
        if types.contains(t) { return t }
        return synonyms[t] ?? "other"
    }

    /// "Japan." -> "Japan"; "none" / "unknown" / "" -> "".
    public static func normalizePlace(_ raw: String?) -> String {
        guard let raw else { return "" }
        let p = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            .trimmingCharacters(in: CharacterSet(charactersIn: ".\"'*"))
            .trimmingCharacters(in: .whitespaces)
        return p.isEmpty || ["none", "unknown", "n/a", "na", "null", "home"].contains(p.lowercased()) ? "" : p
    }

    /// "Japan" -> "japan", "United States" -> "usa".
    public static func placeSlug(_ place: String) -> String {
        let k = place.lowercased().trimmingCharacters(in: .whitespaces)
        if let s = countrySlugs[k] { return s }
        return slug(k)
    }

    /// Lowercase kebab-case, letters and digits only.
    public static func slug(_ s: String) -> String {
        var out = ""
        var dash = false
        for ch in s.lowercased() {
            if ch.isASCII && (ch.isLetter || ch.isNumber) {
                out.append(ch); dash = false
            } else if !dash && !out.isEmpty {
                out.append("-"); dash = true
            }
        }
        return out.hasSuffix("-") ? String(out.dropLast()) : out
    }

    /// Home time-zone offset in minutes at `date`, DST included.
    public static func homeOffsetMin(_ date: Date, zone: TimeZone = .current) -> Int {
        zone.secondsFromGMT(for: date) / 60
    }

    /// - Parameters:
    ///   - geoCountry: country names from the geocoder for photos with GPS (may be empty)
    ///   - homeCountry: the device's country name ("United States"), or nil if unknown
    /// - Returns: photo id -> folder name, for every photo that has an AI result
    public static func assign(
        _ photos: [PhotoInfo],
        results: [String: AiResult],
        geoCountry: [String: String] = [:],
        homeCountry: String? = nil,
        zone: TimeZone = .current
    ) -> [String: String] {
        let scanned = photos.filter { results[$0.id] != nil }
        func typeOf(_ p: PhotoInfo) -> String {
            if p.isScreenshot { return "screenshots" }
            if p.isWhatsApp { return "whatsapp" }
            let r = results[p.id]!
            return r.type.isEmpty ? normalizeType(r.folder) : r.type
        }
        func fixedFolder(_ t: String) -> Bool { t == "screenshots" || t == "documents" || t == "whatsapp" }
        func away(_ p: PhotoInfo) -> Bool {
            guard let taken = p.taken else { return false }
            if let off = p.utcOffsetMin, off != homeOffsetMin(taken, zone: zone) { return true }
            if let g = geoCountry[p.id], let home = homeCountry { return g.lowercased() != home.lowercased() }
            return false
        }

        // 1. trips = runs of away photos, split on long gaps or a different time zone
        let awayPhotos = scanned.filter { !fixedFolder(typeOf($0)) && away($0) }
            .sorted { ($0.taken ?? .distantPast) < ($1.taken ?? .distantPast) }
        var trips: [[PhotoInfo]] = []
        for p in awayPhotos {
            if let prev = trips.last?.last, let a = prev.taken, let b = p.taken,
               b.timeIntervalSince(a) <= tripGap,
               prev.utcOffsetMin == nil || p.utcOffsetMin == nil || prev.utcOffsetMin == p.utcOffsetMin {
                trips[trips.count - 1].append(p)
            } else {
                trips.append([p])
            }
        }
        var out: [String: String] = [:]
        let monthFmt = DateFormatter()
        monthFmt.locale = Locale(identifier: "en_US_POSIX")
        monthFmt.timeZone = zone
        monthFmt.dateFormat = "MMM-yyyy"
        for trip in trips {
            var votes: [String: Int] = [:]
            for p in trip {
                if let g = geoCountry[p.id] { votes[placeSlug(g), default: 0] += 2 }
                let place = results[p.id]!.place
                if !place.isEmpty { votes[placeSlug(place), default: 0] += 1 }
            }
            // ties break alphabetically so the name is stable
            let best = votes.max { $0.value != $1.value ? $0.value < $1.value : $0.key > $1.key }?.key
            let name = best.map { "\($0)-trip" } ?? "trip-" + monthFmt.string(from: trip[0].taken!).lowercased()
            for p in trip { out[p.id] = name }
        }
        // 2. everything else: its type; a clear travel photo with a place but no time info -> that trip
        for p in scanned where out[p.id] == nil {
            let t = typeOf(p)
            let place = results[p.id]!.place
            out[p.id] = (t == "travel" && !place.isEmpty && p.utcOffsetMin == nil) ? "\(placeSlug(place))-trip" : t
        }
        return out
    }
}

/// A folder of photos to file (one album), newest first in the UI.
public struct FolderGroup: Identifiable, Hashable, Sendable {
    public var id: String { name }
    public var name: String
    public var summary: String
    public var photos: [PhotoInfo]

    public var first: Date? { photos.compactMap(\.taken).min() }
    public var last: Date? { photos.compactMap(\.taken).max() }

    /// "2026-09-12 → 2026-09-15", in the photos' own time zone when known.
    public func dateRangeLabel(zone: TimeZone = .current) -> String {
        let fmt = DateFormatter()
        fmt.locale = Locale(identifier: "en_US_POSIX")
        fmt.dateFormat = "yyyy-MM-dd"
        func label(_ p: PhotoInfo?) -> String? {
            guard let p, let t = p.taken else { return nil }
            fmt.timeZone = p.utcOffsetMin.flatMap { TimeZone(secondsFromGMT: $0 * 60) } ?? zone
            return fmt.string(from: t)
        }
        let dated = photos.filter { $0.taken != nil }.sorted { $0.taken! < $1.taken! }
        guard let a = label(dated.first), let b = label(dated.last) else { return "unknown dates" }
        return a == b ? a : "\(a) → \(b)"
    }
}

public enum Folders {
    /// Groups photos by their assigned folder; the summary is the caption of the middle photo.
    public static func group(_ photos: [PhotoInfo], results: [String: AiResult], folders: [String: String]) -> [FolderGroup] {
        var buckets: [String: [PhotoInfo]] = [:]
        var order: [String] = []
        for p in photos {
            guard let name = folders[p.id] ?? results[p.id]?.folder else { continue }
            if buckets[name] == nil { order.append(name) }
            buckets[name, default: []].append(p)
        }
        return order.map { name in
            let ps = buckets[name]!
            let sorted = ps.sorted { ($0.taken ?? .distantPast) < ($1.taken ?? .distantPast) }
            let mid = sorted[sorted.count / 2]
            return FolderGroup(name: name, summary: results[mid.id]?.description ?? "", photos: ps)
        }.sorted { ($0.last ?? .distantPast) > ($1.last ?? .distantPast) }
    }
}
