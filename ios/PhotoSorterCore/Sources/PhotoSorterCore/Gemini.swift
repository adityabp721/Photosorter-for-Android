import Foundation

/// Rate limit / daily quota still exhausted after retries - the caller should stop the batch.
public struct QuotaExceededError: LocalizedError {
    public let message: String
    public var errorDescription: String? { message }
}

public struct GeminiError: LocalizedError {
    public let message: String
    public var errorDescription: String? { message }
}

/// Optional cloud classification with the user's own Gemini API key (same requests, retries and
/// parsing as the Android app). The key goes in a header, never in the URL.
public struct Gemini: Sendable {
    public static let defaultModel = "gemini-2.5-flash"

    public var apiKey: String
    public var model: String
    public var useWebSearch: Bool
    var session: URLSession = .shared
    var sleep: @Sendable (UInt64) async throws -> Void = { try await Task.sleep(nanoseconds: $0) }

    public init(apiKey: String, model: String = Gemini.defaultModel, useWebSearch: Bool = false) {
        self.apiKey = apiKey; self.model = model; self.useWebSearch = useWebSearch
    }

    static let sortPrompt = """
        File this photo. Return ONLY a JSON object with exactly two keys:
        - "folder": ONE broad lowercase folder for the main subject. Prefer exactly one of "food", "people", "pets", "nature", "travel", "city", "home", "shopping", "documents", "screenshots", "vehicles", "events", "art", "other"; only invent a new 1-2 word kebab-case name if none fits at all.
        - "description": one short sentence (at most 15 words) describing the photo. Use the EXIF hints below when helpful.
        """

    static let tripPrompt = """
        Classify this photo from a personal phone gallery. Use the EXIF hints below when helpful.
        Return ONLY a JSON object with exactly three keys:
        - "type": one of "travel", "food", "people", "pets", "nature", "city", "home", "shopping", "documents", "screenshot", "vehicles", "events", "art", "other".
        - "place": the country where it was taken, ONLY if the photo clearly shows travel away from home (a famous landmark, foreign-language signs, a tourist site) or the GPS says so; otherwise "none".
        - "description": one short sentence (at most 12 words) describing the photo.
        """

    /// Classifies one JPEG. `trip` = Organize (type/place/description), else Sort (folder/description).
    public func classify(jpeg: Data, hints: String, trip: Bool) async throws -> AiResult {
        let prompt = trip ? Self.tripPrompt : Self.sortPrompt
        let first = try await call(jpeg: jpeg, prompt: prompt, hints: hints, webSearch: useWebSearch)
        if useWebSearch && Self.looksLikeToolUnsupported(first) {
            // model / plan without Google Search grounding: retry without the tool
            return try Self.parseBody(try await call(jpeg: jpeg, prompt: prompt, hints: hints, webSearch: false))
        }
        return try Self.parseBody(first)
    }

    struct Resp { var code: Int; var body: String }

    static func looksLikeToolUnsupported(_ r: Resp) -> Bool {
        r.code != 200 && (r.body.contains("google_search") ||
            (r.body.lowercased().contains("tool") && r.body.lowercased().contains("invalid")))
    }

    /// Retries rate limits (429), network errors and 5xx: server "retryDelay" hint, else 4 s, 8 s, 16 s.
    private func call(jpeg: Data, prompt: String, hints: String, webSearch: Bool) async throws -> Resp {
        var attempt = 0
        while true {
            var r: Resp
            do {
                r = try await callOnce(jpeg: jpeg, prompt: prompt, hints: hints, webSearch: webSearch)
            } catch {
                if attempt >= 2 { throw GeminiError(message: "Network error: \(error.localizedDescription)") }
                r = Resp(code: -1, body: error.localizedDescription)
            }
            let retryable = r.code == 429 || r.code == -1 || (500...599).contains(r.code)
            if !retryable { return r }
            if attempt >= 3 {
                if r.code == 429 {
                    let daily = r.body.contains("PerDay") || r.body.lowercased().contains("per day")
                    throw QuotaExceededError(message: "Gemini quota/rate limit reached" + (daily ? " (daily limit)" : "") +
                        ". Try again later, use a paid key, or switch to on-device mode.")
                }
                return r
            }
            try await sleep(UInt64(Self.retryDelaySeconds(body: r.body, attempt: attempt)) * 1_000_000_000)
            attempt += 1
        }
    }

