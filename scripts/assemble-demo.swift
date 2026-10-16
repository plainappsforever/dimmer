// Builds the Accessibility-declaration demo video from raw emulator recordings and a cut list (no ffmpeg needed).
// Usage (repo root):  swift scripts/assemble-demo.swift [cutlist.json] [output.mp4]
//   defaults: dist/play/accessibility-demo-cutlist.json -> dist/play/accessibility-demo.mp4
// Raw segments: dist/play/demo-raw/<file> (adb shell screenrecord, 720x1616). Each segment is trimmed, optionally
// sped up (speed > 1; the caption says so), and gets its caption burned in at the bottom (Core Animation).
// Caption font: build/fonts/Roboto-Regular.ttf (open licence, pulled from the emulator:
//   adb -s emulator-5570 pull /system/fonts/Roboto-Regular.ttf build/fonts/). No audio track.
import AVFoundation
import CoreGraphics
import CoreText
import Foundation
import QuartzCore

struct Segment: Decodable { let shot: Int; let file: String; let start: Double; let end: Double; let speed: Double; let caption: String }
struct CutList: Decodable { let renderWidth: Int; let renderHeight: Int; let segments: [Segment] }

let args = CommandLine.arguments
let root = FileManager.default.currentDirectoryPath
let cutURL = URL(fileURLWithPath: args.count > 1 ? args[1] : "dist/play/accessibility-demo-cutlist.json")
let outURL = URL(fileURLWithPath: args.count > 2 ? args[2] : "dist/play/accessibility-demo.mp4")
let rawDir = URL(fileURLWithPath: "dist/play/demo-raw", relativeTo: URL(fileURLWithPath: root))
let fontURL = URL(fileURLWithPath: "build/fonts/Roboto-Regular.ttf")
let cut = try JSONDecoder().decode(CutList.self, from: Data(contentsOf: cutURL))

func sync<T>(_ body: @escaping () async throws -> T) throws -> T {
  var result: Result<T, Error>!
  let sem = DispatchSemaphore(value: 0)
  Task { do { result = .success(try await body()) } catch { result = .failure(error) }; sem.signal() }
  sem.wait()
  return try result.get()
}

// 1. composition: trimmed, re-timed segments back to back
let comp = AVMutableComposition()
guard let track = comp.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid) else { fatalError("no track") }
var cursor = CMTime.zero
var windows: [(start: Double, end: Double, text: String)] = []
let ts: CMTimeScale = 600
for s in cut.segments {
  let asset = AVURLAsset(url: rawDir.appendingPathComponent(s.file))
  guard let src = try sync({ try await asset.loadTracks(withMediaType: .video).first }) else { fatalError("no video in \(s.file)") }
  let duration = try sync { try await asset.load(.duration) }
  let start = CMTime(seconds: s.start, preferredTimescale: ts)
  let end = CMTimeMinimum(CMTime(seconds: s.end, preferredTimescale: ts), duration)
  let range = CMTimeRange(start: start, end: end)
  try track.insertTimeRange(range, of: src, at: cursor)
  let scaled = CMTime(seconds: range.duration.seconds / s.speed, preferredTimescale: ts)
  if s.speed != 1 { track.scaleTimeRange(CMTimeRange(start: cursor, duration: range.duration), toDuration: scaled) }
  let segEnd = track.timeRange.end   // actual end after re-timing (avoids rounding gaps)
  windows.append((cursor.seconds, segEnd.seconds, s.caption))
  print(String(format: "shot %d %@ %.2f-%.2f s (x%.2f) -> %.2f-%.2f s", s.shot, s.file, s.start, end.seconds, s.speed,
               cursor.seconds, segEnd.seconds))
  cursor = segEnd
}
let total = cursor.seconds

// 2. captions: rendered with CoreText into images, shown in a band BELOW the video so no app pixel is covered
//    (the disclosure and its buttons stay fully readable). Core Animation origin is bottom-left on macOS.
let W = CGFloat(cut.renderWidth), H = CGFloat(cut.renderHeight), band: CGFloat = 220
let parent = CALayer(); parent.frame = CGRect(x: 0, y: 0, width: W, height: H + band)
parent.backgroundColor = CGColor(red: 0.05, green: 0.04, blue: 0.035, alpha: 1)
let videoLayer = CALayer(); videoLayer.frame = parent.frame
parent.addSublayer(videoLayer)
guard let descs = CTFontManagerCreateFontDescriptorsFromURL(fontURL as CFURL) as? [CTFontDescriptor], let desc = descs.first else {
  fatalError("caption font missing: adb -s emulator-5570 pull /system/fonts/Roboto-Regular.ttf build/fonts/")
}
let font = CTFontCreateWithFontDescriptor(desc, 31, nil)

