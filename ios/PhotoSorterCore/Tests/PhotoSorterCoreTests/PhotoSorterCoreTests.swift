import Foundation
import Testing
@testable import PhotoSorterCore

private let la = TimeZone(identifier: "America/Los_Angeles")!

private func date(_ iso: String) -> Date { ISO8601DateFormatter().date(from: iso)! }

private func photo(_ id: Int, _ iso: String, _ off: Int?, screenshot: Bool = false, whatsapp: Bool = false) -> PhotoInfo {
    PhotoInfo(id: "\(id)", fileName: "p\(id).jpg", taken: date(iso), utcOffsetMin: off, added: Date(timeIntervalSince1970: Double(id)),
              sizeBytes: 100, isScreenshot: screenshot, isWhatsApp: whatsapp)
}

private func ai(_ type: String, _ place: String = "") -> AiResult { AiResult(folder: type, description: "d", type: type, place: place) }

@Suite struct SmartFolderTests {
    @Test func japanTripCollectsAllPhotosFromThoseDays() {
        let ps = [
            photo(1, "2026-09-14T14:34:51+09:00", 540), photo(2, "2026-09-16T13:42:43+09:00", 540),
            photo(3, "2026-09-18T11:24:53+09:00", 540), photo(4, "2026-09-19T10:29:56+09:00", 540),
            photo(5, "2026-09-20T18:07:10+09:00", 540),
            photo(6, "2026-10-19T14:12:47-07:00", -420), photo(7, "2026-10-19T14:18:11-07:00", -420),
            photo(8, "2026-10-19T17:32:00-07:00", -420, screenshot: true),
            photo(9, "2026-10-18T10:00:00-07:00", nil, whatsapp: true),
        ]
        let res = ["1": ai("events"), "2": ai("food"), "3": ai("events", "Japan"), "4": ai("art"), "5": ai("events"),
                   "6": ai("food"), "7": ai("food"), "8": ai("shopping"), "9": ai("people")]
        let f = SmartFolders.assign(ps, results: res, zone: la)
        #expect(Set((1...5).map { f["\($0)"]! }) == ["japan-trip"])
        #expect(f["6"] == "food" && f["7"] == "food")
        #expect(f["8"] == "screenshots")
        #expect(f["9"] == "whatsapp")
    }

    @Test func unnamedTripGetsMonthName() {
        let ps = [photo(1, "2026-09-14T10:00:00+09:00", 540), photo(2, "2026-09-15T10:00:00+09:00", 540)]
        let f = SmartFolders.assign(ps, results: ["1": ai("food"), "2": ai("nature")], zone: la)
        #expect(f["1"] == "trip-sep-2026" && f["2"] == "trip-sep-2026")
    }

    @Test func longGapSplitsTrips() {
        let ps = [photo(1, "2026-03-01T10:00:00+01:00", 60), photo(2, "2026-06-01T10:00:00+01:00", 60)]
        let f = SmartFolders.assign(ps, results: ["1": ai("city", "France"), "2": ai("city", "Italy")], zone: la)
        #expect(f["1"] == "france-trip" && f["2"] == "italy-trip")
    }

    @Test func gpsCountryMakesATripWithoutTimeZone() {
        let ps = [photo(1, "2026-07-01T10:00:00Z", nil)]
        let f = SmartFolders.assign(ps, results: ["1": ai("nature")], geoCountry: ["1": "United Kingdom"], homeCountry: "United States", zone: la)
        #expect(f["1"] == "uk-trip")
    }

    @Test func homePhotosAreNotTrips() {
        let ps = [photo(1, "2026-07-01T10:00:00-07:00", -420), photo(2, "2026-01-10T10:00:00-08:00", -480)]
        let f = SmartFolders.assign(ps, results: ["1": ai("nature"), "2": ai("pets")], zone: la)
        #expect(f["1"] == "nature" && f["2"] == "pets")
    }

    @Test func normalizesTypesPlacesAndOffsets() {
        #expect(SmartFolders.normalizeType("Screenshot.") == "screenshots")
        #expect(SmartFolders.normalizeType("animals") == "pets")
        #expect(SmartFolders.normalizeType("spaceship") == "other")
        #expect(SmartFolders.normalizePlace("none") == "")
        #expect(SmartFolders.placeSlug("United States") == "usa")
        #expect(SmartFolders.placeSlug("Costa Rica") == "costa-rica")
        #expect(parseUtcOffset("+09:00") == 540 && parseUtcOffset("-07:00") == -420 && parseUtcOffset("Z") == 0)
        #expect(parseUtcOffset("") == nil)
    }

