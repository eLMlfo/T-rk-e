// SPDX-License-Identifier: AGPL-3.0-only

import CoreGraphics
import Foundation
import XCTest
@testable import CouchSubsCore

final class SubtitleBackgroundTests: XCTestCase {
    private let width = 128
    private let height = 48

    func testKeepsSubtitleOnBluePanelInDarkSceneAndDropsOtherText() {
        var pixels = solid(20, 20, 20)
        fill(&pixels, left: 24, top: 16, right: 104, bottom: 32, red: 33, green: 33, blue: 46)
        fill(&pixels, left: 40, top: 21, right: 88, bottom: 27, red: 230, green: 230, blue: 230)
        let reading = Reading(lines: [
            TextLine("Hey, you've reached Brian Heder.", box: TextBox(left: 0.32, top: 20.0 / 48, right: 0.68, bottom: 27.0 / 48)),
            TextLine("PHOTO MODE", box: TextBox(left: 0.80, top: 0.30, right: 0.95, bottom: 0.36)),
        ])

        XCTAssertEqual(SubtitleBackground.filter(reading, pixels: pixels, width: width, height: height).text,
                       "Hey, you've reached Brian Heder.")
    }

    func testRejectsNeutralDarkPanel() {
        var pixels = solid(20, 20, 20)
        fill(&pixels, left: 24, top: 16, right: 104, bottom: 32, red: 33, green: 33, blue: 33)
        let reading = Reading(lines: [TextLine("Brian", box: TextBox(left: 0.32, top: 20.0 / 48,
                                                                     right: 0.68, bottom: 27.0 / 48))])
        XCTAssertTrue(SubtitleBackground.filter(reading, pixels: pixels, width: width, height: height).lines.isEmpty)
    }

    func testImageCoordinatesMatchTopDownBoxes() {
        var pixels = solid(20, 20, 20)
        // Panel altta olmalı: ortadaki panel, görüntü ters çevrilse de aynı yerde kalır.
        fill(&pixels, left: 24, top: 31, right: 104, bottom: 46, red: 33, green: 33, blue: 46)
        let provider = CGDataProvider(data: Data(pixels) as CFData)!
        let image = CGImage(width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32,
                            bytesPerRow: width * 4, space: CGColorSpaceCreateDeviceRGB(),
                            bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue
                                | CGBitmapInfo.byteOrder32Big.rawValue), provider: provider,
                            decode: nil, shouldInterpolate: false, intent: .defaultIntent)!
        let reading = Reading(lines: [
            TextLine("Menu text", box: TextBox(left: 0.4, top: 5.0 / 48, right: 0.6, bottom: 12.0 / 48)),
            TextLine("Yes.", box: TextBox(left: 0.4, top: 35.0 / 48, right: 0.6, bottom: 42.0 / 48)),
        ])
        XCTAssertEqual(SubtitleBackground.filter(reading, in: image).text, "Yes.")
    }

    func testSettingDefaultsToOffAndSurvivesSave() throws {
        XCTAssertFalse(try JSONDecoder().decode(UserSettings.self, from: Data("{}".utf8)).watchSubtitleBackground)
        var settings = UserSettings()
        settings.watchSubtitleBackground = true
        XCTAssertTrue(try JSONDecoder().decode(UserSettings.self, from: JSONEncoder().encode(settings)).watchSubtitleBackground)
    }

    private func solid(_ red: UInt8, _ green: UInt8, _ blue: UInt8) -> [UInt8] {
        var pixels = [UInt8](repeating: 0, count: width * height * 4)
        fill(&pixels, left: 0, top: 0, right: width, bottom: height, red: red, green: green, blue: blue)
        return pixels
    }

    private func fill(_ pixels: inout [UInt8], left: Int, top: Int, right: Int, bottom: Int,
                      red: UInt8, green: UInt8, blue: UInt8) {
        for y in top..<bottom {
            for x in left..<right {
                let i = (y * width + x) * 4
                pixels[i] = red
                pixels[i + 1] = green
                pixels[i + 2] = blue
                pixels[i + 3] = 255
            }
        }
    }
}
