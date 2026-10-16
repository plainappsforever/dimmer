// Google Play store assets from emulator screenshots + primitives (no stock material). Launch step 4.
// Usage (repo root):  swift scripts/make-store-assets.swift [raw-root]     (raw-root default build/store-raw; v3.1.1 used
//          build/store-raw-3.1.1, captured with scripts/emu/kit_shots.py, so the 3.1.0 captures stay untouched)
// Inputs:  <raw-root>/{en,de}/01..08.png   1080x2160 `adb exec-out screencap -p` captures (wm size 1080x2160),
//          dist/play/icon-1024.png              from scripts/make-icon.swift
//          build/fonts/Roboto-Regular.ttf       open-licence font (adb -s emulator-5570 pull /system/fonts/Roboto-Regular.ttf)
// Outputs (all 24-bit PNG without alpha, as Play requires):
//   dist/play/screenshots/{en,de}/NN-name.png             plain screenshots, 1080x2160 (exactly 2:1, the Play maximum)
//   dist/play/screenshots/{en,de}-captioned/NN-name.png   same size, caption strip on top, screenshot scaled below it
//   dist/play/screenshots/captions.json                   the captions as text (RELEASE_KIT 8a), if the owner prefers
//                                                         plain screenshots and captions elsewhere
//   dist/play/feature-graphic.png, feature-graphic-de.png 1024x500 (RELEASE_KIT 8b)
import CoreGraphics
import CoreText
import Foundation
import ImageIO
import UniformTypeIdentifiers

let shots: [(file: String, en: String, de: String)] = [
  ("01-home-on-reading-45", "Darker than your brightness slider", "Dunkler als der Helligkeitsregler"),
  ("02-home-tint-candlelight", "Four warm tints, one simple scale", "Vier warme Farbtöne, eine einfache Skala"),
  ("03-dimmed-app-statusbar", "Covers the status bar too", "Deckt auch die Statusleiste ab"),
  ("04-shade-notification", "Control it from the shade", "Steuerung direkt aus der Benachrichtigungsleiste"),
  ("05-settings-schedule-sunset", "Schedule with slow fade-in, no location needed", "Zeitplan mit langsamem Einblenden, ohne Standort"),
  ("06-presets-edit-menu", "Presets you can edit", "Voreinstellungen, die du ändern kannst"),
  ("07-home-widgets-on", "Widget, tiles and shortcuts", "Widget, Kacheln und Verknüpfungen"),
  ("08-disclosure", "We tell you first. No ads, no account, no internet", "Wir sagen es vorher. Keine Werbung, kein Konto, kein Internet"),
]
let feature = (en: ("Darker than your brightness slider", "Warm tints. Schedule.\nNo ads. No internet."),
               de: ("Dunkler als der Helligkeitsregler", "Warme Töne. Zeitplan.\nKeine Werbung. Kein Internet."))

let fm = FileManager.default
let space = CGColorSpace(name: CGColorSpace.sRGB)!
func rgb(_ h: UInt32, _ a: CGFloat = 1) -> CGColor {
  CGColor(colorSpace: space, components: [CGFloat((h >> 16) & 255) / 255, CGFloat((h >> 8) & 255) / 255, CGFloat(h & 255) / 255, a])!
}
let night: UInt32 = 0x0E0B0A, nightLight: UInt32 = 0x2B1D15, amber: UInt32 = 0xFFB257

// Roboto (variable font): regular and bold via the 'wght' axis.
guard let descs = CTFontManagerCreateFontDescriptorsFromURL(URL(fileURLWithPath: "build/fonts/Roboto-Regular.ttf") as CFURL) as? [CTFontDescriptor],
      let baseDesc = descs.first else { fatalError("missing build/fonts/Roboto-Regular.ttf (adb pull /system/fonts/Roboto-Regular.ttf)") }
func font(_ size: CGFloat, weight: Int) -> CTFont {
  let wght = 0x7767_6874 // 'wght'
  let d = CTFontDescriptorCreateCopyWithVariation(baseDesc, NSNumber(value: wght), CGFloat(weight))
  return CTFontCreateWithFontDescriptor(d, size, nil)
}

func load(_ path: String) -> CGImage {
  guard let src = CGImageSourceCreateWithURL(URL(fileURLWithPath: path) as CFURL, nil),
        let img = CGImageSourceCreateImageAtIndex(src, 0, nil) else { fatalError("cannot read \(path)") }
  return img
}
/// Opaque canvas (no alpha channel in the PNG). Bottom-left origin.
func canvas(_ w: Int, _ h: Int) -> CGContext {
  let c = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0, space: space,
                    bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
  c.interpolationQuality = .high
  return c
}
func save(_ ctx: CGContext, _ path: String) {
  try? fm.createDirectory(atPath: (path as NSString).deletingLastPathComponent, withIntermediateDirectories: true)
  let d = CGImageDestinationCreateWithURL(URL(fileURLWithPath: path) as CFURL, UTType.png.identifier as CFString, 1, nil)!
  CGImageDestinationAddImage(d, ctx.makeImage()!, nil)
  guard CGImageDestinationFinalize(d) else { fatalError("cannot write \(path)") }
  print("wrote \(path) (\(ctx.width)x\(ctx.height))")
}
/// Draws wrapped, centred (or left-aligned) text inside rect; returns the used height.
@discardableResult
func text(_ ctx: CGContext, _ s: String, _ f: CTFont, _ colour: CGColor, in rect: CGRect, centred: Bool = true, vCentre: Bool = true) -> CGFloat {
  var align: CTTextAlignment = centred ? .center : .left
  let para = withUnsafeBytes(of: &align) { p in
    CTParagraphStyleCreate([CTParagraphStyleSetting(spec: .alignment, valueSize: MemoryLayout<CTTextAlignment>.size, value: p.baseAddress!)], 1)
  }
  let attr = NSAttributedString(string: s, attributes: [
    NSAttributedString.Key(kCTFontAttributeName as String): f,
    NSAttributedString.Key(kCTForegroundColorAttributeName as String): colour,
    NSAttributedString.Key(kCTParagraphStyleAttributeName as String): para])
  let fs = CTFramesetterCreateWithAttributedString(attr)
  let fit = CTFramesetterSuggestFrameSizeWithConstraints(fs, CFRange(location: 0, length: 0), nil, rect.size, nil)
  let y = vCentre ? rect.midY - fit.height / 2 : rect.maxY - fit.height
  let r = CGRect(x: rect.minX, y: y, width: rect.width, height: ceil(fit.height) + 1)
  CTFrameDraw(CTFramesetterCreateFrame(fs, CFRange(location: 0, length: 0), CGPath(rect: r, transform: nil), nil), ctx)
  return fit.height
}