    @Test func tripDatesUseThePhotosOwnTimeZone() {
        // 10:45 in Kyoto is the evening before in California; the label shows the Kyoto date
        let g = FolderGroup(name: "japan-trip", summary: "", photos: [photo(1, "2026-09-15T10:45:02+09:00", 540)])
        #expect(g.dateRangeLabel(zone: la) == "2026-09-15")
    }
}

@Suite struct ParsingTests {
    @Test func parsesStructuredAnswers() {
        let a = VlmPrompts.parseOutput("type: food\nplace: none\ndescription: colorful ramen bowl")!
        #expect(a.type == "food" && a.place == "" && a.description == "colorful ramen bowl")
        let b = VlmPrompts.parseOutput("type: events  place: Japan  description: crowds at a temple")!
        #expect(b.type == "events" && b.place == "Japan" && b.description == "crowds at a temple")
        let c = VlmPrompts.parseOutput("**folder:** Street Food\n- description: tacos at night")!
        #expect(c.folder == "street-food" && c.description == "tacos at night")
    }

    @Test func liteCaptionsMapToTypes() {
        let cases: [(String, String)] = [
            ("A plate of food with a fork and spoon on it.", "food"),
            ("A close-up of a pan filled with a creamy orange curry, garnished with dill and herbs.", "food"),
            ("A pot of orange batter and a baking tray with round balls of batter are on a white stove.", "food"),
            ("Three people are playing traditional Chinese instruments in a room.", "events"),
            ("Three people are on stage in a room with a fire mural on the wall.", "events"),
            ("The photo captures the grandeur of a traditional Chinese pavilion surrounded by lush greenery.", "travel"),
            ("A large circular emblem with a bird and a circle in the center, surrounded by intricate patterns.", "art"),
            ("A woman in a brown sweater stands on a rocky shore at sunset.", "people"),
            ("A snowy mountain behind a lake.", "nature"),
            ("Nothing recognizable here.", "other"),
        ]
        for (caption, type) in cases {
            let r = VlmPrompts.liteResult(caption)!
            #expect(r.type == type, "\(caption)")
            #expect(r.place == "")
            #expect(r.description == String(caption.dropLast()))
        }
        #expect(VlmPrompts.liteResult("  \n ") == nil)
    }

    @Test func promptsAndVariants() {
        #expect(VlmPrompts.prompt(for: .lite, trip: true, hints: "x") == "Describe this photo in one short sentence. (x)")
        #expect(VlmPrompts.prompt(for: .standard, trip: true, hints: "Taken: 2026-09-14.").contains("EXIF hints: Taken: 2026-09-14.\n"))
        #expect(VlmVariant.recommended(ramBytes: 4_000_000_000) == .lite)
        #expect(VlmVariant.recommended(ramBytes: 8_000_000_000) == .standard)
        #expect(VlmVariant.lite.mmprojURL.absoluteString.hasSuffix("/ggml-org/SmolVLM2-500M-Video-Instruct-GGUF/resolve/main/mmproj-SmolVLM2-500M-Video-Instruct-Q8_0.gguf"))
    }

    @Test func geminiParsing() throws {
        func body(_ text: String) -> Gemini.Resp {
            let obj: [String: Any] = ["candidates": [["content": ["parts": [["text": text]]]]]]
            return Gemini.Resp(code: 200, body: String(decoding: try! JSONSerialization.data(withJSONObject: obj), as: UTF8.self))
        }
        let t = try Gemini.parseBody(body("```json\n{\"type\":\"Screenshot\",\"place\":\"none\",\"description\":\"a chat\"}\n```"))
        #expect(t.type == "screenshots" && t.place == "" && t.description == "a chat")
        let s = try Gemini.parseBody(body("{\"folder\":\"Street Food!\",\"description\":\"tacos\"}"))
        #expect(s.folder == "street-food")
        #expect(throws: GeminiError.self) { try Gemini.parseBody(Gemini.Resp(code: 403, body: "denied")) }
        #expect(Gemini.retryDelaySeconds(body: "{\"retryDelay\": \"17s\"}", attempt: 0) == 17)
        #expect(Gemini.retryDelaySeconds(body: "", attempt: 1) == 8)
        let req = Gemini(apiKey: "k").requestBody(jpeg: Data([1, 2]), prompt: "p", hints: "h", webSearch: false)
        #expect((req["generationConfig"] as! [String: Any])["responseMimeType"] as? String == "application/json")
    }
}

