import Foundation

// MARK: - Blur

/// A photo the blur scan suggests removing. Lower score = blurrier.
public struct BlurItem: Hashable, Sendable {
    public enum Kind: String, Sendable { case blurry = "Blurry", dark = "Dark or blank" }
    public var photo: PhotoInfo
    public var score: Float
    public var kind: Kind
    public init(photo: PhotoInfo, score: Float, kind: Kind) { self.photo = photo; self.score = score; self.kind = kind }
}

/// Sharpness = variance of the Laplacian of the photo scaled to 512 px, in the SHARPEST of 4x4 tiles,
/// so a sharp subject against a plain sky still counts as sharp. Tiles with almost no contrast are
/// ignored; a photo with no detailed tile at all is dark or blank. Same thresholds as Android
/// (calibrated on real photos: sharp 1,200-12,000; visibly blurred 85-310).
public enum BlurScore {
    public static let side = 512
    private static let grid = 4
    private static let flatStd: Double = 6
    public static let blurryBelow: Float = 250
    private static let darkMean: Float = 14
    /// Thumbnails smaller than this hide blur; photos smaller than this are skipped.
    public static let minScoredSide = 448

    public struct Result: Sendable { public var sharpness: Float; public var detailedTiles: Int; public var mean: Float }

    /// `gray` is w*h luminance values 0-255, row-major.
    public static func measure(_ gray: [Float], width w: Int, height h: Int) -> Result {
        precondition(gray.count == w * h)
        var sum = 0.0
        for v in gray { sum += Double(v) }
        let mean = Float(sum / Double(max(gray.count, 1)))
        if w < 3 || h < 3 { return Result(sharpness: 0, detailedTiles: 0, mean: mean) }
        var best = 0.0
        var detailed = 0
        gray.withUnsafeBufferPointer { g in
            for ty in 0..<grid {
                for tx in 0..<grid {
                    let y0 = max(1, ty * h / grid), y1 = min(h - 1, (ty + 1) * h / grid)
                    let x0 = max(1, tx * w / grid), x1 = min(w - 1, (tx + 1) * w / grid)
                    var n = 0.0, s = 0.0, s2 = 0.0, l = 0.0, l2 = 0.0
                    if y0 < y1 && x0 < x1 {
                        for y in y0..<y1 {
                            let row = y * w
                            for x in x0..<x1 {
                                let c = Double(g[row + x])
                                let lap = Double(g[row + x - 1]) + Double(g[row + x + 1]) + Double(g[row - w + x]) + Double(g[row + w + x]) - 4 * c
                                n += 1; s += c; s2 += c * c; l += lap; l2 += lap * lap
                            }
                        }
                    }
                    if n == 0 { continue }
                    let std = (max(0, s2 / n - (s / n) * (s / n))).squareRoot()
                    if std < flatStd { continue }
                    detailed += 1
                    best = max(best, l2 / n - (l / n) * (l / n))
                }
            }
        }
        return Result(sharpness: Float(best), detailedTiles: detailed, mean: mean)
    }

    /// nil = looks fine.
    public static func classify(_ r: Result) -> BlurItem.Kind? {
        if r.detailedTiles == 0 || r.mean < darkMean { return .dark }
        if r.sharpness < blurryBelow { return .blurry }
        return nil
    }

    /// Camera-type photos only: screenshots, RAW files, GIFs and own copies are skipped.
    public static func isCandidate(_ p: PhotoInfo) -> Bool {
        !p.isScreenshot && !p.isRaw && !p.isOwnCopy && !p.uti.lowercased().contains("gif")
    }

    /// Blurriest first, dark/blank frames before blurry ones.
    public static func sorted(_ items: [BlurItem]) -> [BlurItem] {
        items.sorted { a, b in
            if (a.kind == .dark) != (b.kind == .dark) { return a.kind == .dark }
            return a.score < b.score
        }
    }
}

// MARK: - Duplicates

/// Copies of the same shot. exact = byte-identical; otherwise same perceptual hash (re-saves, edits,
/// bursts). photos[0] is the suggested keep.
public struct DupCluster: Identifiable, Hashable, Sendable {
    public var id: String { photos.first?.id ?? "" }
    public var exact: Bool
    public var photos: [PhotoInfo]
    public init(exact: Bool, photos: [PhotoInfo]) { self.exact = exact; self.photos = photos }

    public var reclaimableBytes: Int64 { photos.dropFirst().reduce(0) { $0 + $1.sizeBytes } }
    public var hasRaw: Bool { photos.contains(where: \.isRaw) }
    /// Pre-selected for deletion: only extra byte-identical copies, never RAW files.
    public var defaultDelete: [PhotoInfo] { exact ? photos.dropFirst().filter { !$0.isRaw } : [] }
}

