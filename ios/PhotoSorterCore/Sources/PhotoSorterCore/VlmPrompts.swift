import Foundation

/// Downloadable on-device models (both Apache 2.0) and the prompts / answer parsing used with them.
/// Same files, prompts and parsing as the Android app's `VlmAi`.
public enum VlmVariant: String, CaseIterable, Sendable, Codable {
    case standard, lite

    public var label: String { self == .standard ? "Qwen3-VL-2B" : "SmolVLM2-500M" }
    public var modelName: String {
        self == .standard ? "Qwen3VL-2B-Instruct-Q4_K_M.gguf" : "SmolVLM2-500M-Video-Instruct-Q8_0.gguf"
    }
    public var mmprojName: String {
        self == .standard ? "mmproj-Qwen3VL-2B-Instruct-Q8_0.gguf" : "mmproj-SmolVLM2-500M-Video-Instruct-Q8_0.gguf"
    }
    private var repo: String {
        self == .standard ? "Qwen/Qwen3-VL-2B-Instruct-GGUF" : "ggml-org/SmolVLM2-500M-Video-Instruct-GGUF"
    }
    public var modelURL: URL { URL(string: "https://huggingface.co/\(repo)/resolve/main/\(modelName)")! }
    public var mmprojURL: URL { URL(string: "https://huggingface.co/\(repo)/resolve/main/\(mmprojName)")! }
    public var downloadBytes: Int64 { self == .standard ? 1_560_000_000 : 546_000_000 }
    public var minModelBytes: Int64 { self == .standard ? 1_000_000_000 : 400_000_000 }
    public var minMmprojBytes: Int64 { self == .standard ? 400_000_000 : 100_000_000 }
    public var sizeText: String { self == .standard ? "~1.6 GB" : "~550 MB" }
    /// LITE only writes a caption; the folder type comes from keywords in it.
    public var isLite: Bool { self == .lite }
    public var typicalAnswerTokens: Int { isLite ? 18 : 24 }

    /// Devices under this much RAM get LITE as the default (STANDARD needs ~2.5 GB while running).
    public static let liteBelowRamBytes: UInt64 = 6_000_000_000

    public static func recommended(ramBytes: UInt64) -> VlmVariant {
        ramBytes > 0 && ramBytes < liteBelowRamBytes ? .lite : .standard
    }
}

public enum VlmPrompts {
    public static let maxTokens = 60

    /// Organize prompt: the model reports WHAT the photo is and, only for clear travel photos, WHERE.
    public static let trip =
        "Classify this photo from a personal phone gallery. EXIF hints: %@\n" +
        "Answer with ONLY three lines:\n" +
        "type: one of travel, food, people, pets, nature, city, home, shopping, documents, screenshot, vehicles, events, art, other\n" +
        "place: the country where it was taken, ONLY if the photo clearly shows travel away from home " +
        "(a famous landmark, foreign-language signs, a tourist site) or the GPS says so; otherwise none\n" +
        "description: one short sentence, max 12 words"

    /// Sort prompt: topic folder + caption.
    public static let sort =
        "File this photo from a personal gallery. EXIF hints: %@\n" +
        "Pick ONE broad lowercase folder for its main subject. Prefer exactly one of: food, people, " +
        "pets, nature, travel, city, home, shopping, documents, screenshots, vehicles, events, art, other. " +
        "Only invent a new 1-2 word kebab-case folder if none of these fits at all.\n" +
        "Reply with ONLY two lines:\n" +
        "folder: <folder name>\n" +
        "description: <one short sentence, max 15 words>"

    /// LITE prompt: small models follow a plain request far better than a format.
    public static let lite = "Describe this photo in one short sentence. (%@)"

    public static func prompt(for variant: VlmVariant, trip isTrip: Bool, hints: String) -> String {
        let template = variant.isLite ? lite : (isTrip ? trip : sort)
        return template.replacingOccurrences(of: "%@", with: hints)
    }

    /// Parses an answer from `variant` into an AiResult (nil when unusable).
    public static func parse(_ raw: String, variant: VlmVariant) -> AiResult? {
        variant.isLite ? liteResult(raw) : parseOutput(raw)
    }

