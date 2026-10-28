// swift-tools-version: 6.0
import PackageDescription

/// Platform-independent PhotoSorter logic shared by the iOS app: folder and trip rules, AI answer
/// parsing, blur scoring, duplicate hashing and the Gemini client. Ported from the Android app and
/// tested on the Mac with `swift test`.
let package = Package(
    name: "PhotoSorterCore",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [.library(name: "PhotoSorterCore", targets: ["PhotoSorterCore"])],
    targets: [
        .target(name: "PhotoSorterCore"),
        .testTarget(name: "PhotoSorterCoreTests", dependencies: ["PhotoSorterCore"]),
    ]
)
