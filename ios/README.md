# PhotoSorter for iOS (in progress)

`PhotoSorterCore/` is a Swift package with the app logic shared with Android, ported 1:1 from the
Kotlin code: folder and trip rules (`SmartFolders`), on-device model prompts and answer parsing
(`VlmPrompts`, Standard Qwen3-VL-2B / Lite SmolVLM2-500M), blur scoring and duplicate hashing
(`Cleanup`), and the optional Gemini client.

```bash
cd ios/PhotoSorterCore && swift test   # needs Xcode (swift-testing)
```

Next: the SwiftUI app (PhotoKit, albums under a "PhotoSorter" folder, Recently Deleted for removals),
llama.cpp + mtmd built for iOS with Metal, and background processing.