func captionImage(_ text: String, size: CGSize) -> CGImage {
  let scale: CGFloat = 2
  let ctx = CGContext(data: nil, width: Int(size.width * scale), height: Int(size.height * scale), bitsPerComponent: 8,
                      bytesPerRow: 0, space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
  ctx.scaleBy(x: scale, y: scale)
  let rect = CGRect(origin: .zero, size: size)
  ctx.addPath(CGPath(roundedRect: rect.insetBy(dx: 1.5, dy: 1.5), cornerWidth: 18, cornerHeight: 18, transform: nil))
  ctx.setFillColor(CGColor(red: 0.11, green: 0.08, blue: 0.06, alpha: 1)); ctx.fillPath()
  ctx.addPath(CGPath(roundedRect: rect.insetBy(dx: 1.5, dy: 1.5), cornerWidth: 18, cornerHeight: 18, transform: nil))
  ctx.setStrokeColor(CGColor(red: 1, green: 0.70, blue: 0.34, alpha: 0.9)); ctx.setLineWidth(2); ctx.strokePath()
  var align = CTTextAlignment.center
  let para = withUnsafeBytes(of: &align) { ptr in
    CTParagraphStyleCreate([CTParagraphStyleSetting(spec: .alignment, valueSize: MemoryLayout<CTTextAlignment>.size,
                                                    value: ptr.baseAddress!)], 1)
  }
  let attr = NSAttributedString(string: text, attributes: [
    NSAttributedString.Key(kCTFontAttributeName as String): font,
    NSAttributedString.Key(kCTForegroundColorAttributeName as String): CGColor(red: 1, green: 1, blue: 1, alpha: 1),
    NSAttributedString.Key(kCTParagraphStyleAttributeName as String): para])
  let fs = CTFramesetterCreateWithAttributedString(attr)
  let inner = rect.insetBy(dx: 24, dy: 18)
  let fit = CTFramesetterSuggestFrameSizeWithConstraints(fs, CFRange(location: 0, length: 0), nil, inner.size, nil)
  let textRect = CGRect(x: inner.minX, y: inner.midY - fit.height / 2, width: inner.width, height: fit.height + 1)
  CTFrameDraw(CTFramesetterCreateFrame(fs, CFRange(location: 0, length: 0), CGPath(rect: textRect, transform: nil), nil), ctx)
  return ctx.makeImage()!
}

for w in windows {
  let box = CALayer()
  let margin: CGFloat = 20
  box.frame = CGRect(x: margin, y: 14, width: W - 2 * margin, height: band - 28)
  box.contents = captionImage(w.text, size: box.frame.size)
  box.opacity = 0
  let anim = CAKeyframeAnimation(keyPath: "opacity")
  let a = max(w.start, 0) / total, b = min(w.end, total) / total, eps = 0.0001
  anim.values = [0, 0, 1, 1, 0, 0]
  anim.keyTimes = [0, NSNumber(value: max(a - eps, 0)), NSNumber(value: a), NSNumber(value: max(b - eps, a)), NSNumber(value: b), 1]
  anim.calculationMode = .discrete
  anim.beginTime = AVCoreAnimationBeginTimeAtZero
  anim.duration = total
  anim.isRemovedOnCompletion = false
  anim.fillMode = .both
  box.add(anim, forKey: "caption")
  parent.addSublayer(box)
}

// 3. video composition + export (H.264 MP4)
let vc = AVMutableVideoComposition()
vc.renderSize = CGSize(width: W, height: H + band)   // video at the top, caption band below
vc.frameDuration = CMTime(value: 1, timescale: 30)
let instruction = AVMutableVideoCompositionInstruction()
instruction.timeRange = CMTimeRange(start: .zero, duration: comp.duration)
instruction.layerInstructions = [AVMutableVideoCompositionLayerInstruction(assetTrack: track)]
vc.instructions = [instruction]
vc.animationTool = AVVideoCompositionCoreAnimationTool(postProcessingAsVideoLayer: videoLayer, in: parent)

if FileManager.default.fileExists(atPath: outURL.path) {
  // never delete: keep the previous file next to the new one
  let old = outURL.deletingPathExtension().appendingPathExtension("previous-\(Int(Date().timeIntervalSince1970)).mp4")
  try FileManager.default.moveItem(at: outURL, to: old)
  print("previous video kept as \(old.lastPathComponent)")
}
guard let export = AVAssetExportSession(asset: comp, presetName: AVAssetExportPresetHighestQuality) else { fatalError("no export session") }
export.videoComposition = vc
export.outputURL = outURL
export.outputFileType = .mp4
let sem = DispatchSemaphore(value: 0)
export.exportAsynchronously { sem.signal() }
sem.wait()
guard export.status == .completed else { fatalError("export failed: \(String(describing: export.error))") }
let size = (try FileManager.default.attributesOfItem(atPath: outURL.path)[.size] as? Int) ?? 0
print(String(format: "wrote %@ (%.1f s, %.1f MB)", outURL.path, total, Double(size) / 1_000_000))