@Suite struct CleanupTests {
    let w = 512, h = 384

    func texture(_ seed: UInt64 = 1) -> [Float] {
        var x = seed
        return (0..<(w * h)).map { _ in
            x = x &* 6364136223846793005 &+ 1442695040888963407
            return 40 + Float(x >> 40) / Float(1 << 24) * 180
        }
    }

    func blur(_ src: [Float], passes: Int) -> [Float] {
        var a = src
        for _ in 0..<passes {
            var b = [Float](repeating: 0, count: a.count)
            for y in 0..<h { for x in 0..<w {
                var s: Float = 0, n: Float = 0
                for dy in -2...2 { for dx in -2...2 {
                    let yy = y + dy, xx = x + dx
                    if yy >= 0 && yy < h && xx >= 0 && xx < w { s += a[yy * w + xx]; n += 1 }
                } }
                b[y * w + x] = s / n
            } }
            a = b
        }
        return a
    }

    func kind(_ g: [Float]) -> BlurItem.Kind? { BlurScore.classify(BlurScore.measure(g, width: w, height: h)) }

    @Test func sharpIsKeptBlurredIsFlagged() {
        #expect(kind(texture()) == nil)
        #expect(kind(blur(texture(), passes: 3)) == .blurry)
    }

    @Test func blankAndBlackAreDark() {
        #expect(kind([Float](repeating: 128, count: w * h)) == .dark)
        #expect(kind([Float](repeating: 4, count: w * h)) == .dark)
    }

    @Test func sharpSubjectOnPlainBackgroundIsKept() {
        let t = texture(7)
        var g = (0..<(w * h)).map { i in 150 + 40 * Float(i / w) / Float(h) }
        for y in 0..<(h / 4) { for x in 0..<(w / 4) { g[y * w + x] = t[y * w + x] } }
        #expect(kind(g) == nil)
    }

    @Test func perceptualHashMatchesResavesNotDifferentPhotos() {
        let n = DupHash.n
        let a: [Float] = (0..<(n * n)).map { i in Float((i % n) * 8 + (i / n) * 3) }
        let resaved = a.map { min(255, $0 * 1.03 + 2) }        // brightness tweak, like a re-save
        let other: [Float] = (0..<(n * n)).map { i in Float(((i / n) % 7) * 36) }
        let ha = DupHash.perceptualHash(gray32: a)
        #expect(DupHash.hamming(ha, DupHash.perceptualHash(gray32: resaved)) <= DupHash.nearHamming)
        #expect(DupHash.hamming(ha, DupHash.perceptualHash(gray32: other)) > DupHash.nearHamming)
    }

    @Test func exactCopiesKeepTheOriginal() {
        var original = photo(10, "2026-10-04T20:00:59-07:00", -420); original.added = Date(timeIntervalSince1970: 1000)
        var copy = photo(11, "2026-10-04T20:00:59-07:00", -420); copy.added = Date(timeIntervalSince1970: 2000)
        let c = DupHash.exactClusters([(copy, "h"), (original, "h"), (photo(12, "2026-01-01T00:00:00Z", 0), "other")])
        #expect(c.count == 1)
        #expect(c[0].photos.map(\.id) == ["10", "11"])
        #expect(c[0].defaultDelete.map(\.id) == ["11"])
    }

    @Test func rawIsNeverPreselected() {
        var raw = photo(2, "2026-01-01T00:00:00Z", 0); raw.fileName = "IMG_2.DNG"; raw.added = Date(timeIntervalSince1970: 50)
        let jpeg = photo(1, "2026-01-01T00:00:00Z", 0)
        let c = DupHash.exactClusters([(jpeg, "h"), (raw, "h")])[0]
        #expect(c.hasRaw && c.defaultDelete.isEmpty)
        #expect(BlurScore.isCandidate(raw) == false)
    }
}