    static func retryDelaySeconds(body: String, attempt: Int) -> Int {
        if let m = body.range(of: "\"retryDelay\"\\s*:\\s*\"(\\d+)(\\.\\d+)?s\"", options: .regularExpression) {
            let digits = body[m].split(separator: "\"").dropFirst(2).first.map { $0.prefix { $0.isNumber } }
            if let d = digits.flatMap({ Int($0) }) { return min(max(d, 1), 60) }
        }
        return min(max(4 << attempt, 1), 60)
    }

    func requestBody(jpeg: Data, prompt: String, hints: String, webSearch: Bool) -> [String: Any] {
        var gen: [String: Any] = ["temperature": 0.2, "maxOutputTokens": 1024]
        // JSON mode can't be combined with tools; the reply is parsed out of the text either way
        if !webSearch { gen["responseMimeType"] = "application/json" }
        // 2.5 Flash thinks by default and thinking tokens count against maxOutputTokens
        if model.hasPrefix("gemini-2.5-flash") { gen["thinkingConfig"] = ["thinkingBudget": 0] }
        var body: [String: Any] = [
            "contents": [["parts": [
                ["inline_data": ["mime_type": "image/jpeg", "data": jpeg.base64EncodedString()]],
                ["text": "\(prompt)\nEXIF hints: \(hints)"],
            ]]],
            "systemInstruction": ["parts": [["text": "You are a photo filing assistant. Reply with only the JSON object."]]],
            "generationConfig": gen,
        ]
        if webSearch { body["tools"] = [["google_search": [String: Any]()]] }
        return body
    }

    private func callOnce(jpeg: Data, prompt: String, hints: String, webSearch: Bool) async throws -> Resp {
        let m = model.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? model
        var req = URLRequest(url: URL(string: "https://generativelanguage.googleapis.com/v1beta/models/\(m):generateContent")!)
        req.httpMethod = "POST"
        req.timeoutInterval = 120
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue(apiKey.trimmingCharacters(in: .whitespacesAndNewlines), forHTTPHeaderField: "x-goog-api-key")
        req.httpBody = try JSONSerialization.data(withJSONObject: requestBody(jpeg: jpeg, prompt: prompt, hints: hints, webSearch: webSearch))
        let (data, resp) = try await session.data(for: req)
        return Resp(code: (resp as? HTTPURLResponse)?.statusCode ?? -1, body: String(decoding: data, as: UTF8.self))
    }

    static func parseBody(_ r: Resp) throws -> AiResult {
        guard r.code == 200 else { throw GeminiError(message: "HTTP \(r.code): \(r.body.prefix(240))") }
        guard let root = try? JSONSerialization.jsonObject(with: Data(r.body.utf8)) as? [String: Any] else {
            throw GeminiError(message: "Bad response: \(r.body.prefix(240))")
        }
        guard let cands = root["candidates"] as? [[String: Any]], let cand = cands.first else {
            throw GeminiError(message: "No candidates: \(r.body.prefix(240))")
        }
        guard let parts = (cand["content"] as? [String: Any])?["parts"] as? [[String: Any]] else {
            throw GeminiError(message: "Empty answer (finishReason: \(cand["finishReason"] as? String ?? "unknown"))")
        }
        let out = parts.compactMap { $0["text"] as? String }.joined()
        var jsonText = out.trimmingCharacters(in: .whitespacesAndNewlines)
        if let a = out.firstIndex(of: "{"), let b = out.lastIndex(of: "}"), a < b { jsonText = String(out[a...b]) }
        guard let json = try? JSONSerialization.jsonObject(with: Data(jsonText.utf8)) as? [String: Any] else {
            throw GeminiError(message: "Model did not return JSON: \(out.prefix(240))")
        }
        let desc = (json["description"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if desc.isEmpty { throw GeminiError(message: "Model returned an empty description") }
        let type = (json["type"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if !type.isEmpty {
            let t = SmartFolders.normalizeType(type)
            return AiResult(folder: t, description: desc, type: t, place: SmartFolders.normalizePlace(json["place"] as? String))
        }
        let f = String(SmartFolders.slug(json["folder"] as? String ?? "").prefix(48))
            .trimmingCharacters(in: CharacterSet(charactersIn: "-"))
        return AiResult(folder: f.isEmpty ? "other" : f, description: desc)
    }
}