// MARK: screenshots
let rawRoot = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "build/store-raw"
var captions: [[String: Any]] = []
for lang in ["en", "de"] {
  for (i, s) in shots.enumerated() {
    let raw = load("\(rawRoot)/\(lang)/\(String(format: "%02d", i + 1)).png")
    precondition(raw.width == 1080 && raw.height == 2160, "expected 1080x2160, got \(raw.width)x\(raw.height)")
    // plain: the capture, flattened onto an opaque canvas
    let plain = canvas(1080, 2160)
    plain.draw(raw, in: CGRect(x: 0, y: 0, width: 1080, height: 2160))
    save(plain, "dist/play/screenshots/\(lang)/\(s.file).png")
    // captioned: 240 px caption strip on top, the screenshot scaled to 1920 px high below it, rounded corners
    let c = canvas(1080, 2160)
    let bg = CGGradient(colorsSpace: space, colors: [rgb(nightLight), rgb(night)] as CFArray, locations: [0, 1])!
    c.drawLinearGradient(bg, start: CGPoint(x: 540, y: 2160), end: CGPoint(x: 540, y: 0), options: [])
    let caption = lang == "en" ? s.en : s.de
    text(c, caption, font(58, weight: 600), rgb(0xFFFFFF), in: CGRect(x: 60, y: 1950, width: 960, height: 190))
    c.setFillColor(rgb(amber)); c.fill(CGRect(x: 490, y: 1938, width: 100, height: 5))
    let shotRect = CGRect(x: 72, y: 0, width: 936, height: 1872)   // 2:1, flush with the bottom edge
    c.saveGState()
    c.addPath(CGPath(roundedRect: shotRect.insetBy(dx: 0, dy: -40), cornerWidth: 40, cornerHeight: 40, transform: nil))
    c.clip()
    c.draw(raw, in: shotRect)
    c.restoreGState()
    c.addPath(CGPath(roundedRect: shotRect.insetBy(dx: -1.5, dy: -41.5), cornerWidth: 41, cornerHeight: 41, transform: nil))
    c.setStrokeColor(rgb(amber, 0.55)); c.setLineWidth(3); c.strokePath()
    save(c, "dist/play/screenshots/\(lang)-captioned/\(s.file).png")
  }
}
for (i, s) in shots.enumerated() {
  captions.append(["number": i + 1, "file": "\(s.file).png", "en": s.en, "de": s.de])
}
let json = try JSONSerialization.data(withJSONObject: ["source": "docs/RELEASE_KIT.md section 8a", "screenshots": captions],
                                      options: [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes])
try json.write(to: URL(fileURLWithPath: "dist/play/screenshots/captions.json"))
print("wrote dist/play/screenshots/captions.json")

// MARK: feature graphic 1024x500 - icon left, text right, everything inside the central ~800x400 area
let icon = load("dist/play/icon-1024.png")
for (lang, lines) in [("en", feature.en), ("de", feature.de)] {
  let c = canvas(1024, 500)
  let bg = CGGradient(colorsSpace: space, colors: [rgb(nightLight), rgb(night)] as CFArray, locations: [0, 1])!
  c.drawRadialGradient(bg, startCenter: CGPoint(x: 300, y: 300), startRadius: 0, endCenter: CGPoint(x: 300, y: 300), endRadius: 760,
                       options: [.drawsAfterEndLocation])
  // icon disc: only the disc and its ring (radius 23/72 of the icon plus the ring), so no icon background shows
  let iconRect = CGRect(x: 92, y: 70, width: 360, height: 360)
  let r = iconRect.width * 23.9 / 72
  c.saveGState()
  c.addEllipse(in: CGRect(x: iconRect.midX - r, y: iconRect.midY - r, width: 2 * r, height: 2 * r)); c.clip()
  c.draw(icon, in: iconRect)
  c.restoreGState()
  let textRect = CGRect(x: 470, y: 120, width: 452, height: 260)
  let headline = font(50, weight: 700), sub = font(27, weight: 400)
  // measure the headline to place the subline below it
  let hRect = CGRect(x: textRect.minX, y: 245, width: textRect.width, height: 140)
  let hh = text(c, lines.0, headline, rgb(0xFFFFFF), in: hRect, centred: false, vCentre: false)
  c.setFillColor(rgb(amber)); c.fill(CGRect(x: textRect.minX, y: hRect.maxY - hh - 26, width: 80, height: 5))
  text(c, lines.1, sub, rgb(0xFFD9A8), in: CGRect(x: textRect.minX, y: 110, width: textRect.width, height: hRect.maxY - hh - 50 - 110),
       centred: false, vCentre: false)
  save(c, lang == "en" ? "dist/play/feature-graphic.png" : "dist/play/feature-graphic-de.png")
}