public enum DupHash {
    /// Bits out of 64 allowed to differ and still be "the same photo".
    public static let nearHamming = 8
    public static let n = 32

    /// 64-bit DCT perceptual hash of a 32x32 luminance image (values 0-255), DC removed, median threshold.
    public static func perceptualHash(gray32 s: [Float]) -> UInt64 {
        precondition(s.count == n * n)
        let f = dct2(s)
        var low: [Float] = []
        low.reserveCapacity(63)
        for y in 0..<8 { for x in 0..<8 where !(x == 0 && y == 0) { low.append(f[y * n + x]) } }
        let median = low.sorted()[low.count / 2]
        var bits: UInt64 = 0
        for y in 0..<8 {
            for x in 0..<8 where !(x == 0 && y == 0) {
                bits = (bits << 1) | (f[y * n + x] > median ? 1 : 0)
            }
        }
        return bits
    }

    public static func hamming(_ a: UInt64, _ b: UInt64) -> Int { (a ^ b).nonzeroBitCount }

    private static let cosTable: [[Float]] = (0..<n).map { coef in
        (0..<n).map { i in
            let c = cos(Double.pi * Double(2 * coef + 1) * Double(i) / Double(n))
            return Float(coef == 0 ? c / 2.0.squareRoot() : c)
        }
    }

    private static func dct2(_ s: [Float]) -> [Float] {
        var m = [Float](repeating: 0, count: n * n)
        for i in 0..<n { for k in 0..<n {
            var sum: Float = 0
            for j in 0..<n { sum += cosTable[k][j] * s[i * n + j] }
            m[i * n + k] = sum
        } }
        var f = [Float](repeating: 0, count: n * n)
        for k in 0..<n { for i in 0..<n {
            var sum: Float = 0
            for j in 0..<n { sum += cosTable[i][j] * m[j * n + k] }
            f[i * n + k] = sum
        } }
        return f
    }

    /// Groups photos by exact content hash (e.g. SHA-256), keeping groups of 2+.
    public static func exactClusters(_ hashes: [(PhotoInfo, String)]) -> [DupCluster] {
        var groups: [String: [PhotoInfo]] = [:]
        var order: [String] = []
        for (p, h) in hashes {
            if groups[h] == nil { order.append(h) }
            if !groups[h, default: []].contains(where: { $0.id == p.id }) { groups[h, default: []].append(p) }
        }
        return order.compactMap { h in
            let ps = groups[h]!
            return ps.count >= 2 ? DupCluster(exact: true, photos: originalFirst(ps)) : nil
        }
    }

    /// Greedy clustering by perceptual hash in library order (first photo of a group is its leader).
    public static func nearClusters(_ hashes: [(PhotoInfo, UInt64)]) -> [DupCluster] {
        var leaders: [(UInt64, Int)] = []
        var groups: [[PhotoInfo]] = []
        for (p, h) in hashes {
            if let idx = leaders.firstIndex(where: { hamming($0.0, h) <= nearHamming }) {
                groups[leaders[idx].1].append(p)
            } else {
                leaders.append((h, groups.count))
                groups.append([p])
            }
        }
        return groups.filter { $0.count >= 2 }.map { DupCluster(exact: false, photos: bestFirst($0)) }
    }

    /// Exact copies: keep the ORIGINAL - not an app copy, the one added to the library first.
    public static func originalFirst(_ ps: [PhotoInfo]) -> [PhotoInfo] {
        ps.sorted {
            if $0.isOwnCopy != $1.isOwnCopy { return !$0.isOwnCopy }
            let a = $0.added ?? .distantFuture, b = $1.added ?? .distantFuture
            if a != b { return a < b }
            return $0.id < $1.id
        }
    }

    /// Look-alikes: keep the largest file (usually the best quality), then the newest.
    public static func bestFirst(_ ps: [PhotoInfo]) -> [PhotoInfo] {
        ps.sorted {
            if $0.isOwnCopy != $1.isOwnCopy { return !$0.isOwnCopy }
            if $0.sizeBytes != $1.sizeBytes { return $0.sizeBytes > $1.sizeBytes }
            return ($0.taken ?? .distantPast) > ($1.taken ?? .distantPast)
        }
    }

    /// Exact groups first (safe to clean), then look-alikes, each by reclaimable bytes.
    public static func ordered(_ clusters: [DupCluster]) -> [DupCluster] {
        clusters.sorted {
            if $0.exact != $1.exact { return $0.exact }
            return $0.reclaimableBytes > $1.reclaimableBytes
        }
    }
}