    /// Parses "type:/place:/description:" or "folder:/description:" lines, tolerating markdown and
    /// several pairs on one line.
    public static func parseOutput(_ raw: String) -> AiResult? {
        let lines = raw.split(whereSeparator: \.isNewline)
            .map { line -> String in
                var s = line.replacingOccurrences(of: "**", with: "").trimmingCharacters(in: .whitespaces)
                while let c = s.first, "-*` ".contains(c) { s.removeFirst() }
                return s.trimmingCharacters(in: .whitespaces)
            }
            .filter { !$0.isEmpty }
        if lines.isEmpty { return nil }
        let keyPattern = try! NSRegularExpression(pattern: "(?i)\\b(folder|description|type|place):")
        var parts: [String] = []
        for l in lines {
            let ns = l as NSString
            let starts = keyPattern.matches(in: l, range: NSRange(location: 0, length: ns.length)).map(\.range.location)
            if starts.isEmpty { parts.append(l); continue }
            var bounds = starts
            if bounds.first != 0 { bounds.insert(0, at: 0) }
            for (i, s) in bounds.enumerated() {
                let e = i + 1 < bounds.count ? bounds[i + 1] : ns.length
                let piece = ns.substring(with: NSRange(location: s, length: e - s)).trimmingCharacters(in: .whitespaces)
                if !piece.isEmpty { parts.append(piece) }
            }
        }
        var folder = "", desc = "", type = "", place = ""
        for p in parts {
            guard let colon = p.firstIndex(of: ":") else { continue }
            let key = p[..<colon].lowercased()
            let v = p[p.index(after: colon)...].trimmingCharacters(in: .whitespaces)
            switch key {
            case "folder": folder = v
            case "description": desc = v
            case "type": type = v
            case "place": place = v
            default: break
            }
        }
        if !type.isEmpty {
            let t = SmartFolders.normalizeType(type)
            return AiResult(folder: t, description: desc.isEmpty ? "Photo" : desc, type: t, place: SmartFolders.normalizePlace(place))
        }
        if folder.isEmpty { folder = lines[0] }
        if desc.isEmpty && lines.count > 1 { desc = lines[1] }
        let f = String(SmartFolders.slug(folder).prefix(48)).trimmingCharacters(in: CharacterSet(charactersIn: "-"))
        if f.isEmpty { return nil }
        return AiResult(folder: f, description: desc.isEmpty ? "Photo" : desc)
    }

    /// LITE answer (one caption) -> type from keywords in the caption, in priority order (a "pan of
    /// curry" is food even with a person in it). No place: trips come from the time zone / GPS.
    public static func liteResult(_ raw: String) -> AiResult? {
        guard let first = raw.split(whereSeparator: \.isNewline)
            .map({ $0.trimmingCharacters(in: .whitespaces) })
            .first(where: { !$0.isEmpty }) else { return nil }
        let caption = String(first.split(whereSeparator: \.isWhitespace).joined(separator: " ").prefix(160))
        let text = caption.lowercased()
        var type = "other"
        outer: for (t, words) in liteKeywords {
            for w in words where text.range(of: "\\b\(w)(s|es)?\\b", options: .regularExpression) != nil {
                type = t
                break outer
            }
        }
        var d = caption
        while d.hasSuffix(".") { d.removeLast() }
        return AiResult(folder: type, description: d, type: type, place: "")
    }

    private static let liteKeywords: [(String, [String])] = [
        ("screenshots", ["screenshot", "screen", "app", "website", "web page", "webpage", "chat"]),
        ("food", [
            "food", "plate", "dish", "bowl", "meal", "curry", "noodle", "ramen", "soup", "stew", "salad",
            "pizza", "burger", "sandwich", "cake", "dessert", "bread", "rice", "sushi", "pan", "pot",
            "cooking", "batter", "baking", "fruit", "vegetable", "coffee", "drink", "breakfast", "lunch", "dinner",
        ]),
        ("pets", ["dog", "cat", "puppy", "kitten", "pet", "hamster", "parrot"]),
        ("documents", ["document", "receipt", "paper", "letter", "form", "invoice", "text", "page", "book", "whiteboard"]),
        ("shopping", ["product", "shopping", "store", "shop", "price", "package", "box"]),
        ("events", [
            "stage", "concert", "music", "musician", "performance", "performer", "party", "festival", "wedding",
            "birthday", "crowd", "instrument", "band", "costume", "celebration", "game", "match",
        ]),
        ("travel", [
            "temple", "pavilion", "shrine", "pagoda", "landmark", "monument", "castle", "palace", "museum",
            "tourist", "airport", "cathedral", "tower", "ruins",
        ]),
        ("vehicles", ["car", "truck", "bus", "train", "motorcycle", "bike", "bicycle", "plane", "airplane", "boat"]),
        ("art", ["painting", "sculpture", "statue", "mural", "artwork", "drawing", "emblem", "mosaic"]),
        ("people", [
            "person", "people", "man", "men", "woman", "women", "boy", "girl", "child", "children", "kid",
            "baby", "family", "couple", "friend", "selfie", "group",
        ]),
        ("nature", [
            "beach", "sea", "ocean", "shore", "mountain", "forest", "tree", "sunset", "sunrise", "lake",
            "river", "flower", "sky", "garden", "field", "waterfall", "snow", "bird", "landscape",
        ]),
        ("city", ["street", "building", "city", "skyline", "bridge", "road", "skyscraper"]),
        ("home", ["room", "kitchen", "bedroom", "sofa", "couch", "furniture", "living room", "bathroom"]),
    ]

    /// EXIF hints passed to the model: "Taken: 2026-09-14. GPS: 35.65875, 139.70056. Pixel size: 4000x2252."
    public static func hints(taken: Date?, latitude: Double?, longitude: Double?, width: Int, height: Int) -> String {
        var parts: [String] = []
        if let taken {
            let f = DateFormatter()
            f.locale = Locale(identifier: "en_US_POSIX")
            f.dateFormat = "yyyy-MM-dd"
            parts.append("Taken: \(f.string(from: taken)).")
        }
        if let latitude, let longitude {
            parts.append(String(format: "GPS: %.5f, %.5f.", latitude, longitude))
        }
        if width > 0 && height > 0 { parts.append("Pixel size: \(width)x\(height).") }
        return parts.isEmpty ? "none" : parts.joined(separator: " ")
    }
}
